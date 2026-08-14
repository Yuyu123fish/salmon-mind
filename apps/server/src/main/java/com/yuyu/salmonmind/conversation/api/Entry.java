package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * JSONL 中一条不可变上下文 Entry，通过 id / parentId / seq 表达身份、逻辑父节点与稳定写入顺序。
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
        COMPACTION
    }
}
