package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentExecutionException.AgentErrorCode;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentStreamSession;
import com.yuyu.salmonmind.agent.api.AgentSummaryRequest;
import com.yuyu.salmonmind.agent.api.AgentSummaryResult;
import com.yuyu.salmonmind.agent.api.AgentSummaryService;
import com.yuyu.salmonmind.agent.api.AgentTitleRequest;
import com.yuyu.salmonmind.agent.api.AgentTitleResult;
import com.yuyu.salmonmind.agent.api.AgentTitleService;
import com.yuyu.salmonmind.agent.api.AgentUsage;
import com.yuyu.salmonmind.agent.api.CheckpointPolicy;
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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 生产 Agent Adapter：封装 ReactAgent、RedisSaver、Redisson 与 Checkpoint 叶子标记，
 * 并基于同一 ReactAgent 提供流式主回答、独立摘要与独立标题三个公开能力。
 * 模型与 Redis 均延迟初始化，应用未配置时仍可启动，首次对话才报告配置错误。
 *
 * <p>主回答走 ReactAgent 流式执行（{@code agent.stream}）：事件流按序给出
 * AGENT_MODEL_STREAMING 增量 delta，末尾 AGENT_MODEL_FINISHED 事件携带累积完整文本
 * 与最终 ChatResponse（usage 与 finishReason 在 originData 中）。Checkpoint 语义与
 * 同步调用一致：Redis 标记等于期望 JSONL 叶子才复用，否则释放并用完整投影重建；
 * 调用方可以通过 AgentRequest 的 CheckpointPolicy 显式要求强制重建。
 *
 * <p>工具生命周期通过平台 ToolLifecycleInterceptor 映射为 agent::api 的
 * started/completed/failed 事件：每次 stream 把当前 listener 挂到 RunnableConfig
 * metadata，拦截器在执行前取回并按 Tool Call ID 至多发出一对终态；工具结果在进入
 * 下一轮模型上下文前按 max-tool-result-chars 有界截断。生产 Bean 不注册任何
 * ToolCallback，测试工具只经包内构造 seam 注入。
 *
 * <p>摘要与标题是独立于 ReactAgent Checkpoint 的非流式轻量调用，请求级
 * temperature/maxTokens 通过 OpenAiChatOptions 传入，不修改模型全局默认选项。
 * 主回答的 maxTokens（输出预留）通过 ReactAgent 的 chatOptions 合并到模型默认选项上，
 * 字段级合并，不影响默认 temperature 与 model name。
 */
@Component
class ReactAgentSessionAdapter implements AgentStreamSession, AgentSummaryService, AgentTitleService {

    static final String CHECKPOINT_LEAF_KEY_PREFIX = "salmon:agent:checkpoint-leaf:";

    /** 标题输出的短上限：与标题最大长度（120 字符）对齐的保守 token 预算。 */
    private static final int TITLE_MAX_OUTPUT_TOKENS = 120;

    // 提供方明确上下文溢出的保守启发式：错误消息同时命中"上下文/长度"与"超限/过长"类关键词
    private static final Pattern CONTEXT_OVERFLOW_PATTERN = Pattern.compile(
            "(?i).*(context|window|length).*(exceed|overflow|too (long|many|large)|limit|maximum).*");

    private final ChatModelProvider chatModelProvider;
    private final String redisUrl;
    private final String redisPassword;
    private final int maxOutputTokens;
    private final int summaryMaxOutputTokens;
    private final double summaryTemperature;
    private final int maxToolResultChars;
    private final List<ToolCallback> testTools;

    private volatile ChatModelHandle chatModelHandle;
    private volatile ReactAgent reactAgent;
    private volatile RedisSaver redisSaver;
    private volatile RedissonClient redissonClient;

    /**
     * Spring 使用的注入构造：不注册任何 ToolCallback，生产 Agent 是纯对话 Agent；
     * 工具生命周期拦截器始终挂载，无工具时为空操作。
     */
    @Autowired
    ReactAgentSessionAdapter(
            ChatModelProvider chatModelProvider,
            @Value("${salmon.redis.url:}") String redisUrl,
            @Value("${salmon.redis.password:}") String redisPassword,
            @Value("${salmon.compaction.output-reserve:65432}") int maxOutputTokens,
            @Value("${salmon.compaction.summary-max-output-tokens:32768}") int summaryMaxOutputTokens,
            @Value("${salmon.compaction.summary-temperature:0.1}") double summaryTemperature,
            @Value("${salmon.agent.max-tool-result-chars:200000}") int maxToolResultChars
    ) {
        this(chatModelProvider, redisUrl, redisPassword, maxOutputTokens, summaryMaxOutputTokens,
                summaryTemperature, maxToolResultChars, List.of());
    }

    /** 兼容既有测试的包内构造：使用默认结果上限，不注册测试工具。 */
    ReactAgentSessionAdapter(
            ChatModelProvider chatModelProvider,
            String redisUrl,
            String redisPassword,
            int maxOutputTokens,
            int summaryMaxOutputTokens,
            double summaryTemperature
    ) {
        this(chatModelProvider, redisUrl, redisPassword, maxOutputTokens, summaryMaxOutputTokens,
                summaryTemperature, 200_000, List.of());
    }

    /**
     * 包内测试注入 seam：允许集成测试注册测试专用 ToolCallback 与更小的结果上限；
     * 测试工具永远只存在于包内构造的实例中，不会进入生产 Spring Bean。
     */
    ReactAgentSessionAdapter(
            ChatModelProvider chatModelProvider,
            String redisUrl,
            String redisPassword,
            int maxOutputTokens,
            int summaryMaxOutputTokens,
            double summaryTemperature,
            int maxToolResultChars,
            List<ToolCallback> testTools
    ) {
        this.chatModelProvider = chatModelProvider;
        this.redisUrl = redisUrl;
        this.redisPassword = redisPassword;
        this.maxOutputTokens = maxOutputTokens;
        this.summaryMaxOutputTokens = summaryMaxOutputTokens;
        this.summaryTemperature = summaryTemperature;
        this.maxToolResultChars = maxToolResultChars;
        this.testTools = List.copyOf(testTools);
    }

    /**
     * 同步阻塞的流式主回答；失败一律收束为 onError，成功后再写回 Checkpoint 叶子。
     */
    @Override
    public void stream(AgentRequest request, AgentStreamListener listener) {
        try {
            ChatModelHandle handle = handle();
            ReactAgent agent = reactAgent(handle);
            RedisSaver saver = saver();

            // 把当前流监听器挂到 config metadata：工具生命周期拦截器在执行前从中取回；
            // 同一 Adapter 上的并发流各自携带独立 listener，互不干扰
            RunnableConfig.Builder configBuilder = RunnableConfig.builder().threadId(request.threadId());
            configBuilder.addMetadata(ToolLifecycleInterceptor.LISTENER_METADATA_KEY, listener);
            RunnableConfig config = configBuilder.build();

            // 显式强制重建（工具轮次）或叶子标记不匹配（Feature 002 语义）时，
            // 先释放旧 Checkpoint，再只使用调用方提供的 JSONL 投影重建
            boolean rebuild = request.checkpointPolicy() == CheckpointPolicy.REBUILD_FROM_PROJECTION
                    || !canReuseCheckpoint(request);
            reactor.core.publisher.Flux<NodeOutput> flux = rebuild
                    ? rebuildFlux(agent, saver, config, request.modelVisibleMessages())
                    : agent.stream(List.of(toSpringMessage(lastUserMessage(request))), config);

            StringBuilder accumulated = new StringBuilder();
            final ChatResponse[] finalOrigin = new ChatResponse[1];
            try {
                flux.doOnNext(output -> {
                    if (!(output instanceof StreamingOutput<?> so)) {
                        return;
                    }
                    if (so.getOutputType() == OutputType.AGENT_MODEL_FINISHED) {
                        // 末尾事件携带累积完整文本与最终 ChatResponse（usage/finishReason 在 origin 中）
                        if (so.getOriginData() instanceof ChatResponse cr) {
                            finalOrigin[0] = cr;
                        }
                        return;
                    }
                    if (so.message() instanceof AssistantMessage chunk && chunk.getText() != null) {
                        // AGENT_MODEL_STREAMING 增量：转发给调用方并累积，不在此处写盘
                        accumulated.append(chunk.getText());
                        listener.onDelta(chunk.getText());
                    }
                }).blockLast();// 流结束后才会执行下面的代码

                Usage usage = finalOrigin[0] != null && finalOrigin[0].getMetadata() != null
                        ? finalOrigin[0].getMetadata().getUsage() : null;
                String finishReason = finalOrigin[0] != null && finalOrigin[0].getResult() != null
                        && finalOrigin[0].getResult().getMetadata() != null
                        ? finalOrigin[0].getResult().getMetadata().getFinishReason() : null;

                String text = accumulated.toString();
                if ("length".equalsIgnoreCase(finishReason)) {
                    // 输出达到长度限制是不完整回答，不能当作成功；与上下文溢出不同，不自动压缩重试
                    listener.onError(new AgentExecutionException(
                            AgentErrorCode.CHAT_MODEL_FAILED, "模型输出达到长度限制，回答不完整"));
                    return;
                }
                if (text.isBlank()) {
                    listener.onError(new AgentExecutionException(
                            AgentErrorCode.CHAT_MODEL_FAILED, "模型返回了空回答"));
                    return;
                }

                // 模型成功：更新 Checkpoint 叶子标记为预分配的回答 Entry，保证下一轮可复用
                writeCheckpointLeaf(request);
                listener.onComplete(new AgentResult(
                        text, handle.provider(), handle.modelName(), mapUsage(usage)));
            } catch (RuntimeException ex) {
                listener.onError(mapError(ex));
            }
        } catch (AgentExecutionException ex) {
            listener.onError(ex);
        } catch (ChatModelException ex) {
            listener.onError(new AgentExecutionException(
                    AgentErrorCode.CHAT_MODEL_NOT_CONFIGURED, "Chat 模型未配置", ex));
        } catch (RedisException ex) {
            listener.onError(redisFailure("Redis 不可用", ex));
        } catch (Exception ex) {
            listener.onError(new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "模型调用失败", ex));
        }
    }

    @Override
    public AgentSummaryResult summarize(AgentSummaryRequest request) {
        try {
            ChatModelHandle handle = handle();
            ChatResponse response = handle.chatModel().call(new Prompt(
                    toSpringMessages(request.messages()),
                    OpenAiChatOptions.builder()
                            .maxTokens(summaryMaxOutputTokens)
                            .temperature(summaryTemperature)
                            .build()));
            return toSummaryResult(handle, response);
        } catch (ChatModelException ex) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_NOT_CONFIGURED, "Chat 模型未配置", ex);
        } catch (AgentExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "摘要调用失败", ex);
        }
    }

    @Override
    public AgentTitleResult generateTitle(AgentTitleRequest request) {
        try {
            ChatModelHandle handle = handle();
            ChatResponse response = handle.chatModel().call(new Prompt(
                    toSpringMessages(request.messages()),
                    OpenAiChatOptions.builder()
                            .maxTokens(TITLE_MAX_OUTPUT_TOKENS)
                            .temperature(0.1)
                            .build()));
            return toTitleResult(handle, response);
        } catch (ChatModelException ex) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_NOT_CONFIGURED, "Chat 模型未配置", ex);
        } catch (AgentExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "标题调用失败", ex);
        }
    }

    private AgentSummaryResult toSummaryResult(ChatModelHandle handle, ChatResponse response) {
        String text = outputTextOf(response);
        if (text == null || text.isBlank()) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "摘要模型返回空白");
        }
        if ("length".equalsIgnoreCase(finishReasonOf(response))) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "摘要输出被长度截断");
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new AgentSummaryResult(text, mapUsage(usage));
    }

    private AgentTitleResult toTitleResult(ChatModelHandle handle, ChatResponse response) {
        String text = outputTextOf(response);
        if (text == null || text.isBlank()) {
            // 空白或截断都不影响已成功的主 Run：返回 null，由 conversation 保留默认标题
            return new AgentTitleResult(null, handle.provider(), handle.modelName());
        }
        return new AgentTitleResult(text, handle.provider(), handle.modelName());
    }

    private static String outputTextOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private static String finishReasonOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    // 标记缺失、指向不存在叶子或与期望不一致时都不能复用
    private boolean canReuseCheckpoint(AgentRequest request) {
        if (request.expectedCheckpointLeafId() == null) {
            return false;
        }
        String marker = readCheckpointLeaf(request.threadId());// threadId是会话ID
        return request.expectedCheckpointLeafId().toString().equals(marker);
    }

    /**
     * 读取 Checkpoint 叶子标记：如果标记缺失、指向不存在叶子或与期望不一致时都不能复用
     * @param threadId 对话ID
     */
    private String readCheckpointLeaf(String threadId) {
        try {
            // bucket 是 String 类型的数据
            RBucket<String> bucket = redissonClient().getBucket(CHECKPOINT_LEAF_KEY_PREFIX + threadId);
            return bucket.get();
        } catch (RedisException ex) {
            throw redisFailure("读取 Checkpoint 标记失败", ex);
        }
    }

    /**
     * 写入叶子标记，下一轮会拿它和 JSONL 的期望标记做比较
     */
    private void writeCheckpointLeaf(AgentRequest request) {
        try {
            RBucket<String> bucket = redissonClient().getBucket(CHECKPOINT_LEAF_KEY_PREFIX + request.threadId());
            bucket.set(request.answerLeafId().toString());
        } catch (RedisException ex) {
            throw redisFailure("写入 Checkpoint 标记失败", ex);
        }
    }

    private void releaseCheckpoint(RedisSaver saver, RunnableConfig config) {
        try {
            saver.release(config);
        } catch (IllegalStateException ex) {
            // 线程从未建立 Checkpoint 时 release 抛 IllegalStateException，视为无需释放
        } catch (Exception ex) {
            throw redisFailure("释放旧 Checkpoint 失败", ex);
        }
    }

    /**
     * 必须先释放旧 Checkpoint 再重建：保留它会令 ReactAgent 把新上下文叠加在
     * 与 JSONL 不一致的陈旧状态上，造成消息重复或上下文错位
     */
    private reactor.core.publisher.Flux<NodeOutput> rebuildFlux(
            ReactAgent agent, RedisSaver saver, RunnableConfig config, List<AgentMessage> messages
    ) {
        // 释放旧 Checkpoint
        releaseCheckpoint(saver, config);
        // 重建 Checkpoint
        try {
            return agent.stream(toSpringMessages(messages), config);
        } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException ex) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "模型执行失败", ex);
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

    /** 把流式调用中的异常映射为稳定错误；提供方明确上下文溢出映射为 CONTEXT_OVERFLOW。 */
    private AgentExecutionException mapError(RuntimeException ex) {
        if (ex instanceof RedisException) {
            return redisFailure("Redis 不可用", ex);
        }
        Throwable cause = ex;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && CONTEXT_OVERFLOW_PATTERN.matcher(message).matches()) {
                return new AgentExecutionException(
                        AgentErrorCode.CONTEXT_OVERFLOW, "模型上下文溢出: " + message, ex);
            }
            cause = cause.getCause();
        }
        return new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "模型调用失败", ex);
    }

    private static AgentExecutionException redisFailure(String message, Throwable cause) {
        return new AgentExecutionException(AgentErrorCode.REDIS_UNAVAILABLE, message, cause);
    }

    private static AgentUsage mapUsage(Usage usage) {
        if (usage == null) {
            return null;
        }
        return new AgentUsage(
                usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue(),
                usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().longValue(),
                usage.getTotalTokens() == null ? null : usage.getTotalTokens().longValue());
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
            OpenAiChatOptions mainOptions = OpenAiChatOptions.builder()
                    .maxTokens(maxOutputTokens)
                    .build();
            // 流式 usage：要求提供方在最后 chunk 返回累计用量（OpenAI-compatible include_usage）
            mainOptions.setStreamOptions(new OpenAiApi.ChatCompletionRequest.StreamOptions(true));
            reactAgent = ReactAgent.builder()
                    .name("chat-agent")
                    .model(handle.chatModel())
                    .systemPrompt("你是 SalmonMind 的对话助手。")
                    // 主回答输出上限与流式 usage：与模型默认选项字段级合并，不修改默认 temperature
                    .chatOptions(mainOptions)
                    .saver(saver())
                    // 平台工具生命周期拦截器：无工具注册时为空操作；测试工具经包内构造 seam 注入
                    .interceptors(new ToolLifecycleInterceptor(maxToolResultChars))
                    .tools(testTools)
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
