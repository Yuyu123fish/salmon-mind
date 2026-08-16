package com.yuyu.salmonmind.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentUsage;
import com.yuyu.salmonmind.conversation.api.ConversationService;
import com.yuyu.salmonmind.conversation.api.ConversationSummary;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.api.RunStreamListener;
import com.yuyu.salmonmind.model.chat.ChatModelHandle;
import com.yuyu.salmonmind.model.chat.ChatModelProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Conversation + 真实 ReactAgent/RedisSaver 的跨模块恢复测试：通过 conversation 公开 seam
 * （{@link ConversationService}）走生产 Agent 链路，只把 ChatModel 替换为确定性实现。
 * 验证两轮 Checkpoint 复用、删除 Checkpoint 后从 JSONL 重建、错误叶子标记后重建
 * 与 Conversation 隔离；不调用外部模型。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test"
        }
)
@Testcontainers
class ConversationRedisRecoveryIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static final Path DATA_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "salmon-mind-conv-redis-test-" + UUID.randomUUID());

    /** 确定性 ChatModel 单例；每个测试方法前重置调用记录。 */
    private static final RecordingChatModel MODEL = new RecordingChatModel("pong");

    @Autowired
    private ConversationService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("salmon.conversation.data-dir", () -> DATA_DIR.toString());
        registry.add("salmon.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @TestConfiguration
    static class DeterministicChatModelConfig {

        @Bean
        @Primary
        ChatModelProvider chatModelProvider() {
            return () -> new ChatModelHandle(MODEL, "test-provider", "test-model");
        }
    }

    @BeforeAll
    static void createDataDir() throws IOException {
        Files.createDirectories(DATA_DIR);
    }

    @AfterAll
    static void cleanupDataDir() throws IOException {
        if (Files.exists(DATA_DIR)) {
            try (var paths = Files.walk(DATA_DIR)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
    }

    @BeforeEach
    void resetModel() {
        MODEL.calls.clear();
    }

    @Test
    void reusesCheckpointAcrossTwoRoundsAndAdvancesMarker() {
        ConversationSummary conversation = service.create();

        // 第一轮：无 Checkpoint 标记，模型只看到首条用户消息
        Run first = send(conversation.id(), "你好");
        assertThat(first.status()).isEqualTo(Run.RunStatus.SUCCEEDED);
        assertThat(visible(mainCalls().get(0))).containsExactly(user("你好"));
        // 模型成功后将标记推进到预分配的回答叶子
        assertThat(markerOf(conversation)).isNotBlank();

        String threadIdAfterFirstRound = activeThreadId(conversation);

        // 第二轮：标记与期望叶子一致，复用 Checkpoint，只发送最新用户消息
        Run second = send(conversation.id(), "再讲一遍");
        assertThat(second.status()).isEqualTo(Run.RunStatus.SUCCEEDED);
        // 复用路径下模型仍看到完整上下文：Checkpoint 恢复了第一轮历史
        assertThat(visible(mainCalls().get(1))).containsExactly(
                user("你好"), assistant("pong"), user("再讲一遍"));
        // 复用路径不释放 Checkpoint：RedisSaver 内部 thread 身份保持不变
        assertThat(activeThreadId(conversation)).isEqualTo(threadIdAfterFirstRound);
        assertThat(markerOf(conversation)).isNotBlank();
    }

    @Test
    void rebuildsFromJsonlWhenCheckpointDeleted() {
        ConversationSummary conversation = service.create();
        send(conversation.id(), "你好");
        send(conversation.id(), "再讲一遍");
        String threadIdBefore = activeThreadId(conversation);

        // 删除 Checkpoint 标记：下一次调用无法复用，必须从 JSONL Active Path 重建
        deleteMarker(conversation);

        Run third = send(conversation.id(), "换个问题");
        assertThat(third.status()).isEqualTo(Run.RunStatus.SUCCEEDED);
        // 重建路径：模型看到完整 JSONL 投影（全部两轮 + 新消息），不静默失忆
        assertThat(visible(mainCalls().get(2))).containsExactly(
                user("你好"), assistant("pong"),
                user("再讲一遍"), assistant("pong"),
                user("换个问题"));
        // 重建路径先释放旧 Checkpoint：内部 thread 身份更换
        assertThat(activeThreadId(conversation)).isNotEqualTo(threadIdBefore);
        assertThat(markerOf(conversation)).isNotBlank();
    }

    @Test
    void rebuildsWhenLeafMarkerPointsToUnknownEntry() {
        ConversationSummary conversation = service.create();
        send(conversation.id(), "你好");
        String threadIdBefore = activeThreadId(conversation);

        // 错误叶子标记：指向不存在的 Entry，与 JSONL 不一致，必须释放后重建
        overwriteMarker(conversation, UUID.randomUUID().toString());

        Run second = send(conversation.id(), "换个问题");
        assertThat(second.status()).isEqualTo(Run.RunStatus.SUCCEEDED);
        assertThat(visible(mainCalls().get(1))).containsExactly(
                user("你好"), assistant("pong"), user("换个问题"));
        assertThat(activeThreadId(conversation)).isNotEqualTo(threadIdBefore);
        assertThat(markerOf(conversation)).isNotBlank();
    }

    @Test
    void isolatesCheckpointsBetweenConversations() {
        ConversationSummary first = service.create();
        ConversationSummary second = service.create();

        send(first.id(), "A 的问题");
        send(second.id(), "B 的问题");

        // 两个 Conversation 各自独立：模型调用互不混杂
        assertThat(visible(mainCalls().get(0))).containsExactly(user("A 的问题"));
        assertThat(visible(mainCalls().get(1))).containsExactly(user("B 的问题"));
        // thread 身份按 Conversation 隔离
        assertThat(activeThreadId(first)).isNotEqualTo(activeThreadId(second));

        // 删除 A 的 Checkpoint 不影响 B：B 下一次仍复用
        deleteMarker(first);
        String secondThreadBefore = activeThreadId(second);
        send(second.id(), "B 再问");
        assertThat(visible(mainCalls().get(2))).containsExactly(
                user("B 的问题"), assistant("pong"), user("B 再问"));
        assertThat(activeThreadId(second)).isEqualTo(secondThreadBefore);
    }

    // 过滤掉标题/摘要等轻量调用，只保留主回答调用（它们都经过同一确定性 ChatModel）
    private static List<List<Message>> mainCalls() {
        return MODEL.calls.stream()
                .filter(call -> call.stream().noneMatch(m -> m.getText() != null
                        && (m.getText().contains("对话标题") || m.getText().contains("压缩器"))))
                .toList();
    }

    // 同步发送并收集终态 Run；失败时以断言错误失败
    private Run send(UUID conversationId, String text) {
        CollectingListener listener = new CollectingListener();
        service.send(conversationId, text, listener);
        if (listener.failed != null) {
            throw new AssertionError("Run 失败: " + listener.failed.errorCode() + " " + listener.failed.message());
        }
        return listener.completed.run();
    }

    /** 收集 Run 终态的最小监听器。 */
    private static final class CollectingListener implements RunStreamListener {
        private RunStreamListener.RunCompleted completed;
        private RunStreamListener.RunFailed failed;

        @Override
        public void onRunStarted(RunStarted event) {
        }

        @Override
        public void onCompactionCompleted(CompactionCompleted event) {
        }

        @Override
        public void onAssistantDelta(AssistantDelta event) {
        }

        @Override
        public void onAssistantCompleted(AssistantCompleted event) {
        }

        @Override
        public void onTitleUpdated(TitleUpdated event) {
        }

        @Override
        public void onRunCompleted(RunCompleted event) {
            completed = event;
        }

        @Override
        public void onRunFailed(RunFailed event) {
            failed = event;
        }
    }

    // ---------- Redis 辅助 ----------

    private static String markerOf(ConversationSummary conversation) {
        return withClient(client -> client.<String>getBucket(
                CHECKPOINT_LEAF_KEY_PREFIX + conversation.id()).get());
    }

    private static void deleteMarker(ConversationSummary conversation) {
        withClient(client -> {
            client.getBucket(CHECKPOINT_LEAF_KEY_PREFIX + conversation.id()).delete();
            return null;
        });
    }

    private static void overwriteMarker(ConversationSummary conversation, String value) {
        withClient(client -> {
            client.getBucket(CHECKPOINT_LEAF_KEY_PREFIX + conversation.id()).set(value);
            return null;
        });
    }

    // RedisSaver 内部的实际线程身份；复用路径保持不变，release 后重建会更换
    private static String activeThreadId(ConversationSummary conversation) {
        return withClient(client -> (String) client.getMap(
                "graph:thread:meta:" + conversation.id()).get("thread_id"));
    }

    // 与 ReactAgentSessionAdapter 的固定前缀保持一致；测试需直接操作 Redis 标记
    private static final String CHECKPOINT_LEAF_KEY_PREFIX = "salmon:agent:checkpoint-leaf:";

    private interface RedisAction<T> {
        T run(RedissonClient client);
    }

    private static <T> T withClient(RedisAction<T> action) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        RedissonClient client = Redisson.create(config);
        try {
            return action.run(client);
        } finally {
            client.shutdown();
        }
    }

    // ---------- 消息断言辅助 ----------

    // 系统提示由框架注入，断言时只比较模型可见的 user / assistant 消息
    private static List<Message> visible(List<Message> messages) {
        return messages.stream()
                .filter(message -> message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT)
                .toList();
    }

    private static Message user(String text) {
        return UserMessage.builder().text(text).build();
    }

    private static Message assistant(String text) {
        return AssistantMessage.builder().content(text).build();
    }

    /** 确定性 ChatModel：记录每次收到的模型可见消息，返回固定回答。 */
    static class RecordingChatModel implements ChatModel {

        final List<List<Message>> calls = new CopyOnWriteArrayList<>();

        private final String answer;

        RecordingChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.add(new ArrayList<>(prompt.getInstructions()));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            // 锁定框架要求 ChatModel 默认选项与 Agent chatOptions 同类型，
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
