package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 代码库运行时 Gate：验证禁读、独立调用预算和专用字符边界。 */
class CodebaseToolLifecycleTest {

    @Test
    void blocksCodebaseHandlerWhenUserForbidsLocalCode() {
        AtomicInteger calls = new AtomicInteger();
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(ToolLifecycleInterceptor.CODEBASE_ACCESS_ALLOWED_METADATA_KEY, false)
                .build();
        ToolLifecycleInterceptor interceptor = new ToolLifecycleInterceptor(
                200_000, 65_536, new ObjectMapper());

        ToolCallResponse response = interceptor.interceptToolCall(
                request("blocked", "select_local_repository", config), request -> {
                    calls.incrementAndGet();
                    return ToolCallResponse.of("blocked", "select_local_repository", "should-not-run");
                });

        assertThat(response.isError()).isTrue();
        assertThat(response.getResult()).contains("CODEBASE_ACCESS_DISABLED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void keepsCodebaseCallAndCharacterBudgetsSeparateFromExistingTools() {
        AtomicInteger calls = new AtomicInteger();
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(ToolLifecycleInterceptor.CODEBASE_ACCESS_ALLOWED_METADATA_KEY, true)
                .addMetadata(ToolLifecycleInterceptor.CODEBASE_INVOCATION_BUDGET_METADATA_KEY,
                        new ToolLifecycleInterceptor.InvocationBudget(1))
                .addMetadata(ToolLifecycleInterceptor.INVOCATION_BUDGET_METADATA_KEY,
                        new ToolLifecycleInterceptor.InvocationBudget(1))
                .build();
        ToolLifecycleInterceptor interceptor = new ToolLifecycleInterceptor(
                200_000, 20, new ObjectMapper());

        var handler = (com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler) request -> {
            calls.incrementAndGet();
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), "x".repeat(100));
        };
        ToolCallResponse firstCodebase = interceptor.interceptToolCall(
                request("codebase-1", "read_repository_file", config), handler);
        ToolCallResponse secondCodebase = interceptor.interceptToolCall(
                request("codebase-2", "read_repository_file", config), handler);
        ToolCallResponse existingTool = interceptor.interceptToolCall(
                request("local-1", "search_local_knowledge", config), handler);

        assertThat(firstCodebase.isError()).isFalse();
        assertThat(firstCodebase.getResult()).hasSize(20);
        assertThat(secondCodebase.getResult()).contains("TOOL_CALL_BUDGET_EXCEEDED");
        assertThat(existingTool.isError()).isFalse();
        assertThat(existingTool.getResult()).hasSize(100);
        assertThat(calls).hasValue(2);
    }

    @Test
    void reservesReadGitAndStageCapacityAfterDiscoveryFence() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(ToolLifecycleInterceptor.CODEBASE_ACCESS_ALLOWED_METADATA_KEY, true)
                .addMetadata(ToolLifecycleInterceptor.CODEBASE_INVOCATION_BUDGET_METADATA_KEY,
                        new CodebaseBudget(16))
                .build();
        ToolLifecycleInterceptor interceptor = new ToolLifecycleInterceptor(
                200_000, 65_536, new ObjectMapper());
        var handler = (com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler) request -> {
            calls.incrementAndGet();
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                    "{\"status\":\"SUCCESS\",\"reason\":\"COMPLETE\","
                            + "\"sourceKind\":\"CODEBASE\",\"provider\":\"CODEBASE\","
                            + "\"operation\":\"" + request.getToolName() + "\",\"items\":[]}");
        };

        ToolCallResponse tenth = null;
        for (int index = 0; index < 10; index++) {
            tenth = interceptor.interceptToolCall(
                    request("discover-" + index, "list_repository_directory", config), handler);
        }
        JsonNode tenthResult = new ObjectMapper().readTree(tenth.getResult());
        assertThat(tenthResult.path("budget").path("remainingEvidenceCalls").asInt()).isEqualTo(6);
        assertThat(tenthResult.path("budget").path("discoveryAllowed").asBoolean()).isFalse();
        assertThat(tenthResult.path("budget").path("stageAvailable").asBoolean()).isTrue();

        ToolCallResponse reserved = interceptor.interceptToolCall(
                request("discover-reserved", "grep_repository", config), handler);
        assertThat(reserved.getResult()).contains("CODEBASE_DISCOVERY_BUDGET_RESERVED");
        ToolCallResponse selectReserved = interceptor.interceptToolCall(
                request("select-reserved", "select_local_repository", config), handler);
        assertThat(selectReserved.getResult()).contains("CODEBASE_DISCOVERY_BUDGET_RESERVED");
        assertThat(calls).hasValue(10);

        for (int index = 0; index < 6; index++) {
            ToolCallResponse read = interceptor.interceptToolCall(
                    request("read-" + index, "read_repository_file", config), handler);
            assertThat(read.isError()).isFalse();
        }
        ToolCallResponse exhausted = interceptor.interceptToolCall(
                request("read-exhausted", "read_repository_file", config), handler);
        assertThat(exhausted.getResult()).contains("TOOL_CALL_BUDGET_EXCEEDED");

        ToolCallResponse staged = interceptor.interceptToolCall(
                request("stage-1", "stage_call_chain", config), handler);
        assertThat(staged.getResult()).contains("\"stageAvailable\":true");
        ToolCallResponse stageRejected = interceptor.interceptToolCall(
                request("stage-2", "stage_call_chain", config), handler);
        assertThat(stageRejected.getResult()).contains("TOOL_CALL_BUDGET_EXCEEDED");
    }

    private static ToolCallRequest request(String id, String name, RunnableConfig config) {
        return new ToolCallRequest(name, "{}", id, Map.of(),
                new ToolCallExecutionContext(config, new OverAllState()));
    }
}
