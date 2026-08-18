package com.yuyu.salmonmind.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuyu.salmonmind.conversation.api.AssistantCompletionStatus;
import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.api.RunResultStatus;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import com.yuyu.salmonmind.conversation.application.port.ConversationHistoryRepository;
import com.yuyu.salmonmind.conversation.application.port.ConversationMetadataRepository;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class ConversationRecoveryServiceTest {

    @Test
    void rebuildsContinuationFailureCodeWhenRunIsMissing() {
        ConversationHistoryRepository historyRepository = mock(ConversationHistoryRepository.class);
        ConversationMetadataRepository metadataRepository = mock(ConversationMetadataRepository.class);
        Fixture fixture = fixture();
        when(historyRepository.read(fixture.conversationId())).thenReturn(fixture.history());
        when(metadataRepository.findRunById(fixture.runId())).thenReturn(null);

        new ConversationRecoveryService(historyRepository, metadataRepository)
                .reconcile(fixture.conversationId(), fixture.conversation());

        ArgumentCaptor<Run> inserted = ArgumentCaptor.forClass(Run.class);
        verify(metadataRepository).insertRun(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo(Run.RunStatus.SUCCEEDED);
        assertThat(inserted.getValue().resultStatus()).isEqualTo(RunResultStatus.INCOMPLETE_LENGTH);
        assertThat(inserted.getValue().errorCode()).isEqualTo("OUTPUT_CONTINUATION_FAILED");
    }

    @Test
    void repairsExistingRunToJsonlContinuationFailureCode() {
        ConversationHistoryRepository historyRepository = mock(ConversationHistoryRepository.class);
        ConversationMetadataRepository metadataRepository = mock(ConversationMetadataRepository.class);
        Fixture fixture = fixture();
        when(historyRepository.read(fixture.conversationId())).thenReturn(fixture.history());
        when(metadataRepository.findRunById(fixture.runId())).thenReturn(new Run(
                fixture.runId(), fixture.conversationId(), fixture.userEntryId(),
                Run.RunStatus.FAILED, "INTERNAL_ERROR", fixture.createdAt(), null));

        new ConversationRecoveryService(historyRepository, metadataRepository)
                .reconcile(fixture.conversationId(), fixture.conversation());

        ArgumentCaptor<Run> repaired = ArgumentCaptor.forClass(Run.class);
        verify(metadataRepository).updateRun(repaired.capture());
        assertThat(repaired.getValue().status()).isEqualTo(Run.RunStatus.SUCCEEDED);
        assertThat(repaired.getValue().resultStatus()).isEqualTo(RunResultStatus.INCOMPLETE_LENGTH);
        assertThat(repaired.getValue().errorCode()).isEqualTo("OUTPUT_CONTINUATION_FAILED");
    }

    private static Fixture fixture() {
        UUID conversationId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID userEntryId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-18T00:00:00Z");
        Entry user = new Entry(
                ConversationHistory.FORMAT_VERSION, conversationId, userEntryId, 1, null,
                Entry.EntryType.USER_MESSAGE, createdAt, new UserMessagePayload("问题", runId));
        Entry assistant = new Entry(
                ConversationHistory.FORMAT_VERSION, conversationId, UUID.randomUUID(), 2, userEntryId,
                Entry.EntryType.ASSISTANT_MESSAGE, createdAt.plusSeconds(1),
                new AssistantMessagePayload("首段正文", runId, "provider", "model", null,
                        List.of(), List.of(), List.of(), AssistantCompletionStatus.INCOMPLETE_LENGTH,
                        "OUTPUT_CONTINUATION_FAILED"));
        ConversationHistory history = new ConversationHistory(
                new ConversationHistory.Header(ConversationHistory.FORMAT_VERSION, conversationId, createdAt),
                List.of(user, assistant), List.of(0L, 100L));
        Conversation conversation = new Conversation(
                conversationId, workspaceId, "新对话", ConversationHistory.FORMAT_VERSION,
                userEntryId, user.seq(), null, null, null, createdAt, createdAt);
        return new Fixture(conversationId, userEntryId, runId, createdAt, conversation, history);
    }

    private record Fixture(
            UUID conversationId,
            UUID userEntryId,
            UUID runId,
            Instant createdAt,
            Conversation conversation,
            ConversationHistory history
    ) {
    }
}
