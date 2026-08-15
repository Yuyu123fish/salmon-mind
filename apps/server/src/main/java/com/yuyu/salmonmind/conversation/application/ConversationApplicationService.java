package com.yuyu.salmonmind.conversation.application;

import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.ConversationDetail;
import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.ConversationService;
import com.yuyu.salmonmind.conversation.api.ConversationSummary;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.api.RunStreamListener;
import com.yuyu.salmonmind.conversation.application.port.ConversationHistoryRepository;
import com.yuyu.salmonmind.conversation.application.port.ConversationMetadataRepository;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import com.yuyu.salmonmind.conversation.domain.ConversationTitle;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation 用例服务：编排 Workspace、JSONL 历史与 PostgreSQL 元数据的创建、列表、打开，
 * 并把打开 / 发送 / 重试经 {@link ConversationExecutionQueue} 按 Conversation 串行后交给
 * {@link ConversationRunCoordinator}。不直接导入 Mapper、Entity、Jackson 或文件路径。
 */
@Service
class ConversationApplicationService implements ConversationService {

    private final WorkspaceRegistry workspaceRegistry;
    private final ConversationHistoryRepository historyRepository;
    private final ConversationMetadataRepository metadataRepository;
    private final ConversationRecoveryService recoveryService;
    private final ConversationExecutionQueue executionQueue;
    private final ConversationRunCoordinator runCoordinator;

    ConversationApplicationService(
            WorkspaceRegistry workspaceRegistry,
            ConversationHistoryRepository historyRepository,
            ConversationMetadataRepository metadataRepository,
            ConversationRecoveryService recoveryService,
            ConversationExecutionQueue executionQueue,
            ConversationRunCoordinator runCoordinator
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.historyRepository = historyRepository;
        this.metadataRepository = metadataRepository;
        this.recoveryService = recoveryService;
        this.executionQueue = executionQueue;
        this.runCoordinator = runCoordinator;
    }

    @Override
    @Transactional
    public ConversationSummary create() {
        UUID workspaceId = workspaceRegistry.current().id();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        // 必须先落 JSONL 再写数据库：文件写入成功后 DB 失败只是可清理的孤儿文件，
        // 反向顺序会让数据库引用一个不存在的历史文件，破坏数据权威
        historyRepository.create(id, now);
        try {
            Conversation conversation = new Conversation(
                    id, workspaceId, ConversationTitle.DEFAULT_TITLE, ConversationHistory.FORMAT_VERSION,
                    null, 0L, null, null, null, now, now);
            metadataRepository.create(conversation);
            return new ConversationSummary(
                    conversation.id(), conversation.workspaceId(), conversation.title(),
                    null, conversation.createdAt(), conversation.updatedAt());
        } catch (RuntimeException ex) {
            // 尽力清理新创建但未被数据库引用的文件；清理失败视为可识别孤儿文件
            historyRepository.deleteOrphan(id);
            throw ex;
        }
    }

    @Override
    public List<ConversationSummary> list() {
        UUID workspaceId = workspaceRegistry.current().id();
        List<Conversation> conversations = metadataRepository.listByWorkspace(workspaceId);
        if (conversations.isEmpty()) {
            return List.of();
        }
        Map<UUID, Run> latestRuns = metadataRepository.latestRunsByConversation(
                conversations.stream().map(Conversation::id).toList());
        return conversations.stream()
                .map(c -> new ConversationSummary(
                        c.id(), c.workspaceId(), c.title(), latestRuns.get(c.id()), c.createdAt(), c.updatedAt()))
                .toList();
    }

    @Override
    public ConversationDetail open(UUID conversationId) {
        // 打开与发送 / 重试共用同一 Conversation 队列：读取、恢复与写入互斥，避免看到半程状态。
        // 不在此处开事务：reconcile 的数据库修复以 JSONL 为权威且幂等，逐语句提交即可，
        // 若用 @Transactional 则提交发生在队列锁释放之后，破坏“队列内看到上一次已提交状态”的不变量
        return executionQueue.execute(conversationId, () -> doOpen(conversationId));
    }

    @Override
    public void send(UUID conversationId, String text, RunStreamListener listener) {
        executionQueue.execute(conversationId, () -> {
            runCoordinator.send(conversationId, text, listener);
            return null;
        });
    }

    @Override
    public void retry(UUID conversationId, UUID runId, RunStreamListener listener) {
        executionQueue.execute(conversationId, () -> {
            runCoordinator.retry(conversationId, runId, listener);
            return null;
        });
    }

    private ConversationDetail doOpen(UUID conversationId) {
        UUID workspaceId = workspaceRegistry.current().id();
        Conversation entity = metadataRepository.findById(conversationId);
        if (entity == null || !workspaceId.equals(entity.workspaceId())) {
            throw new ConversationException(
                    ConversationException.ConversationErrorCode.CONVERSATION_NOT_FOUND,
                    "Conversation 不存在");
        }
        // 先 reconcile 以 JSONL 为权威修复数据库索引，再基于修复后的活动叶子构建 Active Path，
        // 保证返回的路径与数据库索引一致；历史损坏在此步抛 CONVERSATION_HISTORY_CORRUPTED
        ConversationRecoveryService.Reconciliation reconciliation = recoveryService.reconcile(conversationId, entity);
        Conversation conversation = reconciliation.conversation();
        List<Entry> activePath = reconciliation.history().activePath(conversation.activeLeafEntryId());
        Run pendingRun = pendingRun(conversation, activePath);
        return new ConversationDetail(conversation, activePath, pendingRun);
    }

    // 活动叶子是待回答上下文节点（User 或本 Run 追加的 Compaction）时，
    // 返回 Active Path 上最近一个尚未成功的触发 User 的最新 Run，用于前端展示重试入口
    private Run pendingRun(Conversation conversation, List<Entry> activePath) {
        if (activePath.isEmpty() || conversation.activeLeafEntryId() == null) {
            return null;
        }
        Entry leaf = activePath.get(activePath.size() - 1);
        if (leaf.type() == Entry.EntryType.ASSISTANT_MESSAGE) {
            return null;
        }
        for (int i = activePath.size() - 1; i >= 0; i--) {
            Entry entry = activePath.get(i);
            if (entry.type() == Entry.EntryType.USER_MESSAGE) {
                Run run = metadataRepository.latestUnsuccessfulRun(conversation.id(), entry.id());
                if (run != null) {
                    return run;
                }
            }
        }
        return null;
    }
}
