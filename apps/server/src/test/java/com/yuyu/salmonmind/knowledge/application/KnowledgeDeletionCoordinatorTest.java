package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.ObjectStoragePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 删除深模块测试：外部步骤失败时保持稳定失败语义，不提前完成 PostgreSQL 清理。 */
class KnowledgeDeletionCoordinatorTest {

    private final KnowledgeMetadataPort metadata = mock(KnowledgeMetadataPort.class);
    private final EvidenceIndexPort evidenceIndex = mock(EvidenceIndexPort.class);
    private final ObjectStoragePort objectStorage = mock(ObjectStoragePort.class);
    private final KnowledgeDeletionCoordinator coordinator =
            new KnowledgeDeletionCoordinator(metadata, evidenceIndex, objectStorage);

    @Test
    void indexFailureStopsBeforeObjectAndMetadataFinalization() {
        KnowledgeMetadataPort.DeletionTarget target = target();
        when(metadata.markDeleting(target.workspaceId(), target.sourceId())).thenReturn(target);
        doThrow(new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE, "index down"))
                .when(evidenceIndex).deleteForRevisions("salmon-index", List.of(target.revisionIds().get(0)));

        assertThatThrownBy(() -> coordinator.delete(target.workspaceId(), target.sourceId()))
                .isInstanceOf(KnowledgeException.class)
                .extracting(error -> ((KnowledgeException) error).code())
                .isEqualTo(KnowledgeException.Code.DOCUMENT_DELETE_INCOMPLETE);

        verify(objectStorage, never()).deleteStrict(any());
        verify(metadata, never()).finalizeDeletion(any());
    }

    @Test
    void successfulExternalCleanupReachesPostgresFinalization() {
        KnowledgeMetadataPort.DeletionTarget target = target();
        when(metadata.markDeleting(target.workspaceId(), target.sourceId())).thenReturn(target);

        coordinator.delete(target.workspaceId(), target.sourceId());

        verify(evidenceIndex).deleteForRevisions("salmon-index", target.revisionIds());
        verify(objectStorage).deleteStrict("knowledge/documents/source.bin");
        verify(metadata).finalizeDeletion(target);
    }

    @Test
    void objectFailureStopsBeforePostgresFinalization() {
        KnowledgeMetadataPort.DeletionTarget target = target();
        when(metadata.markDeleting(target.workspaceId(), target.sourceId())).thenReturn(target);
        doThrow(new KnowledgeException(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE, "object down"))
                .when(objectStorage).deleteStrict("knowledge/documents/source.bin");

        assertThatThrownBy(() -> coordinator.delete(target.workspaceId(), target.sourceId()))
                .isInstanceOf(KnowledgeException.class)
                .extracting(error -> ((KnowledgeException) error).code())
                .isEqualTo(KnowledgeException.Code.DOCUMENT_DELETE_INCOMPLETE);

        verify(metadata, never()).finalizeDeletion(any());
    }

    @Test
    void eligibilityFailureIsNotRewrittenAsIncompleteExternalFailure() {
        UUID workspaceId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        when(metadata.markDeleting(workspaceId, sourceId)).thenThrow(new KnowledgeException(
                KnowledgeException.Code.DOCUMENT_DELETE_NOT_ALLOWED, "processing"));

        assertThatThrownBy(() -> coordinator.delete(workspaceId, sourceId))
                .isInstanceOf(KnowledgeException.class)
                .extracting(error -> ((KnowledgeException) error).code())
                .isEqualTo(KnowledgeException.Code.DOCUMENT_DELETE_NOT_ALLOWED);
        verify(evidenceIndex, never()).deleteForRevisions(any(), any());
    }

    private static KnowledgeMetadataPort.DeletionTarget target() {
        UUID workspaceId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        return new KnowledgeMetadataPort.DeletionTarget(
                workspaceId,
                sourceId,
                List.of(new KnowledgeMetadataPort.RevisionTarget(
                        revisionId, "knowledge/documents/source.bin", List.of(UUID.randomUUID()))),
                List.of(new KnowledgeMetadataPort.GenerationTarget(
                        UUID.randomUUID(), "salmon-index", List.of(UUID.randomUUID()))));
    }
}
