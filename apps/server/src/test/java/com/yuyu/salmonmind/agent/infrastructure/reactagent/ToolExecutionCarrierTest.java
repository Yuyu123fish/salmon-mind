package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** 验证 Java 21 虚拟线程载体、平台线程回退和实际 Handler timeout 边界。 */
class ToolExecutionCarrierTest {

    @Test
    void usesVirtualThreadCarrierWhenEnabled() throws Exception {
        try (ToolExecutionCarrier carrier = ToolExecutionCarrier.create(
                true, 2, Duration.ofSeconds(1))) {
            ToolCallResponse response = carrier.call(
                    new ToolCallRequest("tool", "{}", "id", java.util.Map.of()),
                    request -> ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                            Boolean.toString(Thread.currentThread().isVirtual())));
            assertThat(response.getResult()).isEqualTo("true");
        }
    }

    @Test
    void fallsBackToPlatformHandlerCarrierWhenDisabled() throws Exception {
        try (ToolExecutionCarrier carrier = ToolExecutionCarrier.create(
                false, 2, Duration.ofSeconds(1))) {
            ToolCallResponse response = carrier.call(
                    new ToolCallRequest("tool", "{}", "id", java.util.Map.of()),
                    request -> ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                            Boolean.toString(Thread.currentThread().isVirtual())));
            assertThat(response.getResult()).isEqualTo("false");
        }
    }

    @Test
    void timeoutStartsAtHandlerSubmission() {
        try (ToolExecutionCarrier carrier = ToolExecutionCarrier.create(
                true, 2, Duration.ofMillis(50))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> carrier.call(
                    new ToolCallRequest("tool", "{}", "id", java.util.Map.of()),
                    request -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), "late");
                    })).isInstanceOf(TimeoutException.class);
        }
    }
}
