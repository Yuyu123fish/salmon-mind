package com.yuyu.salmonmind.model.rerank;

/** 精排 Adapter 的稳定失败语义，不携带 API Key 或原始响应正文。 */
public class RerankException extends RuntimeException {

    public enum Code {
        NOT_CONFIGURED,
        FAILED,
        INVALID_RESPONSE
    }

    private final Code code;

    public RerankException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public RerankException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
