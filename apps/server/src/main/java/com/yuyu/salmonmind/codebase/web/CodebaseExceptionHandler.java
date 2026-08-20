package com.yuyu.salmonmind.codebase.web;

import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** codebase HTTP 的稳定安全错误映射，不回显本地路径、Git stderr 或异常堆栈。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = CodebaseController.class)
class CodebaseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CodebaseExceptionHandler.class);

    @ExceptionHandler(CodebaseException.class)
    ResponseEntity<ErrorBody> codebase(CodebaseException ex) {
        return ResponseEntity.status(statusOf(ex.code()))
                .body(new ErrorBody(ex.code().name(), safeMessage(ex.code(), ex.getMessage())));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalidInput(Exception ignored) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_QUERY", "请求输入不合法"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception ex) {
        log.error("未映射的 codebase 服务器错误", ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorBody("CODEBASE_INTERNAL_ERROR", "代码库服务内部错误"));
    }

    private HttpStatus statusOf(CodebaseErrorCode code) {
        return switch (code) {
            case INVALID_ABSOLUTE_PATH, PATH_NOT_FOUND, PATH_NOT_DIRECTORY, PATH_NOT_READABLE,
                    NOT_GIT_REPOSITORY, BARE_REPOSITORY_NOT_SUPPORTED, INVALID_QUERY,
                    CALL_CHAIN_NAME_INVALID, CALL_CHAIN_DRAFT_INVALID -> HttpStatus.BAD_REQUEST;
            case REPOSITORY_NOT_FOUND, CALL_CHAIN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CALL_CHAIN_DELETED -> HttpStatus.GONE;
            case PATH_OUTSIDE_REPOSITORY, SENSITIVE_FILE_DENIED -> HttpStatus.FORBIDDEN;
            case REPOSITORY_UNAVAILABLE, CALL_CHAIN_EVIDENCE_INSUFFICIENT,
                    CALL_CHAIN_PENDING_NOT_FOUND, CALL_CHAIN_REPOSITORY_CHANGED,
                    CALL_CHAIN_REVISION_UPDATE_REQUIRED, CALL_CHAIN_MATCH_AMBIGUOUS,
                    CALL_CHAIN_DATA_ROOT_CONFLICT,
                    CALL_CHAIN_IDENTITY_CONFLICT -> HttpStatus.CONFLICT;
            case UNSUPPORTED_TEXT_FILE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case GIT_NOT_AVAILABLE, GIT_QUERY_FAILED, CODEBASE_DATA_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GIT_QUERY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CODEBASE_DATA_CORRUPTED, CODEBASE_INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String safeMessage(CodebaseErrorCode code, String message) {
        return switch (code) {
            case INVALID_ABSOLUTE_PATH -> "必须提供 Server 所在机器上的绝对路径";
            case PATH_NOT_FOUND -> "路径不存在";
            case PATH_NOT_DIRECTORY -> "路径不是目录";
            case PATH_NOT_READABLE -> "路径不可读取";
            case NOT_GIT_REPOSITORY -> "路径不是可访问的 Git 工作树";
            case BARE_REPOSITORY_NOT_SUPPORTED -> "不支持 bare repository";
            case REPOSITORY_NOT_FOUND -> "仓库不存在";
            case REPOSITORY_UNAVAILABLE -> "仓库当前不可访问";
            case CALL_CHAIN_NOT_FOUND -> "调用链不存在";
            case CALL_CHAIN_DELETED -> "调用链已删除";
            case CALL_CHAIN_NAME_INVALID -> "调用链名称不合法";
            case CALL_CHAIN_DRAFT_INVALID -> "调用链草稿不合法";
            case CALL_CHAIN_EVIDENCE_INSUFFICIENT -> "调用链源码证据不足";
            case CALL_CHAIN_PENDING_NOT_FOUND -> "调用链待发布记录不存在";
            case CALL_CHAIN_REPOSITORY_CHANGED -> "仓库或源码已发生变化";
            case CALL_CHAIN_REVISION_UPDATE_REQUIRED -> "节点已变化，需要后续 Revision 支持";
            case CALL_CHAIN_MATCH_AMBIGUOUS -> "调用链匹配存在歧义";
            case CALL_CHAIN_DATA_ROOT_CONFLICT -> "调用链数据目录与目标仓库冲突";
            case CALL_CHAIN_IDENTITY_CONFLICT -> "调用链身份不一致";
            case PATH_OUTSIDE_REPOSITORY -> "路径超出仓库边界";
            case SENSITIVE_FILE_DENIED -> "请求访问的文件受到保护";
            case UNSUPPORTED_TEXT_FILE -> "目标不是受支持的文本文件";
            case INVALID_QUERY -> "请求输入不合法";
            case GIT_NOT_AVAILABLE -> "Git 命令当前不可用";
            case GIT_QUERY_FAILED -> "Git 查询失败";
            case GIT_QUERY_TIMEOUT -> "Git 查询超时";
            case CODEBASE_DATA_CORRUPTED -> "代码库 catalog 已损坏";
            case CODEBASE_DATA_UNAVAILABLE -> "代码库 catalog 数据目录不可用";
            case CODEBASE_INTERNAL_ERROR -> "代码库服务内部错误";
        };
    }

    record ErrorBody(String code, String message) {
    }
}
