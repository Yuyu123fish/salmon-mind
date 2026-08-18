package com.yuyu.salmonmind.knowledge.web;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 将 Knowledge 稳定错误映射为简短 JSON，不返回内部堆栈、凭据和基础设施身份。
 *
 * <p>该 Advice 只作用于 Knowledge Web Controller，并优先于其他模块的全局兜底处理器匹配
 * {@link KnowledgeException}，否则知识上传的基础设施错误会被误报成通用 500。</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = KnowledgeController.class)
class KnowledgeExceptionHandler {

    @ExceptionHandler(KnowledgeException.class)
    ResponseEntity<ApiError> knowledge(KnowledgeException ex) {
        return ResponseEntity.status(statusOf(ex.code())).body(new ApiError(ex.code().name(), ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError(KnowledgeException.Code.FILE_TOO_LARGE.name(), "文件超过大小限制"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_UPLOAD", ex.getMessage()));
    }

    private static HttpStatus statusOf(KnowledgeException.Code code) {
        return switch (code) {
            case UNSUPPORTED_FORMAT -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case DOCUMENT_NOT_FOUND, KNOWLEDGE_JOB_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DOCUMENT_NOT_READY, REVISION_NOT_RETRYABLE, DOCUMENT_DELETE_NOT_ALLOWED -> HttpStatus.CONFLICT;
            case DOCUMENT_DELETE_INCOMPLETE -> HttpStatus.SERVICE_UNAVAILABLE;
            case OBJECT_STORAGE_UNAVAILABLE, KNOWLEDGE_QUEUE_UNAVAILABLE,
                    EMBEDDING_MODEL_NOT_CONFIGURED, EMBEDDING_FAILED, KNOWLEDGE_INDEX_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case INVALID_SEARCH_QUERY -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    record ApiError(String code, String message) {
    }
}
