package com.yuyu.salmonmind.codebase.api;

import java.util.Map;

/** codebase 业务失败；Web 层只映射其稳定错误码。 */
public final class CodebaseException extends RuntimeException {

    private final CodebaseErrorCode code;
    private final Map<String, Object> details;

    public CodebaseException(CodebaseErrorCode code, String message) {
        this(code, message, null, Map.of());
    }

    public CodebaseException(CodebaseErrorCode code, String message, Throwable cause) {
        this(code, message, cause, Map.of());
    }

    /** 向 Agent 返回有限、结构化的缺口信息；不得放入源码正文或绝对路径。 */
    public CodebaseException(CodebaseErrorCode code, String message, Map<String, Object> details) {
        this(code, message, null, details);
    }

    private CodebaseException(
            CodebaseErrorCode code, String message, Throwable cause, Map<String, Object> details
    ) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public CodebaseErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
