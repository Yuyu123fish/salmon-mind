package com.yuyu.salmonmind.conversation.api;

import java.util.UUID;

/**
 * 用户消息 payload；runId 使 JSONL 已写而数据库未写时仍可恢复 Run。
 */
public record UserMessagePayload(
        String text, UUID runId, Action action, UUID sourceAssistantEntryId
) implements EntryPayload {

    public UserMessagePayload {
        action = action == null ? Action.MESSAGE : action;
        if (action == Action.CONTINUE_GENERATION && sourceAssistantEntryId == null) {
            throw new IllegalArgumentException("继续生成动作必须指向来源 Assistant Entry");
        }
        if (action == Action.MESSAGE && sourceAssistantEntryId != null) {
            throw new IllegalArgumentException("普通用户消息不能携带来源 Assistant Entry");
        }
    }

    /** 兼容旧 JSONL 与既有普通消息调用。 */
    public UserMessagePayload(String text, UUID runId) {
        this(text, runId, Action.MESSAGE, null);
    }

    public enum Action {
        MESSAGE,
        CONTINUE_GENERATION
    }
}
