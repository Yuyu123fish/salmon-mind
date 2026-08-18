package com.yuyu.salmonmind.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuyu.salmonmind.knowledge.api.DocumentSummary;
import com.yuyu.salmonmind.knowledge.api.DocumentUpload;
import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.DocumentParserPort;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.knowledge.application.port.ObjectStoragePort;
import com.yuyu.salmonmind.knowledge.domain.DocumentFormat;
import com.yuyu.salmonmind.knowledge.domain.IngestionJobState;
import com.yuyu.salmonmind.knowledge.domain.KnowledgeSourceLifecycle;
import com.yuyu.salmonmind.workspace.api.Workspace;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 上传提交边界测试：输入约束和 Redis 双写失败不能绕过 PostgreSQL 权威状态。 */
class KnowledgeApplicationServiceTest {

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final UUID REVISION_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();

    private final WorkspaceRegistry workspace = () -> new Workspace(WORKSPACE_ID, "Test", Instant.now());
    private final KnowledgeMetadataPort metadata = mock(KnowledgeMetadataPort.class);
    private final KnowledgeQueuePort queue = mock(KnowledgeQueuePort.class);
    private final ObjectStoragePort objectStorage = mock(ObjectStoragePort.class);
    private final DocumentParserPort parser = mock(DocumentParserPort.class);
    private final EvidenceIndexPort evidenceIndex = mock(EvidenceIndexPort.class);
    private final KnowledgeDeletion deletion = mock(KnowledgeDeletion.class);
    private KnowledgeApplicationService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeApplicationService(
                workspace, metadata, queue, objectStorage, parser, evidenceIndex, deletion, 10);
    }

    @Test
    void rejectsFileWhenItExceedsLimitBeforeStoring() {
        try {
            service.upload(new DocumentUpload("large.txt", "text/plain",
                    new ByteArrayInputStream("01234567890".getBytes(StandardCharsets.UTF_8))));
            fail("超限文件应被拒绝");
        } catch (KnowledgeException ex) {
            assertThat(ex.code()).isEqualTo(KnowledgeException.Code.FILE_TOO_LARGE);
        }
        verify(objectStorage, never()).put(any(), any(), any());
        verify(metadata, never()).createSubmission(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void rejectsPdfWhoseContentIsActuallyText() {
        when(parser.detect(any())).thenReturn("text/plain");

        try {
            service.upload(new DocumentUpload("disguised.pdf", "application/pdf",
                    new ByteArrayInputStream("not a pdf".getBytes(StandardCharsets.UTF_8))));
            fail("伪装格式应被拒绝");
        } catch (KnowledgeException ex) {
            assertThat(ex.code()).isEqualTo(KnowledgeException.Code.INVALID_UPLOAD);
        }
        verify(objectStorage, never()).put(any(), any(), any());
        verify(metadata, never()).createSubmission(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void rejectsEmptyTextWhenDeclaredTypeIsNotCompatible() {
        try {
            service.upload(new DocumentUpload("empty.txt", "image/png",
                    new ByteArrayInputStream(new byte[0])));
            fail("空文本也必须校验声明类型");
        } catch (KnowledgeException ex) {
            assertThat(ex.code()).isEqualTo(KnowledgeException.Code.INVALID_UPLOAD);
        }
        verify(objectStorage, never()).put(any(), any(), any());
        verify(metadata, never()).createSubmission(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void keepsPendingDispatchWhenRedisFailsAfterDatabaseSubmission() {
        when(parser.detect(any())).thenReturn("text/plain");
        when(metadata.createSubmission(eq(WORKSPACE_ID), eq("note.txt"), eq(DocumentFormat.TEXT),
                eq("text/plain"), eq("text/plain"), eq(6L), any(), any()))
                .thenReturn(new KnowledgeMetadataPort.Submission(SOURCE_ID, REVISION_ID, JOB_ID, 1));
        when(queue.dispatch(JOB_ID, 1, 1)).thenThrow(new KnowledgeException(
                KnowledgeException.Code.KNOWLEDGE_QUEUE_UNAVAILABLE, "测试队列不可用"));
        when(metadata.find(WORKSPACE_ID, SOURCE_ID)).thenReturn(pendingDocument());

        DocumentSummary summary = service.upload(new DocumentUpload("note.txt", "text/plain",
                new ByteArrayInputStream("正文".getBytes(StandardCharsets.UTF_8))));

        assertThat(summary.state()).isEqualTo(IngestionJobState.PENDING_DISPATCH.name());
        assertThat(summary.latestJobId()).isEqualTo(JOB_ID);
        verify(objectStorage).put(any(), any(), eq("text/plain"));
        verify(metadata, never()).markQueued(any(), any());
    }

    @Test
    void treatsOctetStreamDeclarationAsUnknownAndUsesDetectedType() {
        when(parser.detect(any())).thenReturn("text/plain");
        when(metadata.createSubmission(eq(WORKSPACE_ID), eq("note.txt"), eq(DocumentFormat.TEXT),
                eq("text/plain"), eq("text/plain"), eq(6L), any(), any()))
                .thenReturn(new KnowledgeMetadataPort.Submission(SOURCE_ID, REVISION_ID, JOB_ID, 1));
        when(queue.dispatch(JOB_ID, 1, 1)).thenReturn("1-0");
        when(metadata.find(WORKSPACE_ID, SOURCE_ID)).thenReturn(pendingDocument());

        DocumentSummary summary = service.upload(new DocumentUpload("note.txt", "application/octet-stream",
                new ByteArrayInputStream("正文".getBytes(StandardCharsets.UTF_8))));

        assertThat(summary.latestJobId()).isEqualTo(JOB_ID);
        verify(objectStorage).put(any(), any(), eq("text/plain"));
        verify(metadata).markQueued(JOB_ID, "1-0");
    }

    @Test
    void deleteDelegatesWithTheCurrentWorkspaceIdentity() {
        service.delete(SOURCE_ID);

        verify(deletion).delete(WORKSPACE_ID, SOURCE_ID);
    }

    private static KnowledgeMetadataPort.StoredDocument pendingDocument() {
        Instant now = Instant.now();
        KnowledgeMetadataPort.StoredRevision revision = new KnowledgeMetadataPort.StoredRevision(
                REVISION_ID, SOURCE_ID, "note.txt", DocumentFormat.TEXT, "text/plain", "text/plain",
                6, "a".repeat(64), "knowledge/documents/test.bin", 0, 0, now);
        KnowledgeMetadataPort.StoredJob job = new KnowledgeMetadataPort.StoredJob(
                JOB_ID, REVISION_ID, 1, IngestionJobState.PENDING_DISPATCH, false,
                null, null, now, now, null, null, null);
        return new KnowledgeMetadataPort.StoredDocument(
                SOURCE_ID, WORKSPACE_ID, KnowledgeSourceLifecycle.ACTIVE,
                "note.txt", revision, job, List.of(job), 0, now, now);
    }
}
