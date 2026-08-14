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
        REDIS_UNAVAILABLE
    }
}
