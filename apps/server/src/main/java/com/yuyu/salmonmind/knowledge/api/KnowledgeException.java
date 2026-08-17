package com.yuyu.salmonmind.knowledge.api;

/** Knowledge HTTP 与异步处理共用的稳定错误合同。 */
public class KnowledgeException extends RuntimeException {

    public enum Code {
        INVALID_UPLOAD,
        UNSUPPORTED_FORMAT,
        FILE_TOO_LARGE,
        DOCUMENT_NOT_FOUND,
        DOCUMENT_NOT_READY,
        REVISION_NOT_RETRYABLE,
        OBJECT_STORAGE_UNAVAILABLE,
        KNOWLEDGE_QUEUE_UNAVAILABLE,
        PARSE_FAILED,
        DOCUMENT_PASSWORD_REQUIRED,
        OCR_REQUIRED,
        EMBEDDING_MODEL_NOT_CONFIGURED,
        EMBEDDING_FAILED,
        KNOWLEDGE_INDEX_UNAVAILABLE,
        KNOWLEDGE_JOB_NOT_FOUND
    }

    private final Code code;

    public KnowledgeException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public KnowledgeException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
