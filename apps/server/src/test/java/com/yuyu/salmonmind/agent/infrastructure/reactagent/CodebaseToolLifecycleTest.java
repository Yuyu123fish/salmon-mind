package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
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
                .addMetadata(ToolLifecycleInterceptor.CODEBASE_RESULT_BUDGET_METADATA_KEY,
                        new ToolLifecycleInterceptor.ToolResultBudget(32_768))
                .addMetadata(ToolLifecycleInterceptor.RESULT_BUDGET_METADATA_KEY,
                        new ToolLifecycleInterceptor.ToolResultBudget(32_768))
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

    private static ToolCallRequest request(String id, String name, RunnableConfig config) {
        return new ToolCallRequest(name, "{}", id, Map.of(),
                new ToolCallExecutionContext(config, new OverAllState()));
    }
}