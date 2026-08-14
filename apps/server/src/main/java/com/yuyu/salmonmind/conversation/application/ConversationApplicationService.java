package com.yuyu.salmonmind.conversation.application;

import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.ConversationDetail;
import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.ConversationService;
import com.yuyu.salmonmind.conversation.api.ConversationSummary;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.Run;
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
 * Conversation 用例服务：编排 Workspace、JSONL 历史与 PostgreSQL 元数据的创建、列表与打开。
 * 不直接导入 Mapper、Entity、Jackson 或文件路径；Agent 调用在第 3 步接入。
 */
@Service
class ConversationApplicationService implements ConversationService {

    private final WorkspaceRegistry workspaceRegistry;
    private final ConversationHistoryRepository historyRepository;
    private final ConversationMetadataRepository metadataRepository;
    private final ConversationRecoveryService recoveryService;

    ConversationApplicationService(
            WorkspaceRegistry workspaceRegistry,
            ConversationHistoryRepository historyRepository,
            ConversationMetadataRepository metadataRepository,
            ConversationRecoveryService recoveryService
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.historyRepository = historyRepository;
        this.metadataRepository = metadataRepository;
        this.recoveryService = recoveryService;
    }

    @Override
    @Transactional
    public ConversationSummary create() {
        UUID workspaceId = workspaceRegistry.current().id();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
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
    @Transactional
    public ConversationDetail open(UUID conversationId) {
        UUID workspaceId = workspaceRegistry.current().id();
        Conversation entity = metadataRepository.findById(conversationId);
        if (entity == null || !workspaceId.equals(entity.workspaceId())) {
            throw new ConversationException(
                    ConversationException.ConversationErrorCode.CONVERSATION_NOT_FOUND,
                    "Conversation 不存在");
        }
        ConversationRecoveryService.Reconciliation reconciliation = recoveryService.reconcile(conversationId, entity);
        Conversation conversation = reconciliation.conversation();
        List<Entry> activePath = reconciliation.history().activePath(conversation.activeLeafEntryId());
        Run pendingRun = pendingRun(conversation, activePath);
        return new ConversationDetail(conversation, activePath, pendingRun);
    }

    // 活动叶子是待回答用户 Entry 时，返回其最新未成功 Run
    private Run pendingRun(Conversation conversation, List<Entry> activePath) {
        if (activePath.isEmpty() || conversation.activeLeafEntryId() == null) {
            return null;
        }
        Entry leaf = activePath.get(activePath.size() - 1);
        if (leaf.type() != Entry.EntryType.USER_MESSAGE) {
            return null;
        }
        return metadataRepository.latestUnsuccessfulRun(conversation.id(), leaf.id());
    }
}
