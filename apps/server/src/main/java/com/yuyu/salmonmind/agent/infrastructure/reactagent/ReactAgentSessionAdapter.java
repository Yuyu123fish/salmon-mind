package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentExecutionException.AgentErrorCode;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentSession;
import com.yuyu.salmonmind.model.chat.ChatModelException;
import com.yuyu.salmonmind.model.chat.ChatModelHandle;
import com.yuyu.salmonmind.model.chat.ChatModelProvider;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 生产 Agent Adapter：封装 ReactAgent、RedisSaver、Redisson 与 Checkpoint 叶子标记。
 * ChatModel 通过 {@link ChatModelProvider} 获取，模型与 Redis 均延迟初始化，
 * 应用未配置时仍可启动，首次对话才报告配置错误。
 *
 * Checkpoint 一致性语义：Redis 标记必须等于当前 JSONL 活动叶子才能复用；
 * 模型成功后标记被推进到预分配的回答叶子，若此后 JSONL 追加 Assistant 前进程中断，
 * 下一次调用会发现标记与 JSONL 不一致并整体重建，不会采纳未落盘的幽灵回答。
 */
@Component
class ReactAgentSessionAdapter implements AgentSession {

    static final String CHECKPOINT_LEAF_KEY_PREFIX = "salmon:agent:checkpoint-leaf:";

    private final ChatModelProvider chatModelProvider;
    private final String redisUrl;
    private final String redisPassword;

    private volatile ChatModelHandle chatModelHandle;
    private volatile ReactAgent reactAgent;
    private volatile RedisSaver redisSaver;
    private volatile RedissonClient redissonClient;

    ReactAgentSessionAdapter(
            ChatModelProvider chatModelProvider,
            @Value("${salmon.redis.url:}") String redisUrl,
            @Value("${salmon.redis.password:}") String redisPassword
    ) {
        this.chatModelProvider = chatModelProvider;
        this.redisUrl = redisUrl;
        this.redisPassword = redisPassword;
    }

    @Override
    public AgentResult complete(AgentRequest request) {
        try {
            ChatModelHandle handle = handle();
            ReactAgent agent = reactAgent(handle);
            RedisSaver saver = saver();
            RunnableConfig config = RunnableConfig.builder().threadId(request.threadId()).build();

            AssistantMessage response;
            if (canReuseCheckpoint(request)) {
                // 标记与期望叶子一致：Checkpoint 已覆盖历史，只发送最新用户消息
                response = agent.call(List.of(toSpringMessage(lastUserMessage(request))), config);
            } else {
                // 标记缺失或不一致：释放旧 Checkpoint，用完整模型可见消息重建
                releaseCheckpoint(saver, config);
                response = agent.call(toSpringMessages(request.modelVisibleMessages()), config);
            }

            // 模型成功：更新 Checkpoint 叶子标记为预分配的回答 Entry，保证下一轮可复用
            writeCheckpointLeaf(request);
            return new AgentResult(response.getText(), handle.provider(), handle.modelName(), null);
        } catch (AgentExecutionException ex) {
            throw ex;
        } catch (ChatModelException ex) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_NOT_CONFIGURED, "Chat 模型未配置", ex);
        } catch (RedisException ex) {
            throw redisFailure("Redis 不可用", ex);
        } catch (Exception ex) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "模型调用失败", ex);
        }
    }

    // 标记缺失、指向不存在叶子或与期望不一致时都不能复用
    private boolean canReuseCheckpoint(AgentRequest request) {
        if (request.expectedCheckpointLeafId() == null) {
            return false;
        }
        String marker = readCheckpointLeaf(request.threadId());
        return request.expectedCheckpointLeafId().toString().equals(marker);
    }

    private String readCheckpointLeaf(String threadId) {
        try {
            RBucket<String> bucket = redissonClient().getBucket(CHECKPOINT_LEAF_KEY_PREFIX + threadId);
            return bucket.get();
        } catch (RedisException ex) {
            throw redisFailure("读取 Checkpoint 标记失败", ex);
        }
    }

    private void writeCheckpointLeaf(AgentRequest request) {
        try {
            RBucket<String> bucket = redissonClient().getBucket(CHECKPOINT_LEAF_KEY_PREFIX + request.threadId());
            bucket.set(request.answerLeafId().toString());
        } catch (RedisException ex) {
            throw redisFailure("写入 Checkpoint 标记失败", ex);
        }
    }

    private void releaseCheckpoint(RedisSaver saver, RunnableConfig config) {
        // 必须先释放旧 Checkpoint 再重建：保留它会令 ReactAgent 把新上下文叠加在
        // 与 JSONL 不一致的陈旧状态上，造成消息重复或上下文错位
        try {
            saver.release(config);
        } catch (IllegalStateException ex) {
            // 线程从未建立 Checkpoint 时 release 抛 IllegalStateException，视为无需释放
        } catch (Exception ex) {
            throw redisFailure("释放旧 Checkpoint 失败", ex);
        }
    }

    private static AgentMessage lastUserMessage(AgentRequest request) {
        List<AgentMessage> messages = request.modelVisibleMessages();
        return messages.get(messages.size() - 1);
    }

    private static List<Message> toSpringMessages(List<AgentMessage> messages) {
        return messages.stream().map(ReactAgentSessionAdapter::toSpringMessage).toList();
    }

    private static Message toSpringMessage(AgentMessage message) {
        return switch (message.role()) {
            case USER -> UserMessage.builder().text(message.text()).build();
            case ASSISTANT -> AssistantMessage.builder().content(message.text()).build();
        };
    }

    private static AgentExecutionException redisFailure(String message, Throwable cause) {
        return new AgentExecutionException(AgentErrorCode.REDIS_UNAVAILABLE, message, cause);
    }

    // 模型、Agent 与 Redis 均延迟初始化；初始化失败不缓存失败结果，允许下次重试
    private synchronized ChatModelHandle handle() {
        if (chatModelHandle == null) {
            chatModelHandle = chatModelProvider.get();
        }
        return chatModelHandle;
    }

    private synchronized ReactAgent reactAgent(ChatModelHandle handle) {
        if (reactAgent == null) {
            reactAgent = ReactAgent.builder()
                    .name("chat-agent")
                    .model(handle.chatModel())
                    .systemPrompt("你是 SalmonMind 的对话助手。")
                    .saver(saver())
                    .build();
        }
        return reactAgent;
    }

    private synchronized RedisSaver saver() {
        if (redisSaver == null) {
            redisSaver = RedisSaver.builder()
                    .redisson(redissonClient())
                    .build();
        }
        return redisSaver;
    }

    private synchronized RedissonClient redissonClient() {
        if (redissonClient == null) {
            if (!StringUtils.hasText(redisUrl)) {
                throw new AgentExecutionException(AgentErrorCode.REDIS_UNAVAILABLE, "Redis 未配置");
            }
            Config config = new Config();
            config.useSingleServer()
                    .setAddress(redisUrl)
                    .setPassword(StringUtils.hasText(redisPassword) ? redisPassword : null)
                    // 缩短超时与重试，保证 Redis 不可用时快速映射为 REDIS_UNAVAILABLE
                    .setConnectTimeout(3000)
                    .setTimeout(3000)
                    .setRetryAttempts(1);
            redissonClient = Redisson.create(config);
        }
        return redissonClient;
    }

    // 供测试关闭底层 RedissonClient
    void close() {
        RedissonClient client = redissonClient;
        redissonClient = null;
        redisSaver = null;
        reactAgent = null;
        chatModelHandle = null;
        if (client != null) {
            client.shutdown();
        }
    }
}
