package com.yuyu.salmonmind.knowledge.api;

/** Knowledge HTTP 与异步处理共用的稳定错误合同。 */
public class KnowledgeException extends RuntimeException {

    public enum Code {
        INVALID_UPLOAD,
        UNSUPPORTED_FORMAT,
        FILE_TOO_LARGE,
        DOCUMENT_NOT_FOUND,
        DOCUMENT_DELETE_NOT_ALLOWED,
        DOCUMENT_DELETE_INCOMPLETE,
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
        KNOWLEDGE_JOB_NOT_FOUND,
        INVALID_SEARCH_QUERY,
        UPLOAD_SESSION_NOT_FOUND,
        UPLOAD_SESSION_EXPIRED,
        UPLOAD_SESSION_CONFLICT,
        INVALID_UPLOAD_PART,
        UPLOAD_CHECKSUM_MISMATCH,
        UPLOAD_INCOMPLETE,
        UPLOAD_STATE_UNAVAILABLE,
        RESUMABLE_UPLOAD_DISABLED,
        UPLOAD_FINAL_VALIDATION_FAILED
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
