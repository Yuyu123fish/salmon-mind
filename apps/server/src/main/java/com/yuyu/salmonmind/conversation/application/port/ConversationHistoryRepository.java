package com.yuyu.salmonmind.conversation.application.port;

import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;

import java.time.Instant;
import java.util.UUID;

/**
 * Conversation 权威历史（JSONL）的内部 seam。实现位于 infrastructure.jsonl，
 * 只表达历史的变化轴：创建、追加、读取、压缩定位与孤儿清理。
 */
public interface ConversationHistoryRepository {

    /** 原子创建 Conversation 历史文件并写入 Header；数据库失败时可调用 {@link #deleteOrphan} 清理。 */
    void create(UUID conversationId, Instant createdAt);

    /** 串行追加一条完整 Entry 并强制刷盘；追加顺序即调用顺序。 */
    void append(UUID conversationId, Entry entry);

    /** 读取完整历史快照；末行 JSON 截断自动修复，中部或完整行损坏抛 ConversationException。 */
    ConversationHistory read(UUID conversationId);

    /** 按字节偏移校验 Compaction Entry；不一致或越界返回 false，不采用未校验偏移。 */
    boolean validateCompaction(UUID conversationId, UUID entryId, long seq, long byteOffset);

    /** 尽力删除孤儿目录（创建时数据库写入失败后的清理）。 */
    void deleteOrphan(UUID conversationId);
}
