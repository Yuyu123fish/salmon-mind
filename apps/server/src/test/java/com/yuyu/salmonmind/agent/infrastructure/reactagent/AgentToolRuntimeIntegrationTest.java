package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentToolCompleted;
import com.yuyu.salmonmind.agent.api.AgentToolFailed;
import com.yuyu.salmonmind.agent.api.AgentToolStarted;
import com.yuyu.salmonmind.agent.api.CheckpointPolicy;
import com.yuyu.salmonmind.model.chat.ChatModelHandle;
import com.yuyu.salmonmind.model.chat.ChatModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Feature 003 S1-01 Tool Runtime 硬 Gate：在锁定版本（Spring AI 1.1.2 /
 * Spring AI Alibaba 1.1.2.2 / Redisson 3.22.0）上，用真实 ReactAgent + RedisSaver +
 * 确定性 ChatModel 与测试专用只读 ToolCallback 证明：
 *
 * <ol>
 *   <li>ToolCallback 被真实执行，tool result 回到下一次模型调用；</li>
 *   <li>工具生命周期由平台 ToolLifecycleInterceptor 映射为 started/completed/failed，
 *       每个 Tool Call 至多一个终态，随后恰好一次 Agent 终态；</li>
 *   <li>工具异常只产生一次 failed 观察并收束为单终态；</li>
 *   <li>超长工具结果在进入模型上下文前被有界截断；</li>
 *   <li>REBUILD_FROM_PROJECTION 释放旧 Checkpoint 后只看到显式 JSONL 投影，
 *       不携带上一轮工具消息；REUSE_IF_MATCH 默认路径不受影响。</li>
 * </ol>
 *
 * <p>不调用真实模型、不绕过 ReactAgent/ToolNode，不注册任何生产业务工具。
 * 工具与结果上限只经包内构造 seam 注入，生产 Spring Bean 没有任何 ToolCallback。
 */
@Testcontainers
class AgentToolRuntimeIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static String redisUrl;

    private final List<ReactAgentSessionAdapter> adapters = new ArrayList<>();

    @BeforeAll
    static void redisUrl() {
        redisUrl = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    @AfterEach
    void closeAdapters() {
        adapters.forEach(ReactAgentSessionAdapter::close);
        adapters.clear();
    }

    @Test
    void executesRealToolLoopAndEmitsLifecycleEvents() {
        var tool = new RecordingSearchTool();
        var model = new ToolCallingChatModel();
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "loop-thread", null, UUID.randomUUID(),
                userList("请帮我搜索 salmon"), CheckpointPolicy.REUSE_IF_MATCH));

        // 最终回答非空，最终 usage 可取得
        assertThat(result.text()).isEqualTo(ToolCallingChatModel.FINAL_ANSWER);
        assertThat(result.usage().totalTokens()).isEqualTo(49);

        // 1. ToolCallback 被真实执行一次，参数来自模型 tool call
        assertThat(tool.calls).hasSize(1);
        assertThat(tool.calls.get(0)).contains("salmon");

        // 2. 第二次模型调用包含同一 Tool Call ID 对应的 tool result
        ToolResponseMessage toolResult = toolResponseOf(model.calls.get(1));
        assertThat(toolResult.getResponses().get(0).id()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
        assertThat(toolResult.getResponses().get(0).name()).isEqualTo(ToolCallingChatModel.TOOL_NAME);
        assertThat(toolResult.getResponses().get(0).responseData()).contains("SalmonMind");

        // 3. started → completed 顺序唯一，随后一次 complete；无 failed
        assertThat(events.started).hasSize(1);
        assertThat(events.started.get(0).toolCallId()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
        assertThat(events.started.get(0).toolName()).isEqualTo(ToolCallingChatModel.TOOL_NAME);
        assertThat(events.started.get(0).safeQuerySummary()).contains("salmon");
        assertThat(events.completed).hasSize(1);
        assertThat(events.completed.get(0).toolCallId()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
        assertThat(events.completed.get(0).toolName()).isEqualTo(ToolCallingChatModel.TOOL_NAME);
        assertThat(events.completed.get(0).durationMillis()).isGreaterThanOrEqualTo(0);
        assertThat(events.failed).isEmpty();
        // 恰好一次 Agent 终态：complete，无 error
        assertThat(events.result()).isNotNull();
        assertThat(events.error()).isNull();
        // delta 拼接即最终回答
        assertThat(String.join("", events.deltas)).isEqualTo(ToolCallingChatModel.FINAL_ANSWER);
    }

    @Test
    void toolExceptionProducesSingleFailedAndSingleTerminal() {
        var tool = new RecordingSearchTool(true);
        var model = new ToolCallingChatModel();
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "fail-thread", null, UUID.randomUUID(),
                userList("搜索一个会失败的话题"), CheckpointPolicy.REUSE_IF_MATCH));

        // 4. 工具抛异常：一次 failed，无 completed，且只有一次 Agent 终态（complete）
        assertThat(events.failed).hasSize(1);
        assertThat(events.failed.get(0).toolCallId()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
        assertThat(events.failed.get(0).toolName()).isEqualTo(ToolCallingChatModel.TOOL_NAME);
        assertThat(events.failed.get(0).stableErrorCode()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(events.failed.get(0).safeMessage()).contains("搜索服务不可用");
        assertThat(events.completed).isEmpty();
        assertThat(events.result()).isNotNull();
        assertThat(events.error()).isNull();
        assertThat(result.text()).isEqualTo(ToolCallingChatModel.FINAL_ANSWER);
        // 错误结果仍以 ToolResponseMessage 形式回到模型，框架不中断循环
        assertThat(toolResponseOf(model.calls.get(1)).getResponses().get(0).id())
                .isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
    }

    @Test
    void boundsOverlongToolResultBeforeNextModelCall() {
        var tool = new RecordingSearchTool(false, "x".repeat(5000));
        var model = new ToolCallingChatModel();
        // 结果上限 100 字符：证明存在进入模型上下文前的有界控制点
        var adapter = newAdapter(model, List.of(tool), 100);
        var events = new RecordingListener();

        completeSync(adapter, events, new AgentRequest(
                "bound-thread", null, UUID.randomUUID(),
                userList("返回超长结果"), CheckpointPolicy.REUSE_IF_MATCH));

        // 5. 超长结果在进入模型前被截断到上限；生命周期仍以 completed 收尾
        String responseData = toolResponseOf(model.calls.get(1)).getResponses().get(0).responseData();
        assertThat(responseData).hasSize(100);
        assertThat(events.completed).hasSize(1);
        assertThat(events.failed).isEmpty();
    }

    @Test
    void rebuildFromProjectionDoesNotCarryPreviousToolMessages() {
        var tool = new RecordingSearchTool();
        var model = new ToolCallingChatModel();
        var adapter = newAdapter(model, List.of(tool), 200_000);

        // 第一轮：含一次工具执行，RedisSaver 在循环中保存 assistant(tool call) 与 tool result
        completeSync(adapter, new RecordingListener(), new AgentRequest(
                "rebuild-thread", null, UUID.randomUUID(),
                userList("第一轮：搜索 salmon")));
        String round1ThreadId = activeThreadId("rebuild-thread");
        assertThat(round1ThreadId).isNotBlank();

        UUID round2AnswerLeafId = UUID.randomUUID();
        // 第二轮：强制从 JSONL 投影重建，不得看到上一轮原始 tool call/result
        completeSync(adapter, new RecordingListener(), new AgentRequest(
                "rebuild-thread", null, round2AnswerLeafId,
                userList("第二轮：换个问题"), CheckpointPolicy.REBUILD_FROM_PROJECTION));

        List<Message> firstCallOfRound2 = model.calls.get(2);
        assertThat(visible(firstCallOfRound2)).containsExactly(user("第二轮：换个问题"));
        assertThat(firstCallOfRound2).noneMatch(ToolResponseMessage.class::isInstance);
        // 重建释放了旧 Checkpoint：RedisSaver 内部线程身份已更换
        assertThat(activeThreadId("rebuild-thread")).isNotEqualTo(round1ThreadId);

        // 6. 既有 REUSE_IF_MATCH 路径不受影响：标记一致时复用，不释放 Checkpoint，
        //    且证明 RedisSaver 确实保存了工具轮消息（第三轮模型能看到它们）
        String round2ThreadId = activeThreadId("rebuild-thread");
        completeSync(adapter, new RecordingListener(), new AgentRequest(
                "rebuild-thread", round2AnswerLeafId, UUID.randomUUID(),
                userList("第三轮：继续"), CheckpointPolicy.REUSE_IF_MATCH));
        assertThat(activeThreadId("rebuild-thread")).isEqualTo(round2ThreadId);
        List<Message> reusedCall = model.calls.get(4);
        assertThat(visible(reusedCall)).contains(user("第三轮：继续"));
        assertThat(reusedCall).anyMatch(ToolResponseMessage.class::isInstance);
    }

    private ReactAgentSessionAdapter newAdapter(ToolCallingChatModel model, List<ToolCallback> tools, int maxResultChars) {
        var adapter = new ReactAgentSessionAdapter(
                (ChatModelProvider) () -> new ChatModelHandle(model, "test-provider", "test-model"),
                redisUrl, "", 65_432, 32_768, 0.1, maxResultChars, tools);
        adapters.add(adapter);
        return adapter;
    }

    private static AgentResult completeSync(ReactAgentSessionAdapter adapter, RecordingListener events, AgentRequest request) {
        adapter.stream(request, events);
        if (events.error() != null) {
            throw events.error();
        }
        if (events.result() == null) {
            throw new AssertionError("流式调用未完成");
        }
        return events.result();
    }

    // RedisSaver 内部的实际线程身份；复用路径保持不变，release 后重建会更换
    private static String activeThreadId(String threadId) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient client = Redisson.create(config);
        try {
            return (String) client.getMap("graph:thread:meta:" + threadId).get("thread_id");
        } finally {
            client.shutdown();
        }
    }

    private static ToolResponseMessage toolResponseOf(List<Message> messages) {
        return messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("模型调用中缺少 tool result 消息"));
    }

    // 系统提示由框架注入，断言时只比较模型可见的 user / assistant 消息
    private static List<Message> visible(List<Message> messages) {
        return messages.stream()
                .filter(message -> message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT)
                .toList();
    }

    private static List<AgentMessage> userList(String text) {
        return List.of(new AgentMessage(AgentMessage.Role.USER, text));
    }

    private static Message user(String text) {
        return UserMessage.builder().text(text).build();
    }

    /** 记录工具生命周期事件与模型终态；失败抛出由调用方决定。 */
    private static final class RecordingListener implements AgentStreamListener {

        private final List<String> deltas = new CopyOnWriteArrayList<>();
        private final List<AgentToolStarted> started = new CopyOnWriteArrayList<>();
        private final List<AgentToolCompleted> completed = new CopyOnWriteArrayList<>();
        private final List<AgentToolFailed> failed = new CopyOnWriteArrayList<>();
        private volatile AgentResult result;
        private volatile AgentExecutionException error;

        @Override
        public void onDelta(String delta) {
            deltas.add(delta);
        }

        @Override
        public void onComplete(AgentResult complete) {
            result = complete;
        }

        @Override
        public void onError(AgentExecutionException err) {
            error = err;
        }

        @Override
        public void onToolStarted(AgentToolStarted event) {
            started.add(event);
        }

        @Override
        public void onToolCompleted(AgentToolCompleted event) {
            completed.add(event);
        }

        @Override
        public void onToolFailed(AgentToolFailed event) {
            failed.add(event);
        }

        AgentResult result() {
            return result;
        }

        AgentExecutionException error() {
            return error;
        }
    }

    /**
     * 测试专用只读搜索工具：记录每次调用收到的原始参数；
     * 可通过构造开关抛出异常或返回超长结果。
     */
    private static final class RecordingSearchTool implements ToolCallback {

        static final String NAME = "search_test_tool";

        private final List<String> calls = new CopyOnWriteArrayList<>();

        private final boolean throwOnCall;

        private final String resultText;

        RecordingSearchTool() {
            this(false, "检索命中：SalmonMind 支持本地文档问答，混合召回与引用。");
        }

        RecordingSearchTool(boolean throwOnCall) {
            this(throwOnCall, null);
        }

        RecordingSearchTool(boolean throwOnCall, String resultText) {
            this.throwOnCall = throwOnCall;
            this.resultText = resultText;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(NAME)
                    .description("只读测试搜索工具：按查询返回固定短结果")
                    .inputSchema("""
                            {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            calls.add(toolInput);
            if (throwOnCall) {
                throw new IllegalStateException("搜索服务不可用：gate 测试注入异常");
            }
            return resultText;
        }
    }

    /**
     * 确定性 ChatModel：首次未看到本 Call ID 的 tool result 时返回带稳定 Tool Call ID
     * 的工具调用，看到后才返回最终回答与确定性 usage。记录每次收到的模型可见消息。
     */
    static final class ToolCallingChatModel implements ChatModel {

        static final String TOOL_CALL_ID = "call-001";

        static final String TOOL_NAME = RecordingSearchTool.NAME;

        static final String FINAL_ANSWER = "根据检索结果，SalmonMind 支持本地文档问答。";

        private final List<List<Message>> calls = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = new ArrayList<>(prompt.getInstructions());
            calls.add(instructions);
            boolean sawToolResult = instructions.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .anyMatch(response -> TOOL_CALL_ID.equals(response.id()));
            if (!sawToolResult) {
                var toolCallMessage = AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                TOOL_CALL_ID, "function", TOOL_NAME, "{\"query\":\"salmon\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }
            var answer = AssistantMessage.builder().content(FINAL_ANSWER).build();
            var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(42, 7, 49)).build();
            return new ChatResponse(List.of(new Generation(answer)), metadata);
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            // Gate 发现：框架要求 ChatModel 默认选项与 Agent chatOptions 同类型，
            // 否则 build 时 Jackson merge 对 DefaultChatOptions（无 @JsonProperty）抛错
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            // 锁定框架的 LLM 节点经 ChatClient 走 stream 通道，确定性模型以单响应 Flux 实现
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }
}
