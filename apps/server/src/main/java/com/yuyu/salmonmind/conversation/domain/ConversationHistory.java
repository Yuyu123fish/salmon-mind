package com.yuyu.salmonmind.conversation.domain;

import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;

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
        validateContinuationActions(entries);
    }

    private static void validateContinuationActions(List<Entry> entries) {
        Map<UUID, Entry> byId = new HashMap<>();
        for (Entry entry : entries) {
            byId.put(entry.id(), entry);
        }
        for (Entry entry : entries) {
            if (entry.type() != Entry.EntryType.USER_MESSAGE) {
                continue;
            }
            UserMessagePayload payload = (UserMessagePayload) entry.payload();
            if (payload.action() != UserMessagePayload.Action.CONTINUE_GENERATION) {
                continue;
            }
            Entry source = byId.get(payload.sourceAssistantEntryId());
            if (source == null || source.type() != Entry.EntryType.ASSISTANT_MESSAGE
                    || !source.id().equals(entry.parentId())
                    || source.seq() >= entry.seq()) {
                throw corrupted("继续生成动作没有指向其父 Assistant Entry");
            }
        }
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

    /**
     * 从活动叶子沿当前 Active Path 倒序定位最近一个 Compaction Entry；路径上没有压缩时返回 null。
     * 不扫描物理 Entries 尾部：分支（不在当前路径上）的 Compaction 不是当前模型投影的一部分，
     * 不能作为"最新压缩"参与 usage 锚点或 PostgreSQL 指针修复。
     */
    public Entry latestCompactionOnPath(UUID activeLeafEntryId) {
        List<Entry> path = activePath(activeLeafEntryId);
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i).type() == Entry.EntryType.COMPACTION) {
                return path.get(i);
            }
        }
        return null;
    }

    /**
     * 最新 Title Entry；没有时返回 null。Title 是 Conversation 级元数据事件，不属于分支上下文，
     * 因此基于完整合法 JSONL 倒序定位，不沿 Active Path 过滤。
     */
    public Entry latestTitleEntry() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).type() == Entry.EntryType.TITLE) {
                return entries.get(i);
            }
        }
        return null;
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
