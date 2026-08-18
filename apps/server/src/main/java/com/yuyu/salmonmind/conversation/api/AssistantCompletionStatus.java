package com.yuyu.salmonmind.conversation.api;

/** Assistant 正文的 durable 完成状态；不把长度收束误报成 Run 失败。 */
public enum AssistantCompletionStatus {
    COMPLETE,
    INCOMPLETE_LENGTH
}
