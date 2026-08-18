package com.yuyu.salmonmind.conversation.api;

/** 成功 Run 的回答结果状态；FAILED/INTERRUPTED/RUNNING 时必须为空。 */
public enum RunResultStatus {
    COMPLETE,
    INCOMPLETE_LENGTH
}
