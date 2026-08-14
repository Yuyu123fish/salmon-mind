package com.yuyu.salmonmind.conversation.domain;

import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.Entry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 已解析历史文件的不可变快照：Header、按序 Entry 与每行起始字节偏移。
 * 负责 Active Path 与压缩节点定位等纯规则，不依赖文件 I/O 或数据库。
 */
public record ConversationHistory(Header header, List<Entry> entries, List<Long> byteOffsets) {

    /** JSONL v1 格式版本；Header 与 Entry 的 formatVersion 字段均为该值。 */
    public static final int FORMAT_VERSION = 1;

    public ConversationHistory {
        entries = List.copyOf(entries);
        byteOffsets = List.copyOf(byteOffsets);
    }

    /** Header 是文件第一行，不参与 Entry 树。 */
    public record Header(int formatVersion, UUID conversationId, Instant createdAt) {
    }

    /** 从活动叶子沿 parentId 回溯构建根到叶子的 Active Path。 */
    public List<Entry> activePath(UUID activeLeafEntryId) {
        if (activeLeafEntryId == null) {
            return List.of();
        }
        Map<UUID, Entry> byId = new HashMap<>();
        for (Entry entry : entries) {
            byId.put(entry.id(), entry);
        }
        List<Entry> path = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        UUID cursor = activeLeafEntryId;
        while (cursor != null) {
            if (!visited.add(cursor)) {
                throw corrupted("Entry 父链存在循环引用");
            }
            Entry entry = byId.get(cursor);
            if (entry == null) {
                throw corrupted("活动叶子在 JSONL 中不存在");
            }
            path.add(entry);
            cursor = entry.parentId();
        }
        java.util.Collections.reverse(path);
        return List.copyOf(path);
    }

    /** 最后一个 Compaction Entry；无压缩时为空。 */
    public Entry latestCompactionEntry() {
        Entry latest = null;
        for (Entry entry : entries) {
            if (entry.type() == Entry.EntryType.COMPACTION) {
                latest = entry;
            }
        }
        return latest;
    }

    /** Entry 所在行的起始字节偏移；Entry 不在本快照中时为空。 */
    public Long byteOffsetOf(Entry entry) {
        int index = entries.indexOf(entry);
        return index < 0 ? null : byteOffsets.get(index);
    }

    private static ConversationException corrupted(String message) {
        return new ConversationException(
                ConversationException.ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED, message);
    }
}
