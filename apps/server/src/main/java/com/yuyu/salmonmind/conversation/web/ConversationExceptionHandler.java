package com.yuyu.salmonmind.conversation.web;

import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.conversation.api.ConversationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * conversation 模块的稳定错误映射：把业务失败转换为
 * {@code {"code": "STABLE_CODE", "message": "用户可理解信息"}}。
 * 不向前端返回内部路径、Redis Key、凭据或原始堆栈；诊断原因保留在服务端日志。
 */
@RestControllerAdvice
class ConversationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ConversationExceptionHandler.class);

    @ExceptionHandler(ConversationException.class)
    ResponseEntity<ErrorBody> handleConversation(ConversationException ex) {
        HttpStatus status = switch (ex.code()) {
            case CONVERSATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONVERSATION_BUSY, CONVERSATION_AWAITING_RETRY, CONTEXT_LIMIT_REACHED -> HttpStatus.CONFLICT;
            case COMPACTION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CONVERSATION_HISTORY_CORRUPTED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorBody(ex.code().name(), ex.getMessage()));
    }

    // 模型或 Redis 依赖失败：错误码来自 agent::api 的稳定合同，Run 已标记 FAILED，可重试
    @ExceptionHandler(AgentExecutionException.class)
    ResponseEntity<ErrorBody> handleAgent(AgentExecutionException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorBody(ex.code().name(), ex.getMessage()));
    }

    // 输入校验失败（空消息正文、请求体缺失或类型不匹配、非法路径参数）
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> handleInput(Exception ex) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_INPUT", "请求输入不合法"));
    }

    // 未知资源路径
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("NOT_FOUND", "资源不存在"));
    }

    // 兜底：不暴露内部细节，只返回通用信息；诊断原因保留在日志
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> handleUnexpected(Exception ex) {
        log.error("未映射的服务器错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody("INTERNAL_ERROR", "服务器内部错误"));
    }

    record ErrorBody(String code, String message) {
    }
}
