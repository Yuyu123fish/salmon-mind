package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * JSONL 中一条不可变上下文 Entry，通过 id / parentId / seq 表达身份、逻辑父节点与稳定写入顺序。
 * 不变量：Entry 一旦成功追加就不得更新或删除；seq 从 1 开始连续递增且属于同一 Conversation；
 * 首个 Entry 的 parentId 为 null，后续 Entry 指向其逻辑父节点。
 */
public record Entry(
        int formatVersion,
        UUID conversationId,
        UUID id,
        long seq,
        UUID parentId,
        EntryType type,
        Instant createdAt,
        EntryPayload payload
) {

    public enum EntryType {
        USER_MESSAGE,
        ASSISTANT_MESSAGE,
        COMPACTION,
        TITLE
    }
}
