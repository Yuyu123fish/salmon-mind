package com.yuyu.salmonmind.conversation.application;

import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.api.TitlePayload;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import com.yuyu.salmonmind.conversation.application.port.ConversationHistoryRepository;
import com.yuyu.salmonmind.conversation.application.port.ConversationMetadataRepository;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 数据库索引与 JSONL 的 reconciliation：以合法 JSONL 为权威推进数据库索引、
 * 补齐 Run 行与终态、把遗留 RUNNING Run 恢复为 INTERRUPTED、校验或修复压缩索引；
 * 数据库指向不存在 Entry、Header 身份不一致或文件损坏时拒绝继续。不直接解析 JSON，也不接触 Mapper。
 */
@Component
class ConversationRecoveryService {

    private final ConversationHistoryRepository historyRepository;
    private final ConversationMetadataRepository metadataRepository;

    ConversationRecoveryService(
            ConversationHistoryRepository historyRepository,
            ConversationMetadataRepository metadataRepository
    ) {
        this.historyRepository = historyRepository;
        this.metadataRepository = metadataRepository;
    }

    // 应用就绪时把所有遗留 RUNNING Run 恢复为 INTERRUPTED（进程中断遗留，可重试）；
    // 启动后不再有跨进程在飞 Run，单进程队列保证运行期不会产生新的冲突
    @EventListener(ApplicationReadyEvent.class)
    void recoverInterruptedRunsAtStartup() {
        metadataRepository.interruptAllRunningRuns();
    }

    /** reconcile 结果：修复后的 Conversation 元数据与同一次读取的历史。 */
    record Reconciliation(Conversation conversation, ConversationHistory history) {
    }

    /**
     * 以 JSONL 为权威修复数据库索引并返回修复结果。
     * 前置条件：调用方已确认 Conversation 行存在且属于当前 Workspace。
     * 失败语义：数据库指向不存在 Entry、Header 身份不一致或文件损坏时抛
     * CONVERSATION_HISTORY_CORRUPTED；JSONL 领先数据库时在本步推进索引与 Run，
     * 下一次读取不再重复修复。
     */
    Reconciliation reconcile(UUID conversationId, Conversation current) {
        ConversationHistory history = historyRepository.read(conversationId);
        List<Entry> entries = history.entries();

        // 数据库指向的 Entry 必须存在于 JSONL，否则数据权威被破坏
        if (current.activeLeafEntryId() != null && !containsEntry(entries, current.activeLeafEntryId())) {
            throw corrupted("数据库活动叶子在 JSONL 中不存在");
        }

        // 队列内不存在本进程的在飞 Run，遗留 RUNNING 只可能是进程中断产物：恢复为可重试的 INTERRUPTED
        metadataRepository.interruptRunningRuns(conversationId);

        // 推进 JSONL 领先数据库的部分
        for (Entry entry : entries) {
            if (entry.seq() > current.lastConfirmedSeq()) {
                advanceEntry(entry);
            }
        }

        // 活动叶子与确认序号以 JSONL 为准：Title Entry 是元数据事件，只推进 seq，不推进 Active Path，
        // 因此叶子取物理最后一条非 Title Entry，而 seq 包含 Title
        Entry lastContextEntry = null;
        Entry lastEntry = null;
        for (Entry entry : entries) {
            if (entry.type() != Entry.EntryType.TITLE) {
                lastContextEntry = entry;
            }
            lastEntry = entry;
        }
        UUID leaf = lastContextEntry == null ? null : lastContextEntry.id();
        long lastSeq = lastEntry == null ? 0 : lastEntry.seq();

        // 标题以最新有效 Title Entry 为权威修复；Stage 2 起标题只来自模型生成，
        // 不再从首条用户消息截断；数据库已有的旧截断标题保留，等 Title Entry 覆盖
        String title = current.title();
        Entry titleEntry = history.latestTitleEntry();
        if (titleEntry != null) {
            title = ((TitlePayload) titleEntry.payload()).title();
        }

        // 压缩索引只沿当前 Active Path 定位：数据库指针必须等于路径上最新 Compaction 且字节偏移可校验，
        // 否则按路径修复；分支上的 Compaction 不是当前模型投影的一部分，不得采用
        UUID compactionEntryId = current.latestCompactionEntryId();
        Long compactionSeq = current.latestCompactionSeq();
        Long compactionOffset = current.latestCompactionByteOffset();
        Entry latestOnPath = history.latestCompactionOnPath(leaf);
        boolean pointerValid = latestOnPath != null
                && compactionEntryId != null
                && compactionEntryId.equals(latestOnPath.id())
                && compactionSeq != null
                && compactionSeq == latestOnPath.seq()
                && historyRepository.validateCompaction(
                        conversationId, compactionEntryId, compactionSeq, compactionOffset);
        if (!pointerValid) {
            // 指针缺失、越界、不一致或不在当前 Active Path：沿 Active Path 反向修复
            compactionEntryId = latestOnPath == null ? null : latestOnPath.id();
            compactionSeq = latestOnPath == null ? null : latestOnPath.seq();
            compactionOffset = latestOnPath == null ? null : history.byteOffsetOf(latestOnPath);
        }

        Conversation repaired = new Conversation(
                current.id(),
                current.workspaceId(),
                title,
                current.historyFormatVersion(),
                leaf, lastSeq, compactionEntryId, compactionSeq, compactionOffset,
                current.createdAt(),
                current.updatedAt()
        );

        // changed 判定必须覆盖标题与压缩索引：仅 Title 或 Compaction 索引变化也要写回 PostgreSQL
        boolean changed = !Objects.equals(leaf, current.activeLeafEntryId())
                || lastSeq != current.lastConfirmedSeq()
                || !Objects.equals(title, current.title())
                || !Objects.equals(compactionEntryId, current.latestCompactionEntryId())
                || !Objects.equals(compactionSeq, current.latestCompactionSeq())
                || !Objects.equals(compactionOffset, current.latestCompactionByteOffset());
        if (changed) {
            repaired = new Conversation(
                    repaired.id(), repaired.workspaceId(), repaired.title(), repaired.historyFormatVersion(),
                    repaired.activeLeafEntryId(), repaired.lastConfirmedSeq(),
                    repaired.latestCompactionEntryId(), repaired.latestCompactionSeq(),
                    repaired.latestCompactionByteOffset(), repaired.createdAt(), Instant.now());
            metadataRepository.update(repaired);
        }
        return new Reconciliation(repaired, history);
    }

    // 按 seq 顺序补齐 JSONL 领先部分对应的数据库状态
    private void advanceEntry(Entry entry) {
        switch (entry.type()) {
            case USER_MESSAGE -> {
                UserMessagePayload payload = (UserMessagePayload) entry.payload();
                // 同一 trigger 可能有多次重试 Run，只关心是否存在
                if (!metadataRepository.existsRunByTrigger(entry.conversationId(), entry.id())) {
                    // 携带 Run ID 的待回答用户 Entry 缺少 Run 行：重建为 INTERRUPTED，允许重试
                    metadataRepository.insertRun(new Run(
                            payload.runId(), entry.conversationId(), entry.id(),
                            Run.RunStatus.INTERRUPTED, null, entry.createdAt(), null));
                }
            }
            case ASSISTANT_MESSAGE -> {
                AssistantMessagePayload payload = (AssistantMessagePayload) entry.payload();
                Run run = metadataRepository.findRunById(payload.runId());
                if (run == null) {
                    // 补齐成功终态的 Run；trigger 为该回答的用户父 Entry
                    metadataRepository.insertRun(new Run(
                            payload.runId(), entry.conversationId(), entry.parentId(),
                            Run.RunStatus.SUCCEEDED, null, entry.createdAt(), entry.createdAt()));
                } else if (run.status() != Run.RunStatus.SUCCEEDED || run.endedAt() == null) {
                    // 数据库仍落后：补齐终态与结束时间
                    metadataRepository.updateRun(new Run(
                            run.id(), run.conversationId(), run.triggerEntryId(),
                            Run.RunStatus.SUCCEEDED, run.errorCode(), run.startedAt(), entry.createdAt()));
                }
            }
            case COMPACTION -> {
                // 压缩 Entry 只参与压缩索引校验，不改变 Run 或活动叶子
            }
            case TITLE -> {
                // 标题修复由最新 Title Entry 单独完成；Title 不改变 Run 或活动叶子
            }
        }
    }

    private static boolean containsEntry(List<Entry> entries, UUID entryId) {
        return entries.stream().anyMatch(e -> e.id().equals(entryId));
    }

    private static ConversationException corrupted(String message) {
        return new ConversationException(
                ConversationException.ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED, message);
    }
}
