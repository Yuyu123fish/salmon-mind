package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentToolCompleted;
import com.yuyu.salmonmind.agent.api.AgentToolFailed;
import com.yuyu.salmonmind.agent.api.AgentToolStarted;

/**
 * 平台拥有的工具生命周期拦截器：把 Spring AI Alibaba 的 ToolInterceptor 钩子映射为
 * {@code agent::api} 的 started/completed/failed 平台事件，并在此处完成两个 Gate 控制点：
 *
 * <ul>
 *   <li>每个 Tool Call 的 started 后至多一次 completed 或 failed：结果以
 *       {@link ToolCallResponse#isError()} 与异常为失败依据，二者互斥。</li>
 *   <li>工具结果在回到模型上下文前按 {@code maxToolResultChars} 有界截断，
 *       不允许超长原始结果直接进入下一轮模型 Prompt。</li>
 * </ul>
 *
 * <p>拦截器通过 {@link #LISTENER_METADATA_KEY} 从 RunnableConfig metadata 中取出
 * 当前流的 {@link AgentStreamListener}：config 由 Adapter 在每次 stream 时构建并
 * 随图执行流转，因此同一 Agent 上的并发流互不干扰。没有挂载 listener 时为空操作，
 * 生产 Bean 未注册任何 ToolCallback 时该拦截器同样成立。
 *
 * <p>异常采用与框架 ToolErrorInterceptor 相同的处理模式：捕获后转为错误
 * ToolCallResponse 送回 Agent 循环，而不是抛出——保证工具失败只产生一次 failed 观察，
 * 并最终收束为唯一一次 Agent complete 或 error，不产生双终态。
 */
class ToolLifecycleInterceptor extends ToolInterceptor {

    /** RunnableConfig metadata 中挂载当前流监听器的键。 */
    static final String LISTENER_METADATA_KEY = "salmon:agent:tool-listener";

    /** 工具失败的标准错误码：所有未分类的异常统一使用该稳定错误码。 */
    private static final String ERROR_CODE_TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";

    /** 工具参数安全摘要的最大字符数：只用于状态展示，不承载完整参数。 */
    private static final int SUMMARY_MAX_CHARS = 100;

    private final int maxToolResultChars;

    ToolLifecycleInterceptor(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
    }

    @Override
    public String getName() {
        return "tool-lifecycle-observer";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        AgentStreamListener listener = listenerOf(request);
        long startedNanos = System.nanoTime();
        if (listener != null) {
            listener.onToolStarted(new AgentToolStarted(
                    request.getToolCallId(), request.getToolName(), safeSummary(request.getArguments())));
        }
        try {
            ToolCallResponse response = boundResultSize(handler.call(request));
            long durationMillis = elapsedMillis(startedNanos);
            if (listener != null) {
                if (response.isError()) {
                    listener.onToolFailed(new AgentToolFailed(
                            response.getToolCallId(), response.getToolName(), durationMillis,
                            stableErrorCode(response), safeSummary(response.getResult())));
                } else {
                    listener.onToolCompleted(new AgentToolCompleted(
                            response.getToolCallId(), response.getToolName(), durationMillis));
                }
            }
            return response;
        } catch (RuntimeException ex) {
            // 与框架 ToolErrorInterceptor 同款模式：异常转错误结果，保持循环单终态
            if (listener != null) {
                listener.onToolFailed(new AgentToolFailed(
                        request.getToolCallId(), request.getToolName(), elapsedMillis(startedNanos),
                        ERROR_CODE_TOOL_EXECUTION_FAILED, safeSummary(ex.getMessage())));
            }
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), ex);
        }
    }

    /** 从当前 ToolCallRequest 的执行上下文 config 中取回挂载的监听器；未挂载返回 null。 */
    private static AgentStreamListener listenerOf(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(LISTENER_METADATA_KEY))
                .filter(AgentStreamListener.class::isInstance)
                .map(AgentStreamListener.class::cast)
                .orElse(null);
    }

    /** 工具结果进入模型上下文前的有界控制点：超出上限按字符截断，不携带完整原始结果。 */
    private ToolCallResponse boundResultSize(ToolCallResponse response) {
        String result = response.getResult();
        if (result == null || result.length() <= maxToolResultChars) {
            return response;
        }
        ToolCallResponse.Builder builder = ToolCallResponse.builder()
                .toolCallId(response.getToolCallId())
                .toolName(response.getToolName())
                .content(result.substring(0, maxToolResultChars));
        if (response.getStatus() != null) {
            builder.status(response.getStatus());
        }
        if (response.getMetadata() != null) {
            builder.metadata(response.getMetadata());
        }
        return builder.build();
    }

    /**
     * 错误响应优先使用框架 status，但框架统一使用 "error" 表达所有失败：
     * 映射为平台稳定错误码，避免把内部 status 直接暴露为业务错误码。
     */
    private static String stableErrorCode(ToolCallResponse response) {
        String status = response.getStatus();
        return status == null || status.isBlank() || "error".equalsIgnoreCase(status)
                ? ERROR_CODE_TOOL_EXECUTION_FAILED
                : status;
    }

    /** 安全摘要：去掉控制字符后按固定长度截断，避免原始参数/结果泄露到事件中。 */
    static String safeSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = raw.replaceAll("\\p{Cc}", " ").trim();
        return cleaned.length() <= SUMMARY_MAX_CHARS ? cleaned : cleaned.substring(0, SUMMARY_MAX_CHARS);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
