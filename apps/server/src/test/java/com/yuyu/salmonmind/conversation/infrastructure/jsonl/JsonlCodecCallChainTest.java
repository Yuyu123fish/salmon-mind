package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CallChainReferencePayload;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Assistant 调用链引用的 JSONL 兼容性测试；详情字段不进入 Conversation。 */
class JsonlCodecCallChainTest {

    @Test
    void roundTripsReferenceAndReadsOldAssistantAsEmpty() {
        UUID repositoryId = UUID.randomUUID();
        CallChainReferencePayload reference = new CallChainReferencePayload(
                UUID.randomUUID(), repositoryId, "入口到结果", 3, 2);
        Entry entry = assistant(new AssistantMessagePayload(
                "回答", UUID.randomUUID(), "provider", "model", null,
                List.of(), List.of(), List.of(),
                com.yuyu.salmonmind.conversation.api.AssistantCompletionStatus.COMPLETE,
                null, List.of(reference)));
        JsonlCodec codec = new JsonlCodec();

        String line = codec.encodeEntry(entry);
        AssistantMessagePayload decoded = (AssistantMessagePayload) codec.decodeEntry(line).payload();
        assertThat(decoded.callChains()).containsExactly(reference);
        assertThat(line).contains("\"callChains\"").doesNotContain("\"source\"");

        AssistantMessagePayload old = (AssistantMessagePayload) codec.decodeEntry(
                codec.encodeEntry(assistant(new AssistantMessagePayload(
                        "旧回答", UUID.randomUUID(), "provider", "model", null)))).payload();
        assertThat(old.callChains()).isEmpty();
    }

    private static Entry assistant(AssistantMessagePayload payload) {
        return new Entry(ConversationHistory.FORMAT_VERSION, UUID.randomUUID(), UUID.randomUUID(), 1,
                null, Entry.EntryType.ASSISTANT_MESSAGE, Instant.parse("2026-08-20T00:00:00Z"), payload);
    }
}
