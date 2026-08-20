package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 混合工具批次的并行组、屏障组和原始顺序调度测试。 */
class ToolExecutionBatchCoordinatorTest {

    @Test
    void barrierWaitsForPriorSafeGroupAndLaterSafeGroupWaitsForBarrier() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("a", "function", "safe_a", "{}"),
                new AssistantMessage.ToolCall("b", "function", "safe_b", "{}"),
                new AssistantMessage.ToolCall("c", "function", "barrier", "{}"),
                new AssistantMessage.ToolCall("d", "function", "safe_d", "{}"));
        OverAllState state = new OverAllState(Map.of(
                "messages", List.of(AssistantMessage.builder().toolCalls(calls).build())));
        RunnableConfig config = RunnableConfig.builder().build();
        ToolExecutionBatchCoordinator coordinator = new ToolExecutionBatchCoordinator(
                Map.of("safe_a", true, "safe_b", true, "barrier", false, "safe_d", true), 2);

        ToolExecutionBatchCoordinator.Permit first = coordinator.acquire(request("a", config, state));
        ToolExecutionBatchCoordinator.Permit second = coordinator.acquire(request("b", config, state));
        CompletableFuture<ToolExecutionBatchCoordinator.Permit> barrier = CompletableFuture.supplyAsync(
                () -> acquireUnchecked(coordinator, request("c", config, state)));
        CompletableFuture<ToolExecutionBatchCoordinator.Permit> later = CompletableFuture.supplyAsync(
                () -> acquireUnchecked(coordinator, request("d", config, state)));

        Thread.sleep(50);
        assertThat(barrier).isNotDone();
        assertThat(later).isNotDone();

        first.close();
        second.close();
        ToolExecutionBatchCoordinator.Permit barrierPermit = barrier.get(1, TimeUnit.SECONDS);
        assertThat(later).isNotDone();

        barrierPermit.close();
        ToolExecutionBatchCoordinator.Permit laterPermit = later.get(1, TimeUnit.SECONDS);
        laterPermit.close();
    }

    @Test
    void barrierWaitHasAnAdmissionUpperBound() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("a", "function", "safe_a", "{}"),
                new AssistantMessage.ToolCall("c", "function", "barrier", "{}"));
        OverAllState state = new OverAllState(Map.of(
                "messages", List.of(AssistantMessage.builder().toolCalls(calls).build())));
        RunnableConfig config = RunnableConfig.builder().build();
        ToolExecutionBatchCoordinator coordinator = new ToolExecutionBatchCoordinator(
                Map.of("safe_a", true, "barrier", false), 2, Duration.ofMillis(25));

        ToolExecutionBatchCoordinator.Permit first = coordinator.acquire(request("a", config, state));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> coordinator.acquire(request("c", config, state)))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        first.close();
    }

    @Test
    void rebuildsPlanForEachAssistantToolBatchInTheSameRun() throws Exception {
        OverAllState firstState = state(List.of(
                new AssistantMessage.ToolCall("first", "function", "safe_a", "{}")));
        ToolExecutionBatchCoordinator coordinator = new ToolExecutionBatchCoordinator(
                Map.of("safe_a", true, "safe_b", true, "barrier", false), 2);
        ToolExecutionBatchCoordinator.Permit first = coordinator.acquire(
                request("first", RunnableConfig.builder().build(), firstState));
        first.close();

        List<AssistantMessage.ToolCall> secondCalls = List.of(
                new AssistantMessage.ToolCall("second-safe", "function", "safe_b", "{}"),
                new AssistantMessage.ToolCall("second-barrier", "function", "barrier", "{}"));
        OverAllState secondState = new OverAllState(Map.of("messages", List.of(
                AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("first", "function", "safe_a", "{}"))).build(),
                AssistantMessage.builder().toolCalls(secondCalls).build())));
        RunnableConfig config = RunnableConfig.builder().build();

        ToolExecutionBatchCoordinator.Permit safe = coordinator.acquire(
                request("second-safe", config, secondState));
        CompletableFuture<ToolExecutionBatchCoordinator.Permit> barrier = CompletableFuture.supplyAsync(
                () -> acquireUnchecked(coordinator, request("second-barrier", config, secondState)));

        Thread.sleep(50);
        assertThat(barrier).isNotDone();
        safe.close();
        barrier.get(1, TimeUnit.SECONDS).close();
    }

    @Test
    void consecutiveBarriersKeepModelOrderEvenWhenLaterTaskArrivesFirst() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("first-barrier", "function", "barrier", "{}"),
                new AssistantMessage.ToolCall("second-barrier", "function", "barrier", "{}"));
        OverAllState state = state(calls);
        RunnableConfig config = RunnableConfig.builder().build();
        ToolExecutionBatchCoordinator coordinator = new ToolExecutionBatchCoordinator(
                Map.of("barrier", false), 2);

        CompletableFuture<ToolExecutionBatchCoordinator.Permit> second = CompletableFuture.supplyAsync(
                () -> acquireUnchecked(coordinator, request("second-barrier", config, state)));
        Thread.sleep(50);
        assertThat(second).isNotDone();

        ToolExecutionBatchCoordinator.Permit first = coordinator.acquire(
                request("first-barrier", config, state));
        assertThat(second).isNotDone();
        first.close();
        second.get(1, TimeUnit.SECONDS).close();
    }

    private static OverAllState state(List<AssistantMessage.ToolCall> calls) {
        return new OverAllState(Map.of(
                "messages", List.of(AssistantMessage.builder().toolCalls(calls).build())));
    }

    private static ToolCallRequest request(String id, RunnableConfig config, OverAllState state) {
        return new ToolCallRequest("tool", "{}", id, Map.of(),
                new ToolCallExecutionContext(config, state));
    }

    private static ToolExecutionBatchCoordinator.Permit acquireUnchecked(
            ToolExecutionBatchCoordinator coordinator, ToolCallRequest request
    ) {
        try {
            return coordinator.acquire(request);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
