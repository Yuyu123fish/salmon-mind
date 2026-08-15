package com.yuyu.salmonmind.agent.api;

/**
 * Agent seam 的稳定失败类型。错误码对齐 Spec 的稳定错误合同，
 * conversation 模块据此映射 HTTP 状态，不向前端暴露框架异常。
 */
public class AgentExecutionException extends RuntimeException {

    private final AgentErrorCode code;

    public AgentExecutionException(AgentErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AgentExecutionException(AgentErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public AgentErrorCode code() {
        return code;
    }

    public enum AgentErrorCode {
        CHAT_MODEL_NOT_CONFIGURED,
        CHAT_MODEL_FAILED,
        /** 提供方明确返回上下文溢出（如 context length exceeded）；主回答未输出 delta 时可据此压缩重试一次。 */
        CONTEXT_OVERFLOW,
        REDIS_UNAVAILABLE
    }
}
