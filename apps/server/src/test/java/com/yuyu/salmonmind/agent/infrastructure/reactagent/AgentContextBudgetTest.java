package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/** Agent 静态 system/tool schema 预算的确定性测试。 */
class AgentContextBudgetTest {

    @Test
    void staticBudgetChangesWhenRegisteredToolDefinitionChanges() {
        ToolCallback local = tool("search_local_knowledge", "本地资料");
        ToolCallback bocha = tool("search_web_bocha", "博查网页");
        ToolCallback searchApi = tool("search_web_searchapi", "SearchApi 网页");

        long one = ReactAgentSessionAdapter.estimateStaticInputTokens("system", List.of(local));
        long three = ReactAgentSessionAdapter.estimateStaticInputTokens(
                "system", List.of(local, bocha, searchApi));

        assertThat(three).isGreaterThan(one);
        assertThat(ReactAgentSessionAdapter.estimateStaticInputTokens(
                "system", List.of(local, bocha))).isLessThan(three);
    }

    private static ToolCallback tool(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(description)
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }
}
