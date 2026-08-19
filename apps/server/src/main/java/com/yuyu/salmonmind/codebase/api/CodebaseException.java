package com.yuyu.salmonmind.codebase.api;

/** codebase 业务失败；Web 层只映射其稳定错误码。 */
public final class CodebaseException extends RuntimeException {

    private final CodebaseErrorCode code;

    public CodebaseException(CodebaseErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public CodebaseException(CodebaseErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public CodebaseErrorCode code() {
        return code;
    }
}
