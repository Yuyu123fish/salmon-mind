package com.yuyu.salmonmind.conversation.api;

/**
 * conversation 模块的稳定失败类型；错误码对齐 Spec 的稳定错误合同，HTTP 层据此映射状态。
 */
public class ConversationException extends RuntimeException {

    private final ConversationErrorCode code;

    public ConversationException(ConversationErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ConversationException(ConversationErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ConversationErrorCode code() {
        return code;
    }

    public enum ConversationErrorCode {
        CONVERSATION_NOT_FOUND,
        CONVERSATION_BUSY,
        CONVERSATION_AWAITING_RETRY,
        CONTEXT_LIMIT_REACHED,
        /** 摘要调用失败、结果空白、结构非法或被长度截断；User Entry 与失败 Run 保留，可重试。 */
        COMPACTION_FAILED,
        CONVERSATION_HISTORY_CORRUPTED
    }
}
