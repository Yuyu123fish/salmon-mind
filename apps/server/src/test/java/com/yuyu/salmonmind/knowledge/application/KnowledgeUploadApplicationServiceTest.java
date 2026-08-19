package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.api.UploadInitRequest;
import com.yuyu.salmonmind.knowledge.application.port.DocumentParserPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.knowledge.application.port.ResumableUploadStoragePort;
import com.yuyu.salmonmind.knowledge.application.port.UploadSessionRepository;
import com.yuyu.salmonmind.knowledge.domain.UploadSession;
import com.yuyu.salmonmind.knowledge.infrastructure.redis.KnowledgeUploadProperties;
import com.yuyu.salmonmind.workspace.api.Workspace;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Upload policy、Session 初始化和 Workspace 隔离的 application 边界。 */
class KnowledgeUploadApplicationServiceTest {

    private static final UUID WORKSPACE_ID = UUID.randomUUID();

    @Test
    void policyAndInitUseBoundedServerValues() {
        UploadSessionRepository sessions = mock(UploadSessionRepository.class);
        when(sessions.create(any(UploadSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeUploadApplicationService service = service(sessions, true);

        assertThat(service.policy().resumableThresholdBytes()).isEqualTo(100_000);
        var view = service.init(new UploadInitRequest("notes.txt", "text/plain", 130_000, "file|130000", 12));

        assertThat(view.totalParts()).isEqualTo(2);
        assertThat(view.confirmedBytes()).isZero();
        assertThat(view.fileName()).isEqualTo("notes.txt");
    }

    @Test
    void disabledFlagReturnsStable503Code() {
        KnowledgeUploadApplicationService service = service(mock(UploadSessionRepository.class), false);

        assertThatThrownBy(() -> service.init(new UploadInitRequest("notes.txt", "text/plain", 130_000, "f", 0)))
                .isInstanceOf(KnowledgeException.class)
                .extracting(error -> ((KnowledgeException) error).code())
                .isEqualTo(KnowledgeException.Code.RESUMABLE_UPLOAD_DISABLED);
    }

    private static KnowledgeUploadApplicationService service(UploadSessionRepository sessions, boolean enabled) {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties(enabled, 100_000,
                65_536, 2, Duration.ofMinutes(10), Duration.ofHours(2), Duration.ofHours(1),
                Duration.ofHours(1), Duration.ofSeconds(5), 10, "salmon:knowledge:upload:v1:",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        WorkspaceRegistry workspace = () -> new Workspace(WORKSPACE_ID, "Test", Instant.now());
        return new KnowledgeUploadApplicationService(workspace, properties, sessions,
                mock(ResumableUploadStoragePort.class), mock(DocumentParserPort.class),
                mock(KnowledgeMetadataPort.class), mock(KnowledgeQueuePort.class), 1_000_000);
    }
}
