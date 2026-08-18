package com.yuyu.salmonmind.model.embedding;

/** Embedding 模型调用失败的稳定错误，不泄露 API Key 或原始响应正文。 */
public class EmbeddingException extends RuntimeException {

    public enum Code {
        NOT_CONFIGURED,
        FAILED,
        INVALID_RESPONSE
    }

    private final Code code;

    public EmbeddingException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public EmbeddingException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
