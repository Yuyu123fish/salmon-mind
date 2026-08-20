package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

/** Run Context Meter 只按实际 Tool Response 计量，并清理较旧结果。 */
class RunContextMeterTest {

    @Test
    void cleansOldToolResponsesAndKeepsRecentClosure() {
        RunContextMeter meter = new RunContextMeter(500, 100, 100);
        ModelRequest request = request(List.of(
                new UserMessage("用户问题"),
                toolResponse("old", "x".repeat(1_000)),
                toolResponse("recent", "y".repeat(300))));

        RunContextMeter.Prepared prepared = meter.prepare(request);

        assertThat(prepared.cleaned()).isTrue();
        assertThat(prepared.request().getMessages()).hasSize(3);
        assertThat(prepared.request().getMessages().get(1)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage cleaned = (ToolResponseMessage) prepared.request().getMessages().get(1);
        assertThat(cleaned.getResponses()).singleElement().satisfies(response ->
                assertThat(response.responseData()).startsWith("[tool-result-cleaned:old]"));
        assertThat(meter.snapshot().remainingInputTokens()).isGreaterThan(0);
    }

    @Test
    void rejectsWhenActualMessagesStillExceedPhysicalInputCeiling() {
        RunContextMeter meter = new RunContextMeter(1_000, 100, 100);
        ModelRequest request = request(List.of(
                new UserMessage("用户问题"),
                toolResponse("recent", "x".repeat(10_000))));

        assertThatThrownBy(() -> meter.prepare(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("context input limit exceeded");
    }

    @Test
    void recordsActualResultAndTruncationForTrace() {
        RunContextMeter meter = new RunContextMeter(1_000, 100, 100);

        meter.recordToolResult(100, false);
        long remainingAfterFirst = meter.snapshot().remainingInputTokens();
        meter.recordToolResult(321, true);

        assertThat(meter.snapshot().estimatedResultTokens()).isEqualTo(321);
        assertThat(meter.snapshot().remainingInputTokens()).isLessThan(remainingAfterFirst);
        assertThat(meter.snapshot().resultTruncated()).isTrue();
    }

    @Test
    void protectsClosureReserveFromOrdinaryToolResults() {
        RunContextMeter meter = new RunContextMeter(1_000, 900, 100, 100, 200);

        assertThat(meter.recordToolResult(700, false, false)).isTrue();
        assertThat(meter.recordToolResult(1, false, false)).isFalse();
        assertThat(meter.recordToolResult(200, false, true)).isTrue();
    }

    private static ModelRequest request(List<org.springframework.ai.chat.messages.Message> messages) {
        return new ModelRequest(null, messages, null, List.of(), List.of(), Map.of(), Map.of());
    }

    private static ToolResponseMessage toolResponse(String id, String value) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "tool", value)))
                .build();
    }
}
