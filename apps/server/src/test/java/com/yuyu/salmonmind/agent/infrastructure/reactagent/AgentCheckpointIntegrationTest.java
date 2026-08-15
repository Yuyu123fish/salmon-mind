package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentExecutionException.AgentErrorCode;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 聚焦的 RedisSaver / ReactAgent 集成测试：验证 Checkpoint 叶子标记的复用、
 * 释放后重建、线程隔离与 Redis 不可用映射。使用测试侧确定性 ChatModelProvider，
 * 同时覆盖 agent -> model::chat seam，不调用外部模型。
 */
@Testcontainers
class AgentCheckpointIntegrationTest {

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
    void reusesCheckpointAndKeepsContextPerThread() {
        var model = new RecordingChatModel("pong");
        var adapter = newAdapter(model);

        // 第一轮：无 Checkpoint 标记，完整消息直接发送
        var first = completeSync(adapter, new AgentRequest(
                "thread-a", null, UUID.randomUUID(), userList("你好")));
        assertThat(first.text()).isEqualTo("pong");
        assertThat(first.provider()).isEqualTo("test-provider");
        assertThat(first.model()).isEqualTo("test-model");
        assertThat(visible(model.calls.get(0))).containsExactly(user("你好"));
        var firstThreadId = activeThreadId("thread-a");

        // 第二轮：标记等于用户 Entry 的 parentId，复用 Checkpoint，模型仍看到完整上下文
        var second = completeSync(adapter, new AgentRequest(
                "thread-a",
                firstLeafOf(),
                UUID.randomUUID(),
                messages("你好", "pong", "再讲一遍")));
        assertThat(second.text()).isEqualTo("pong");
        assertThat(visible(model.calls.get(1))).containsExactly(
                user("你好"), assistant("pong"), user("再讲一遍"));
        // 复用路径不释放 Checkpoint：RedisSaver 内部 thread 身份保持不变
        assertThat(activeThreadId("thread-a")).isEqualTo(firstThreadId);

        // 另一个线程互不影响
        var other = completeSync(adapter, new AgentRequest(
                "thread-b", null, UUID.randomUUID(), userList("另一个话题")));
        assertThat(other.text()).isEqualTo("pong");
        assertThat(visible(model.calls.get(2))).containsExactly(user("另一个话题"));
    }

    @Test
    void rebuildsFromFullProjectionWhenLeafMarkerMismatches() {
        var model = new RecordingChatModel("pong");
        var adapter = newAdapter(model);

        completeSync(adapter, new AgentRequest("thread-a", null, UUID.randomUUID(), userList("你好")));
        var releasedThreadId = activeThreadId("thread-a");

        // 期望叶子与实际标记不一致：先释放旧 Checkpoint，再以完整投影重建，且不重复消息
        var rebuilt = completeSync(adapter, new AgentRequest(
                "thread-a",
                UUID.randomUUID(),
                UUID.randomUUID(),
                messages("你好", "pong", "换个问题")));
        assertThat(rebuilt.text()).isEqualTo("pong");
        assertThat(visible(model.calls.get(1))).containsExactly(
                user("你好"), assistant("pong"), user("换个问题"));
        // 重建路径释放旧 Checkpoint：RedisSaver 内部 thread 身份已更换
        assertThat(activeThreadId("thread-a")).isNotEqualTo(releasedThreadId);
    }

    @Test
    void mapsRedisUnavailableToStableError() throws Exception {
        var closedPort = unusedLocalPort();
        var adapter = newAdapter(redisUrl.replaceAll(":\\d+$", ":" + closedPort), new RecordingChatModel("pong"));

        assertThatThrownBy(() -> completeSync(adapter,
                new AgentRequest("thread-a", null, UUID.randomUUID(), userList("你好"))))
                .isInstanceOfSatisfying(AgentExecutionException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AgentErrorCode.REDIS_UNAVAILABLE));
    }

    // 通过流式合同同步完成一次调用；失败时抛出原异常（与旧同步 complete 语义一致）
    private static AgentResult completeSync(ReactAgentSessionAdapter adapter, AgentRequest request) {
        AgentResult[] result = new AgentResult[1];
        adapter.stream(request, new AgentStreamListener() {
            @Override
            public void onDelta(String delta) {
            }

            @Override
            public void onComplete(AgentResult complete) {
                result[0] = complete;
            }

            @Override
            public void onError(AgentExecutionException error) {
                throw error;
            }
        });
        if (result[0] == null) {
            throw new AssertionError("流式调用未完成");
        }
        return result[0];
    }

    private ReactAgentSessionAdapter newAdapter(RecordingChatModel model) {
        return newAdapter(redisUrl, model);
    }

    private ReactAgentSessionAdapter newAdapter(String url, RecordingChatModel model) {
        var adapter = new ReactAgentSessionAdapter(
                () -> new ChatModelHandle(model, "test-provider", "test-model"), url, "",
                65_432, 32_768, 0.1);
        adapters.add(adapter);
        return adapter;
    }

    private static UUID firstLeafOf() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient client = Redisson.create(config);
        try {
            return UUID.fromString(client.<String>getBucket("salmon:agent:checkpoint-leaf:thread-a").get());
        } finally {
            client.shutdown();
        }
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

    private static int unusedLocalPort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static List<AgentMessage> userList(String text) {
        return List.of(userMessage(text));
    }

    // 系统提示由框架注入，断言时只比较模型可见的 user / assistant 消息
    private static List<Message> visible(List<Message> messages) {
        return messages.stream()
                .filter(message -> message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT)
                .toList();
    }

    private static AgentMessage userMessage(String text) {
        return new AgentMessage(AgentMessage.Role.USER, text);
    }

    private static AgentMessage assistantMessage(String text) {
        return new AgentMessage(AgentMessage.Role.ASSISTANT, text);
    }

    private static List<AgentMessage> messages(String... texts) {
        List<AgentMessage> result = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            result.add(i % 2 == 0 ? userMessage(texts[i]) : assistantMessage(texts[i]));
        }
        return result;
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
    }
}
