package com.yuyu.salmonmind.conversation.application;

import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentSession;
import com.yuyu.salmonmind.agent.api.AgentUsage;
import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.ConversationException.ConversationErrorCode;
import com.yuyu.salmonmind.conversation.api.ConversationRunResult;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.Entry.EntryType;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.api.Run.RunStatus;
import com.yuyu.salmonmind.conversation.api.TokenUsage;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import com.yuyu.salmonmind.conversation.application.port.ConversationHistoryRepository;
import com.yuyu.salmonmind.conversation.application.port.ConversationMetadataRepository;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import com.yuyu.salmonmind.conversation.domain.ConversationTitle;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 发送 / 重试的完整 Run 协调器，被 {@link ConversationExecutionQueue} 按 Conversation 串行调用。
 *
 * 顺序不变量（由队列保证单写者）：
 * 1. 先以 JSONL 为权威恢复并校验 Conversation，再追加用户 Entry 并强制刷盘；
 * 2. 数据库事务创建 RUNNING Run 并推进活动叶子、序号与标题（Agent 调用绝不在事务内）；
 * 3. Agent 失败时不追加 Assistant Entry，只把 Run 置为 FAILED 并抛出 agent::api 稳定异常；
 * 4. 成功时先追加 Assistant Entry 再以数据库事务完成 Run 并推进活动叶子；
 * 5. JSONL 已写而数据库事务失败时，由下一次 {@link ConversationRecoveryService} 修复索引。
 */
@Component
class ConversationRunCoordinator {

    private final WorkspaceRegistry workspaceRegistry;
    private final ConversationRecoveryService recoveryService;
    private final ConversationHistoryRepository historyRepository;
    private final ConversationMetadataRepository metadataRepository;
    private final AgentSession agentSession;
    private final TransactionTemplate transactionTemplate;
    private final int maxPromptChars;

    ConversationRunCoordinator(
            WorkspaceRegistry workspaceRegistry,
            ConversationRecoveryService recoveryService,
            ConversationHistoryRepository historyRepository,
            ConversationMetadataRepository metadataRepository,
            AgentSession agentSession,
            TransactionTemplate transactionTemplate,
            @Value("${salmon.agent.max-prompt-chars:262144}") int maxPromptChars
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.recoveryService = recoveryService;
        this.historyRepository = historyRepository;
        this.metadataRepository = metadataRepository;
        this.agentSession = agentSession;
        this.transactionTemplate = transactionTemplate;
        this.maxPromptChars = maxPromptChars;
    }

    ConversationRunResult send(UUID conversationId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        RecoveryState state = recover(conversationId);
        Conversation conversation = state.conversation();
        ConversationHistory history = state.history();

        // 活动叶子仍是待回答用户 Entry 时不能发送新消息，只能重试
        ensureNotAwaitingRetry(conversation, history);

        // 预分配 Run / 用户 Entry / 回答 Entry ID，保证 JSONL 先写而数据库未写时仍可恢复
        UUID runId = UUID.randomUUID();
        UUID userEntryId = UUID.randomUUID();
        UUID answerEntryId = UUID.randomUUID();
        long userSeq = conversation.lastConfirmedSeq() + 1;

        List<AgentMessage> messages = projection(history, conversation.activeLeafEntryId());
        messages.add(new AgentMessage(AgentMessage.Role.USER, text));
        ensureWithinContextLimit(messages);

        Instant now = Instant.now();
        Entry userEntry = new Entry(
                ConversationHistory.FORMAT_VERSION, conversationId, userEntryId, userSeq,
                conversation.activeLeafEntryId(), EntryType.USER_MESSAGE, now,
                new UserMessagePayload(text, runId));
        historyRepository.append(conversationId, userEntry);

        String title = ConversationTitle.DEFAULT_TITLE.equals(conversation.title())
                ? ConversationTitle.fromFirstUserEntry(text)
                : conversation.title();
        Conversation advanced = advance(conversation, userEntry.id(), userSeq, title, now);
        Run running = new Run(runId, conversationId, userEntryId, RunStatus.RUNNING, null, now, null);
        // 创建 RUNNING Run 与推进叶子必须在同一事务：数据库状态不能出现 Run 领先叶子或反向
        transactionTemplate.executeWithoutResult(status -> {
            metadataRepository.insertRun(running);
            metadataRepository.update(advanced);
        });

        try {
            AgentResult result = agentSession.complete(new AgentRequest(
                    conversationId.toString(), userEntry.parentId(), answerEntryId, messages));
            return finishSuccess(conversationId, advanced, userEntry, runId, answerEntryId, result, running);
        } catch (AgentExecutionException ex) {
            failRun(running, ex);
            throw ex;
        }
    }

    ConversationRunResult retry(UUID conversationId, UUID runId) {
        RecoveryState state = recover(conversationId);
        Conversation conversation = state.conversation();
        ConversationHistory history = state.history();

        Run previous = metadataRepository.findRunById(runId);
        if (previous == null || !conversationId.equals(previous.conversationId())) {
            throw new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND, "Run 不存在");
        }
        if (previous.status() == RunStatus.RUNNING) {
            // 队列内不存在本进程的在飞 Run，此处只能是绕过队列或未知状态的残留
            throw new ConversationException(ConversationErrorCode.CONVERSATION_BUSY, "该 Run 仍在执行中");
        }
        if (previous.status() == RunStatus.SUCCEEDED
                || conversation.activeLeafEntryId() == null
                || !conversation.activeLeafEntryId().equals(previous.triggerEntryId())) {
            // 只有当前活动叶子（未回答用户 Entry）才可重试，避免在已回答消息上追加分支
            throw new ConversationException(
                    ConversationErrorCode.CONVERSATION_AWAITING_RETRY, "该消息已得到回答，不能重试");
        }

        Entry trigger = findEntry(history, previous.triggerEntryId());
        if (trigger == null || trigger.type() != EntryType.USER_MESSAGE) {
            throw new ConversationException(
                    ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED, "重试触发的用户 Entry 不存在");
        }

        List<AgentMessage> messages = projection(history, conversation.activeLeafEntryId());
        ensureWithinContextLimit(messages);

        // 重试复用原用户 Entry，只为同一触发 Entry 创建新的 Run 记录
        UUID newRunId = UUID.randomUUID();
        UUID answerEntryId = UUID.randomUUID();
        Instant now = Instant.now();
        Run running = new Run(newRunId, conversationId, trigger.id(), RunStatus.RUNNING, null, now, null);
        transactionTemplate.executeWithoutResult(status -> metadataRepository.insertRun(running));

        try {
            AgentResult result = agentSession.complete(new AgentRequest(
                    conversationId.toString(), trigger.parentId(), answerEntryId, messages));
            return finishSuccess(conversationId, conversation, trigger, newRunId, answerEntryId, result, running);
        } catch (AgentExecutionException ex) {
            failRun(running, ex);
            throw ex;
        }
    }

    // 成功路径：先追加 Assistant Entry，再在同一数据库事务完成 Run 与推进叶子
    private ConversationRunResult finishSuccess(
            UUID conversationId, Conversation conversation, Entry userEntry,
            UUID runId, UUID answerEntryId, AgentResult result, Run running
    ) {
        if (result.text() == null || result.text().isBlank()) {
            // 空回答视为模型失败：不追加空 Assistant Entry，由调用方完成 FAILED Run 后可重试
            throw new AgentExecutionException(
                    AgentExecutionException.AgentErrorCode.CHAT_MODEL_FAILED, "模型返回了空回答");
        }
        long answerSeq = conversation.lastConfirmedSeq() + 1;
        Instant answeredAt = Instant.now();
        Entry assistantEntry = new Entry(
                ConversationHistory.FORMAT_VERSION, conversationId, answerEntryId, answerSeq,
                userEntry.id(), EntryType.ASSISTANT_MESSAGE, answeredAt,
                new AssistantMessagePayload(
                        result.text(), runId, result.provider(), result.model(), mapUsage(result.usage())));
        historyRepository.append(conversationId, assistantEntry);

        Run finished = new Run(
                runId, conversationId, userEntry.id(), RunStatus.SUCCEEDED, null,
                running.startedAt(), answeredAt);
        Conversation finalConversation = advance(conversation, assistantEntry.id(), answerSeq,
                conversation.title(), answeredAt);
        transactionTemplate.executeWithoutResult(status -> {
            metadataRepository.updateRun(finished);
            metadataRepository.update(finalConversation);
        });
        return new ConversationRunResult(finalConversation, userEntry, assistantEntry, finished);
    }

    // 失败路径：不追加 Assistant Entry，只完成失败 Run；活动叶子仍是待回答用户 Entry
    private void failRun(Run running, AgentExecutionException ex) {
        Run failed = new Run(
                running.id(), running.conversationId(), running.triggerEntryId(),
                RunStatus.FAILED, ex.code().name(), running.startedAt(), Instant.now());
        metadataRepository.updateRun(failed);
    }

    // 打开 / 发送 / 重试的统一前置：确认 Conversation 属于当前 Workspace，并以 JSONL 修复数据库索引
    private RecoveryState recover(UUID conversationId) {
        UUID workspaceId = workspaceRegistry.current().id();
        Conversation conversation = metadataRepository.findById(conversationId);
        if (conversation == null || !workspaceId.equals(conversation.workspaceId())) {
            throw new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND, "Conversation 不存在");
        }
        ConversationRecoveryService.Reconciliation reconciliation = recoveryService.reconcile(conversationId, conversation);
        return new RecoveryState(reconciliation.conversation(), reconciliation.history());
    }

    // 活动叶子是待回答用户 Entry 且其 Run 未成功时，新消息只能走重试
    private void ensureNotAwaitingRetry(Conversation conversation, ConversationHistory history) {
        if (conversation.activeLeafEntryId() == null) {
            return;
        }
        Entry leaf = findEntry(history, conversation.activeLeafEntryId());
        if (leaf != null && leaf.type() == EntryType.USER_MESSAGE
                && metadataRepository.latestUnsuccessfulRun(conversation.id(), leaf.id()) != null) {
            throw new ConversationException(
                    ConversationErrorCode.CONVERSATION_AWAITING_RETRY, "上一条消息仍在等待回答，请先重试或另开新对话");
        }
    }

    // 从活动路径投影模型可见消息：只含用户与助手消息，Compaction Entry 不是模型消息
    private static List<AgentMessage> projection(ConversationHistory history, UUID leafEntryId) {
        List<AgentMessage> messages = new ArrayList<>();
        for (Entry entry : history.activePath(leafEntryId)) {
            switch (entry.type()) {
                case USER_MESSAGE -> messages.add(new AgentMessage(
                        AgentMessage.Role.USER, ((UserMessagePayload) entry.payload()).text()));
                case ASSISTANT_MESSAGE -> messages.add(new AgentMessage(
                        AgentMessage.Role.ASSISTANT, ((AssistantMessagePayload) entry.payload()).text()));
                case COMPACTION -> {
                    // 本 Feature 不生成 Compaction Entry；未来压缩后模型上下文从 Compaction 开始构建
                }
            }
        }
        return messages;
    }

    // 硬限制不静默裁剪：超限即拒绝本次发送 / 重试
    private void ensureWithinContextLimit(List<AgentMessage> messages) {
        long chars = messages.stream().mapToLong(message -> message.text().length()).sum();
        if (chars > maxPromptChars) {
            throw new ConversationException(
                    ConversationErrorCode.CONTEXT_LIMIT_REACHED,
                    "模型上下文超过限制，请创建新的 Conversation 继续对话");
        }
    }

    // 推进叶子 / 序号 / 标题并刷新更新时间；压缩索引字段原样保留
    private static Conversation advance(
            Conversation conversation, UUID leafEntryId, long seq, String title, Instant updatedAt
    ) {
        return new Conversation(
                conversation.id(), conversation.workspaceId(), title, conversation.historyFormatVersion(),
                leafEntryId, seq, conversation.latestCompactionEntryId(),
                conversation.latestCompactionSeq(), conversation.latestCompactionByteOffset(),
                conversation.createdAt(), updatedAt);
    }

    // 持久化历史使用 conversation 自己的 TokenUsage，在 Agent 结果边界显式映射
    private static TokenUsage mapUsage(AgentUsage usage) {
        return usage == null ? null : new TokenUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    private static Entry findEntry(ConversationHistory history, UUID entryId) {
        for (Entry entry : history.entries()) {
            if (entry.id().equals(entryId)) {
                return entry;
            }
        }
        return null;
    }

    private record RecoveryState(Conversation conversation, ConversationHistory history) {
    }
}
