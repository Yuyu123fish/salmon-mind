package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.RunTraceItemPayload;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Assistant Run Trace 的 JSONL v1 可选字段与 HTTP 枚举格式兼容测试。 */
class JsonlCodecRunTraceTest {

    @Test
    void roundTripsCompleteAndTruncatedTraceAndReadsOldEntryWithoutField() throws Exception {
        JsonlCodec codec = new JsonlCodec();
        List<RunTraceItemPayload> trace = List.of(
                RunTraceItemPayload.reasoning("先分析问题", false),
                RunTraceItemPayload.tool(
                        "call-1", "search", RunTraceItemPayload.ToolStatus.COMPLETED,
                        "命中 2 个来源", null, false),
                RunTraceItemPayload.reasoning("达到展示上限", true),
                RunTraceItemPayload.tool(
                        "call-2", "search", RunTraceItemPayload.ToolStatus.FAILED,
                        "搜索暂不可用", "TOOL_FAILED", true));
        Entry entry = assistantEntry(new AssistantMessagePayload(
                "最终回答", UUID.randomUUID(), "provider", "model", null, List.of(), trace));

        String line = codec.encodeEntry(entry);
        AssistantMessagePayload decoded = (AssistantMessagePayload) codec.decodeEntry(line).payload();
        String httpJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(decoded.trace());

        assertThat(decoded.trace()).containsExactlyElementsOf(trace);
        assertThat(line).contains("\"kind\":\"reasoning\"", "\"status\":\"failed\"",
                "\"truncated\":true");
        assertThat(httpJson).contains("\"kind\":\"REASONING\"", "\"toolStatus\":\"FAILED\"");

        Entry oldEntry = assistantEntry(new AssistantMessagePayload(
                "旧回答", UUID.randomUUID(), "provider", "model", null));
        String oldLine = codec.encodeEntry(oldEntry);
        assertThat(oldLine).doesNotContain("\"trace\"");
        assertThat(((AssistantMessagePayload) codec.decodeEntry(oldLine).payload()).trace()).isEmpty();
    }

    private static Entry assistantEntry(AssistantMessagePayload payload) {
        return new Entry(
                ConversationHistory.FORMAT_VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                null,
                Entry.EntryType.ASSISTANT_MESSAGE,
                Instant.parse("2026-08-18T00:00:00Z"),
                payload);
    }
}
