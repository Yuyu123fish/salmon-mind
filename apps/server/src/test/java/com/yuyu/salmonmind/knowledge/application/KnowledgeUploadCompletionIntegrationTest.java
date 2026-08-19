package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.api.DocumentSummary;
import com.yuyu.salmonmind.knowledge.application.port.DocumentParserPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.knowledge.application.port.ResumableUploadStoragePort;
import com.yuyu.salmonmind.knowledge.application.port.UploadSessionRepository;
import com.yuyu.salmonmind.knowledge.domain.DocumentFormat;
import com.yuyu.salmonmind.knowledge.domain.IngestionJobState;
import com.yuyu.salmonmind.knowledge.domain.KnowledgeSourceLifecycle;
import com.yuyu.salmonmind.knowledge.domain.PartReceipt;
import com.yuyu.salmonmind.knowledge.domain.UploadSession;
import com.yuyu.salmonmind.knowledge.domain.UploadSessionStatus;
import com.yuyu.salmonmind.knowledge.infrastructure.redis.KnowledgeUploadProperties;
import com.yuyu.salmonmind.workspace.api.Workspace;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Complete 固定顺序与固定 final key 幂等提交的 application 级证据。 */
class KnowledgeUploadCompletionIntegrationTest {

    @Test
    void repeatedCompletionReusesTheSameSubmission() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        String sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        UploadSession session = new UploadSession(sessionId, workspaceId, "a.txt", "text/plain", 3,
                "finger", 0, 65_536, 1, 2, now, now.plusSeconds(100), now.plusSeconds(1000),
                UploadSessionStatus.UPLOADING, "knowledge/upload-parts/v1/x/", "knowledge/upload-finals/v1/x.bin",
                Map.of(1, new PartReceipt(1, "knowledge/upload-parts/v1/x/1-" + sha + ".part", 3, sha, now)),
                Map.of(), null, null, null);
        KnowledgeMetadataPort metadata = mock(KnowledgeMetadataPort.class);
        ResumableUploadStoragePort storage = mock(ResumableUploadStoragePort.class);
        UploadSessionRepository sessions = mock(UploadSessionRepository.class);
        when(sessions.find(workspaceId, sessionId)).thenReturn(session);
        when(sessions.fenceCompletion(any(), any(), any(), any()))
                .thenReturn(new UploadSessionRepository.CompletionFence(session, true, false))
                .thenReturn(new UploadSessionRepository.CompletionFence(
                        session.withState(UploadSessionStatus.COMPLETED, sourceId, null, null), false, true));
        when(storage.listObjects(any(), any(), anyInt())).thenReturn(new ResumableUploadStoragePort.ObjectPage(List.of(), null, false));
        when(storage.headObject(session.finalObjectKey())).thenReturn(
                new ResumableUploadStoragePort.ObjectHead(session.finalObjectKey(), 3, "text/plain", now));
        when(metadata.findSubmissionByObjectKey(workspaceId, session.finalObjectKey())).thenReturn(null);
        KnowledgeMetadataPort.Submission submission = new KnowledgeMetadataPort.Submission(sourceId, revisionId, jobId, 1);
        when(metadata.createSubmission(workspaceId, "a.txt", DocumentFormat.TEXT, "text/plain", "text/plain",
                3, sha, session.finalObjectKey())).thenReturn(submission);
        when(metadata.find(workspaceId, sourceId)).thenReturn(storedDocument(workspaceId, sourceId, revisionId, jobId));
        when(sessions.markCompleted(workspaceId, sessionId, sourceId))
                .thenReturn(session.withState(UploadSessionStatus.COMPLETED, sourceId, null, null));
        doAnswer(invocation -> {
            Files.writeString((Path) invocation.getArgument(1), "abc");
            return null;
        }).when(storage).downloadObject(any(), any());
        DocumentParserPort parser = mock(DocumentParserPort.class);
        when(parser.detect(any())).thenReturn("text/plain");
        KnowledgeQueuePort queue = mock(KnowledgeQueuePort.class);
        when(queue.dispatch(jobId, 1, 1)).thenReturn("1-0");
        KnowledgeUploadApplicationService service = service(workspaceId, sessions, storage, parser, metadata, queue);

        DocumentSummary first = service.complete(sessionId);
        DocumentSummary second = service.complete(sessionId);

        assertThat(first.id()).isEqualTo(sourceId);
        assertThat(second.id()).isEqualTo(sourceId);
        verify(metadata).createSubmission(workspaceId, "a.txt", DocumentFormat.TEXT, "text/plain", "text/plain",
                3, sha, session.finalObjectKey());
    }

    private static KnowledgeUploadApplicationService service(UUID workspaceId, UploadSessionRepository sessions,
                                                              ResumableUploadStoragePort storage, DocumentParserPort parser,
                                                              KnowledgeMetadataPort metadata, KnowledgeQueuePort queue) {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties(true, 100_000, 65_536, 2,
                Duration.ofMinutes(10), Duration.ofHours(2), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofSeconds(5), 10, "salmon:knowledge:upload:v1:", Duration.ofSeconds(5), Duration.ofSeconds(2));
        WorkspaceRegistry workspace = () -> new Workspace(workspaceId, "Test", Instant.now());
        return new KnowledgeUploadApplicationService(workspace, properties, sessions, storage, parser, metadata, queue, 1_000_000);
    }

    private static KnowledgeMetadataPort.StoredDocument storedDocument(UUID workspaceId, UUID sourceId,
                                                                         UUID revisionId, UUID jobId) {
        Instant now = Instant.now();
        KnowledgeMetadataPort.StoredRevision revision = new KnowledgeMetadataPort.StoredRevision(revisionId, sourceId,
                "a.txt", DocumentFormat.TEXT, "text/plain", "text/plain", 3,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "knowledge/upload-finals/v1/x.bin", 0, 0, now);
        KnowledgeMetadataPort.StoredJob job = new KnowledgeMetadataPort.StoredJob(jobId, revisionId, 1,
                IngestionJobState.PENDING_DISPATCH, false, null, null, now, now, null, null, null);
        return new KnowledgeMetadataPort.StoredDocument(sourceId, workspaceId, KnowledgeSourceLifecycle.ACTIVE,
                "a.txt", revision, job, List.of(job), 0, now, now);
    }
}
