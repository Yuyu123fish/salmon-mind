package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentRunTraceItem;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentToolCompleted;
import com.yuyu.salmonmind.agent.api.AgentToolFailed;
import com.yuyu.salmonmind.agent.api.AgentToolStarted;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/**
 * Displayable Reasoning 运行时 Gate：只使用公开的 AssistantMessage metadata，
 * 验证锁定版本的真实 ReactAgent 流不会把 reasoning 混入最终回答或在工具轮次中丢失。
 */
class AgentReasoningRuntimeIntegrationTest {

    private static final String REASONING_KEY = "reasoningContent";

    @Test
    void preservesReasoningMetadataSeparatelyFromAnswer() throws Exception {
        var model = new ReasoningChatModel(false, true);

        List<AssistantMessage> chunks = streamingMessages(agent(model, List.of()).stream("请回答"));

        assertThat(chunks).singleElement().satisfies(message -> {
            assertThat(message.getMetadata()).containsEntry(REASONING_KEY, "先分析，再作答。");
            assertThat(message.getText()).isEqualTo("这是最终回答。");
            assertThat(message.getText()).doesNotContain("先分析");
        });
    }

    @Test
    void naturallyDegradesWhenReasoningMetadataIsAbsent() throws Exception {
        var model = new ReasoningChatModel(false, false);

        List<AssistantMessage> chunks = streamingMessages(agent(model, List.of()).stream("请回答"));

        assertThat(chunks).singleElement().satisfies(message -> {
            assertThat(message.getMetadata()).doesNotContainKey(REASONING_KEY);
            assertThat(message.getText()).isEqualTo("这是最终回答。");
        });
    }

    @Test
    void preservesReasoningAcrossToolRoundBeforeFinalAnswer() throws Exception {
        var model = new ReasoningChatModel(true, true);
        var tool = new FixedTool();

        List<AssistantMessage> chunks = streamingMessages(agent(model, List.of(tool)).stream("先查工具再回答"));

        assertThat(tool.calls).containsExactly("{\"query\":\"salmon\"}");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getMetadata()).containsEntry(REASONING_KEY, "需要先查询资料。");
        assertThat(chunks.get(0).getText()).isNullOrEmpty();
        assertThat(chunks.get(0).hasToolCalls()).isTrue();
        assertThat(chunks.get(1).getMetadata()).containsEntry(REASONING_KEY, "资料足够，可以作答。");
        assertThat(chunks.get(1).getText()).isEqualTo("这是最终回答。");
    }

    @Test
    void boundsAndOrdersDisplayTraceWithoutDroppingToolLifecycle() {
        var delegate = new RecordingTraceListener();
        var collector = new RunTraceCollector(delegate, 4, 8, 5);

        collector.onReasoningDelta("abc");
        collector.onReasoningDelta("def");
        collector.onToolStarted(new AgentToolStarted("call-1", "search-a", "query-a"));
        collector.onToolCompleted(new AgentToolCompleted(
                "call-1", "search-a", 4, "BOCHA", 2, false, false));
        collector.onReasoningDelta("ghijk");
        collector.onToolStarted(new AgentToolStarted("call-2", "search-b", "query-b"));
        collector.onToolFailed(new AgentToolFailed(
                "call-2", "search-b", 5, "TOOL_FAILED", "provider unavailable"));
        // item 上限后仍转发新工具生命周期，但持久化列表保持有界并标记截断。
        collector.onToolStarted(new AgentToolStarted("call-3", "search-c", "query-c"));

        assertThat(delegate.reasoning).containsExactly("abc", "def", "gh");
        assertThat(delegate.started).extracting(AgentToolStarted::toolCallId)
                .containsExactly("call-1", "call-2", "call-3");
        assertThat(collector.snapshot()).hasSize(4);
        assertThat(collector.snapshot()).satisfiesExactly(
                item -> assertThat(item).extracting(
                        AgentRunTraceItem::kind, AgentRunTraceItem::text, AgentRunTraceItem::truncated)
                        .containsExactly(AgentRunTraceItem.Kind.REASONING, "abcdef", false),
                item -> assertThat(item).extracting(
                        AgentRunTraceItem::kind, AgentRunTraceItem::toolCallId,
                        AgentRunTraceItem::toolStatus, AgentRunTraceItem::truncated)
                        .containsExactly(AgentRunTraceItem.Kind.TOOL, "call-1",
                                AgentRunTraceItem.ToolStatus.COMPLETED, true),
                item -> assertThat(item).extracting(
                        AgentRunTraceItem::kind, AgentRunTraceItem::text, AgentRunTraceItem::truncated)
                        .containsExactly(AgentRunTraceItem.Kind.REASONING, "gh", true),
                item -> assertThat(item).extracting(
                        AgentRunTraceItem::kind, AgentRunTraceItem::toolCallId,
                        AgentRunTraceItem::toolStatus, AgentRunTraceItem::truncated)
                        .containsExactly(AgentRunTraceItem.Kind.TOOL, "call-2",
                                AgentRunTraceItem.ToolStatus.FAILED, true));
    }

    private static ReactAgent agent(ChatModel model, List<ToolCallback> tools) {
        return ReactAgent.builder()
                .name("reasoning-runtime-gate")
                .model(model)
                .returnReasoningContents(true)
                .tools(tools)
                .build();
    }

    private static List<AssistantMessage> streamingMessages(Flux<NodeOutput> outputs) {
        return outputs
                .ofType(StreamingOutput.class)
                .filter(output -> output.getOutputType() == OutputType.AGENT_MODEL_STREAMING)
                .map(StreamingOutput::message)
                .ofType(AssistantMessage.class)
                .collectList()
                .block();
    }

    /** 确定性模型：可选择先发起工具调用，并在公开 metadata 中提供 reasoning。 */
    private static final class ReasoningChatModel implements ChatModel {

        private final boolean useTool;
        private final boolean includeReasoning;

        private ReasoningChatModel(boolean useTool, boolean includeReasoning) {
            this.useTool = useTool;
            this.includeReasoning = includeReasoning;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> messages = new ArrayList<>(prompt.getInstructions());
            boolean hasToolResult = messages.stream().anyMatch(ToolResponseMessage.class::isInstance);
            if (useTool && !hasToolResult) {
                AssistantMessage.Builder builder = AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "reasoning-call-1", "function", FixedTool.NAME,
                                "{\"query\":\"salmon\"}")));
                if (includeReasoning) {
                    builder.properties(Map.of(REASONING_KEY, "需要先查询资料。"));
                }
                return new ChatResponse(List.of(new Generation(builder.build())));
            }

            AssistantMessage.Builder builder = AssistantMessage.builder().content("这是最终回答。");
            if (includeReasoning) {
                builder.properties(Map.of(
                        REASONING_KEY,
                        useTool ? "资料足够，可以作答。" : "先分析，再作答。"));
            }
            return new ChatResponse(List.of(new Generation(builder.build())));
        }

        @Override
        public OpenAiChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    /** Gate 内部的无外部副作用工具，只记录真实 ReactAgent ToolNode 传入的参数。 */
    private static final class FixedTool implements ToolCallback {

        private static final String NAME = "reasoning_fixed_tool";

        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(NAME)
                    .description("返回固定资料")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls.add(toolInput);
            return "SalmonMind 资料";
        }
    }

    /** 仅记录收集器对外转发，终态不参与这个纯内存边界用例。 */
    private static final class RecordingTraceListener implements AgentStreamListener {

        private final List<String> reasoning = new CopyOnWriteArrayList<>();
        private final List<AgentToolStarted> started = new CopyOnWriteArrayList<>();

        @Override
        public void onReasoningDelta(String delta) {
            reasoning.add(delta);
        }

        @Override
        public void onDelta(String delta) {
        }

        @Override
        public void onComplete(AgentResult result) {
        }

        @Override
        public void onError(AgentExecutionException error) {
        }

        @Override
        public void onToolStarted(AgentToolStarted event) {
            started.add(event);
        }
    }
}
