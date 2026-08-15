package com.yuyu.salmonmind.conversation.application;

import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentExecutionException.AgentErrorCode;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentStreamSession;
import com.yuyu.salmonmind.agent.api.AgentSummaryRequest;
import com.yuyu.salmonmind.agent.api.AgentSummaryResult;
import com.yuyu.salmonmind.agent.api.AgentSummaryService;
import com.yuyu.salmonmind.agent.api.AgentTitleRequest;
import com.yuyu.salmonmind.agent.api.AgentTitleResult;
import com.yuyu.salmonmind.agent.api.AgentTitleService;
import com.yuyu.salmonmind.agent.api.AgentUsage;
import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CompactionPayload;
import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.ConversationException.ConversationErrorCode;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.Entry.EntryType;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.api.Run.RunStatus;
import com.yuyu.salmonmind.conversation.api.RunStreamListener;
import com.yuyu.salmonmind.conversation.api.TitlePayload;
import com.yuyu.salmonmind.conversation.api.TokenUsage;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import com.yuyu.salmonmind.conversation.application.port.ConversationHistoryRepository;
import com.yuyu.salmonmind.conversation.application.port.ConversationMetadataRepository;
import com.yuyu.salmonmind.conversation.domain.ConversationCompactionPolicy;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import com.yuyu.salmonmind.conversation.domain.ConversationTitle;
import com.yuyu.salmonmind.conversation.domain.TitleTemplate;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 发送 / 重试的完整 Run 协调器，被 {@link ConversationExecutionQueue} 按 Conversation 串行调用。
 *
 * <p>Run 生命周期（对应 Spec 的发送顺序）：
 * 1. 以 JSONL 为权威恢复并校验 Conversation，再追加用户 Entry 并强制刷盘；
 * 2. 数据库事务创建 RUNNING Run 并推进活动叶子（Agent 调用绝不在事务内）；
 * 3. durable 状态成立后发出 run_started；
 * 4. 主 LLM 前压缩检测：usage 锚点 + 本次 User 参与计量，达到 196,712 阈值时生成
 *    摘要、追加 Compaction、推进叶子与压缩三元组并使旧 Checkpoint 失效，随后主调用
 *    从「Summary + Retained Tail + Compaction 后消息」的新投影重建；
 * 5. 流式主回答：delta 只在内存/SSE 中累积，只有模型成功且文本非空才追加一个完整
 *    Assistant Entry；模型成功前失败不写 Assistant；
 * 6. 成功落盘后在同一事务完成 Run 并推进叶子；无 Title Entry 时基于第一次成功交互
 *    尝试生成标题（失败不影响成功 Run）；
 * 7. run_started 之后的一切失败通过 run_failed 收束为唯一终态。
 *
 * <p>上下文投影规则：路径上最后一个 Compaction 之前的内容全部被摘要或 Tail 覆盖；
 * 投影只展开最新 Compaction 的 Summary 与 Retained Tail 原文，再加其后的新消息。
 * 每次主调用前期望 Checkpoint 叶子 = 活动叶子（Compaction 时强制重建，否则复用
 * 到叶子之前的上下文节点），使压缩后的旧 Checkpoint 在结构上失效。
 */
@Component
class ConversationRunCoordinator {

    /** Summary 进入模型上下文的前缀消息；摘要是历史事实整理，不是用户新指令。 */
    private static final String SUMMARY_PREFIX = "以下为此前对话的结构化摘要，请基于它继续对话：\n";

    private final WorkspaceRegistry workspaceRegistry;
    private final ConversationRecoveryService recoveryService;
    private final ConversationHistoryRepository historyRepository;
    private final ConversationMetadataRepository metadataRepository;
    private final AgentStreamSession agentStream;
    private final AgentSummaryService summaryService;
    private final AgentTitleService titleService;
    private final TransactionTemplate transactionTemplate;
    private final ConversationCompactionPolicy compactionPolicy;
    private final ConversationCompactionPolicy.Budgets budgets;
    private final ConversationCompactionPolicy.TokenEstimator estimator;
    private final long systemPromptTokens;

    ConversationRunCoordinator(
            WorkspaceRegistry workspaceRegistry,
            ConversationRecoveryService recoveryService,
            ConversationHistoryRepository historyRepository,
            ConversationMetadataRepository metadataRepository,
            AgentStreamSession agentStream,
            AgentSummaryService summaryService,
            AgentTitleService titleService,
            TransactionTemplate transactionTemplate,
            @Value("${salmon.compaction.physical-window:1000000}") long physicalWindow,
            @Value("${salmon.compaction.working-window:262144}") long workingWindow,
            @Value("${salmon.compaction.output-reserve:65432}") long outputReserve,
            @Value("${salmon.compaction.retained-tail-target:65536}") long retainedTailTarget,
            @Value("${salmon.compaction.summary-max-output-tokens:32768}") long summaryMaxOutputTokens,
            @Value("${salmon.compaction.summary-temperature:0.1}") double summaryTemperature,
            @Value("${salmon.compaction.system-prompt-tokens:256}") long systemPromptTokens
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.recoveryService = recoveryService;
        this.historyRepository = historyRepository;
        this.metadataRepository = metadataRepository;
        this.agentStream = agentStream;
        this.summaryService = summaryService;
        this.titleService = titleService;
        this.transactionTemplate = transactionTemplate;
        this.compactionPolicy = new ConversationCompactionPolicy();
        this.budgets = new ConversationCompactionPolicy.Budgets(
                physicalWindow, workingWindow, outputReserve, retainedTailTarget,
                summaryMaxOutputTokens, summaryTemperature);
        // 保守 UTF-8 估算：1 token 至少对应 2 字节；英文/代码高估（安全），
        // 中文 3-6 字节/字也不低估；usage 锚点存在时本估算不参与计量
        this.estimator = text -> {
            long bytes = text.getBytes(StandardCharsets.UTF_8).length;
            return Math.max(1L, bytes / 2);
        };
        this.systemPromptTokens = systemPromptTokens;
    }

    void send(UUID conversationId, String text, RunStreamListener listener) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        RecoveryState state = recover(conversationId);// 确认 Conversation 属于当前 Workspace，并以 JSONL 修复数据库索引
        Conversation conversation = state.conversation();
        ConversationHistory history = state.history();

        // 活动叶子仍是待回答用户 Entry 时不能发送新消息，只能重试
        ensureNotAwaitingRetry(conversation, history);

        // 预分配 Run / 用户 Entry / 回答 Entry ID，保证 JSONL 先写而数据库未写时仍可恢复
        UUID runId = UUID.randomUUID();
        UUID userEntryId = UUID.randomUUID();
        UUID answerEntryId = UUID.randomUUID();
        long userSeq = conversation.lastConfirmedSeq() + 1;
        Instant now = Instant.now();
        Entry userEntry = new Entry(
                ConversationHistory.FORMAT_VERSION, conversationId, userEntryId, userSeq,
                conversation.activeLeafEntryId(), EntryType.USER_MESSAGE, now,
                new UserMessagePayload(text, runId));
        historyRepository.append(conversationId, userEntry);

        Conversation advanced = advance(conversation, userEntry.id(), userSeq, conversation.title(), now);
        Run running = new Run(runId, conversationId, userEntryId, RunStatus.RUNNING, null, now, null);
        // 创建 RUNNING Run 与推进叶子必须在同一事务：数据库状态不能出现 Run 领先叶子或反向
        transactionTemplate.executeWithoutResult(status -> {
            metadataRepository.insertRun(running);
            metadataRepository.update(advanced);
        });

        // durable 状态成立后才能开始流；之前的前置错误以异常抛出（HTTP 层映射 JSON）
        listener.onRunStarted(new RunStreamListener.RunStarted(conversationId, running, userEntry, false));
        execute(listener, conversationId, advanced, running, userEntry, answerEntryId);
    }

    void retry(UUID conversationId, UUID runId, RunStreamListener listener) {
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
        if (previous.status() == RunStatus.SUCCEEDED) {
            throw new ConversationException(
                    ConversationErrorCode.CONVERSATION_AWAITING_RETRY, "该消息已得到回答，不能重试");
        }

        // 只有当前路径上仍无成功回答才可重试：触发 User 必须在路径上，活动叶子可以是
        // 该 User（未压缩）或旧 Run 追加的 Compaction（已压缩但无成功 Assistant）
        UUID leafId = conversation.activeLeafEntryId();
        List<Entry> path = leafId == null ? List.of() : history.activePath(leafId);
        Entry leaf = path.isEmpty() ? null : path.get(path.size() - 1);
        if (leaf == null || leaf.type() == EntryType.ASSISTANT_MESSAGE
                || path.stream().noneMatch(e -> e.id().equals(previous.triggerEntryId()))) {
            throw new ConversationException(
                    ConversationErrorCode.CONVERSATION_AWAITING_RETRY, "该消息已得到回答，不能重试");
        }
        Entry trigger = path.stream()
                .filter(e -> e.id().equals(previous.triggerEntryId()))
                .findFirst()
                .orElse(null);
        if (trigger == null || trigger.type() != EntryType.USER_MESSAGE) {
            throw new ConversationException(
                    ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED, "重试触发的用户 Entry 不存在");
        }

        // 重试复用原用户 Entry，只为同一触发 Entry 创建新的 Run 记录
        UUID newRunId = UUID.randomUUID();
        UUID answerEntryId = UUID.randomUUID();
        Instant now = Instant.now();
        Run running = new Run(newRunId, conversationId, trigger.id(), RunStatus.RUNNING, null, now, null);
        transactionTemplate.executeWithoutResult(status -> metadataRepository.insertRun(running));

        listener.onRunStarted(new RunStreamListener.RunStarted(conversationId, running, trigger, true));
        execute(listener, conversationId, conversation, running, trigger, answerEntryId);
    }

    // 一次 Run 的完整执行：预压缩 → 流式主调用（overflow 可强制压缩重试一次）→ 成功落盘；
    // run_started 之后的任何失败都收束为 onRunFailed，不再向 HTTP 层抛异常
    private void execute(
            RunStreamListener listener, UUID conversationId, Conversation conversation,
            Run running, Entry triggerEntry, UUID answerEntryId
    ) {
        Conversation current = conversation;
        try {
            ConversationHistory history = historyRepository.read(conversationId);
            CompactionOutcome compaction = compactBeforeMain(listener, current, history, running, false);
            if (compaction.compacted()) {
                current = compaction.conversation();
                history = historyRepository.read(conversationId);
            }
            MainOutcome outcome = streamMain(
                    listener, conversationId, current, history, running, answerEntryId, compaction.compacted());
            finishSuccess(listener, conversationId, outcome, running);
        } catch (ConversationException ex) {
            failRun(listener, current, running, ex.code().name(), ex.getMessage());
        } catch (AgentExecutionException ex) {
            failRun(listener, current, running, ex.code().name(), ex.getMessage());
        } catch (RuntimeException ex) {
            failRun(listener, current, running, "INTERNAL_ERROR", "服务器内部错误");
        }
    }

    /**
     * 发送前（或 overflow 重试时）的压缩检测与执行。force=true 时不检查估算直接压缩，
     * 用于提供方明确上下文溢出且尚未输出 delta 的恢复路径；估算驱动路径只在实际
     * 达到阈值时压缩。压缩步骤：计划 Tail 切分 → 摘要调用 → 追加 Compaction →
     * 更新压缩三元组 → 压缩后重计量。
     */
    private CompactionOutcome compactBeforeMain(
            RunStreamListener listener, Conversation conversation, ConversationHistory history,
            Run running, boolean force
    ) {
        UUID leafId = conversation.activeLeafEntryId();
        List<Entry> path = history.activePath(leafId);
        if (path.isEmpty()) {
            return new CompactionOutcome(false, conversation, 0);
        }
        int lastCompactionIndex = -1;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).type() == EntryType.COMPACTION) {
                lastCompactionIndex = i;
            }
        }
        // previousSummary 只来自当前 Active Path 上的最新 Compaction；分支上的摘要不属于本投影
        String previousSummary = lastCompactionIndex >= 0
                ? ((CompactionPayload) path.get(lastCompactionIndex).payload()).summary() : null;

        // usage 锚点：最新 Compaction 之后的最近 Assistant，且 totalTokens 非空；
        // 锚点已包含 system prompt 与既有输入，后续内容用保守估算补足
        Long anchorTokens = null;
        int anchorIndex = -1;
        for (int i = path.size() - 1; i > lastCompactionIndex; i--) {
            Entry entry = path.get(i);
            if (entry.type() == EntryType.ASSISTANT_MESSAGE) {
                TokenUsage usage = ((AssistantMessagePayload) entry.payload()).usage();
                if (usage != null && usage.totalTokens() != null) {
                    anchorTokens = usage.totalTokens();
                    anchorIndex = i;
                    break;
                }
            }
        }

        long estimated;
        List<Entry> candidate = new ArrayList<>();
        if (anchorTokens != null) {
            // 锚点之后的模型消息（含本次 User）逐条估算；既有内容全部由锚点承担
            List<Entry> afterAnchor = new ArrayList<>();
            for (int i = anchorIndex + 1; i < path.size(); i++) {
                Entry entry = path.get(i);
                if (entry.type() == EntryType.USER_MESSAGE || entry.type() == EntryType.ASSISTANT_MESSAGE) {
                    afterAnchor.add(entry);
                }
            }
            estimated = compactionPolicy.estimateNextInputTokens(
                    budgets, estimator, systemPromptTokens, previousSummary, List.of(), anchorTokens, afterAnchor);
        } else {
            List<Entry> projected = new ArrayList<>();
            for (Entry entry : path) {
                if (entry.type() == EntryType.USER_MESSAGE || entry.type() == EntryType.ASSISTANT_MESSAGE) {
                    projected.add(entry);
                }
            }
            estimated = compactionPolicy.estimateNextInputTokens(
                    budgets, estimator, systemPromptTokens, previousSummary, projected, null, List.of());
        }

        if (!force && !compactionPolicy.shouldCompact(estimated, budgets)) {
            return new CompactionOutcome(false, conversation, estimated);
        }

        // 候选区：无旧压缩时是全部模型消息；有旧压缩时是旧 retainedTail + 其后的新消息
        if (lastCompactionIndex >= 0) {
            candidate.addAll(((CompactionPayload) path.get(lastCompactionIndex).payload()).retainedTail());
            for (int i = lastCompactionIndex + 1; i < path.size(); i++) {
                Entry entry = path.get(i);
                if (entry.type() == EntryType.USER_MESSAGE || entry.type() == EntryType.ASSISTANT_MESSAGE) {
                    candidate.add(entry);
                }
            }
        } else {
            for (Entry entry : path) {
                if (entry.type() == EntryType.USER_MESSAGE || entry.type() == EntryType.ASSISTANT_MESSAGE) {
                    candidate.add(entry);
                }
            }
        }

        ConversationCompactionPolicy.CompactionPlan plan =
                compactionPolicy.planCompaction(budgets, estimator, previousSummary, candidate);
        if (plan.summarizedEntries().isEmpty()) {
            // 候选区全部保留在 Tail（如单条超大交互）：没有可压缩内容，无法恢复
            return new CompactionOutcome(false, conversation, estimated);
        }

        // 摘要调用：失败、空白或结构非法统一映射为 COMPACTION_FAILED（User 与 Run 保留可重试）
        String summary;
        TokenUsage summaryUsage = null;
        try {
            AgentSummaryResult result = summaryService.summarize(new AgentSummaryRequest(List.of(
                    new AgentMessage(AgentMessage.Role.USER, plan.summarizeInput()))));
            summary = result.summary();
            summaryUsage = mapUsage(result.usage());
        } catch (AgentExecutionException ex) {
            throw new ConversationException(
                    ConversationErrorCode.COMPACTION_FAILED, "摘要生成失败", ex);
        }
        if (!compactionPolicy.isValidSummary(summary)) {
            throw new ConversationException(
                    ConversationErrorCode.COMPACTION_FAILED, "摘要结果结构不合法");
        }

        long seq = conversation.lastConfirmedSeq() + 1;
        Instant now = Instant.now();
        Entry compactionEntry = new Entry(
                ConversationHistory.FORMAT_VERSION, conversation.id(), UUID.randomUUID(), seq,
                leafId, EntryType.COMPACTION, now,
                new CompactionPayload(summary, plan.coveredThroughEntryId(), plan.retainedTail(),
                        estimated, summaryUsage));
        historyRepository.append(conversation.id(), compactionEntry);

        // 刷新快照取得新 Compaction 的字节偏移；Compaction 推进活动叶子与确认序号，
        // 压缩三元组与叶子在同一事务更新，旧 Redis Checkpoint 因叶子变化在下次主调用时失效
        ConversationHistory fresh = historyRepository.read(conversation.id());
        Long offset = fresh.byteOffsetOf(compactionEntry);
        Conversation compacted = new Conversation(
                conversation.id(), conversation.workspaceId(), conversation.title(),
                conversation.historyFormatVersion(), compactionEntry.id(), seq,
                compactionEntry.id(), compactionEntry.seq(), offset, conversation.createdAt(), now);
        transactionTemplate.executeWithoutResult(status -> metadataRepository.update(compacted));

        // 压缩后重计量：Summary + Retained Tail + Compaction 后消息；仍超限则明确失败
        long reestimated = compactionPolicy.reestimateAfterCompaction(
                budgets, estimator, systemPromptTokens, summary, plan.retainedTail(), List.of());
        if (compactionPolicy.shouldCompact(reestimated, budgets)) {
            throw new ConversationException(
                    ConversationErrorCode.CONTEXT_LIMIT_REACHED, "压缩后仍超过工作输入预算，请创建新的 Conversation");
        }

        listener.onCompactionCompleted(new RunStreamListener.CompactionCompleted(
                conversation.id(), compactionEntry, compacted));
        return new CompactionOutcome(true, compacted, reestimated);
    }

    /**
     * 流式主调用。提供方在输出任何 delta 前明确 CONTEXT_OVERFLOW 且本 Run 尚未压缩时，
     * 强制压缩一次并重试；已输出 delta、已完成压缩或第二次溢出则直接失败，
     * 不循环压缩也不清空已展示内容。
     */
    private MainOutcome streamMain(
            RunStreamListener listener, UUID conversationId, Conversation conversation,
            ConversationHistory history, Run running, UUID answerEntryId, boolean alreadyCompacted
    ) {
        List<Entry> path = history.activePath(conversation.activeLeafEntryId());
        // 获取需要被压缩的全部对话
        List<AgentMessage> projection = project(path);
        AgentRequest request = new AgentRequest(
                conversationId.toString(), expectedCheckpointLeaf(path), answerEntryId, projection);

        boolean[] deltaSeen = {false};
        AgentResult[] success = {null};
        AgentExecutionException[] failure = {null};
        agentStream.stream(request, new AgentStreamListener() {
            @Override
            public void onDelta(String delta) {
                // delta 只用于临时显示：不写 JSONL，也不参与最终结果拼接
                deltaSeen[0] = true;
                listener.onAssistantDelta(new RunStreamListener.AssistantDelta(running.id(), delta));
            }

            @Override
            public void onComplete(AgentResult result) {
                success[0] = result;
            }

            @Override
            public void onError(AgentExecutionException error) {
                failure[0] = error;
            }
        });

        if (failure[0] != null) {
            AgentExecutionException error = failure[0];
            if (error.code() == AgentErrorCode.CONTEXT_OVERFLOW && !deltaSeen[0] && !alreadyCompacted) {
                // 唯一一次流内压缩机会：强制压缩后从新投影重建并重试一次
                CompactionOutcome retried = compactBeforeMain(listener, conversation, history, running, true);
                if (!retried.compacted()) {
                    throw new ConversationException(
                            ConversationErrorCode.CONTEXT_LIMIT_REACHED, "模型上下文溢出，且没有可压缩内容");
                }
                return streamMain(listener, conversationId, retried.conversation(),
                        historyRepository.read(conversationId), running, answerEntryId, true);
            }
            if (error.code() == AgentErrorCode.CONTEXT_OVERFLOW) {
                throw new ConversationException(
                        ConversationErrorCode.CONTEXT_LIMIT_REACHED, "模型上下文溢出，已用尽压缩机会");
            }
            throw error;
        }
        AgentResult result = success[0];
        if (result == null) {
            throw new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "流式调用未完成");
        }
        return new MainOutcome(result, conversation);
    }

    // 成功路径：先追加完整 Assistant Entry，再在同一数据库事务完成 Run 与推进叶子；
    // delta 不形成历史，只有最终完整文本落盘一次
    private void finishSuccess(
            RunStreamListener listener, UUID conversationId, MainOutcome outcome, Run running
    ) {
        AgentResult result = outcome.result();
        if (result.text() == null || result.text().isBlank()) {
            // 空回答视为模型失败：不追加空 Assistant Entry，由调用方完成 FAILED Run 后可重试
            throw new AgentExecutionException(
                    AgentErrorCode.CHAT_MODEL_FAILED, "模型返回了空回答");
        }
        long answerSeq = outcome.conversation().lastConfirmedSeq() + 1;
        Instant answeredAt = Instant.now();
        // parent 是本 Run 最新上下文叶子（User 或 Compaction），Run trigger 仍是原 User
        Entry assistantEntry = new Entry(
                ConversationHistory.FORMAT_VERSION, conversationId, UUID.randomUUID(), answerSeq,
                outcome.conversation().activeLeafEntryId(), EntryType.ASSISTANT_MESSAGE, answeredAt,
                new AssistantMessagePayload(
                        result.text(), running.id(), result.provider(), result.model(), mapUsage(result.usage())));
        historyRepository.append(conversationId, assistantEntry);

        Run finished = new Run(
                running.id(), conversationId, running.triggerEntryId(), RunStatus.SUCCEEDED, null,
                running.startedAt(), answeredAt);
        Conversation finalConversation = advance(outcome.conversation(), assistantEntry.id(), answerSeq,
                outcome.conversation().title(), answeredAt);
        transactionTemplate.executeWithoutResult(status -> {
            metadataRepository.updateRun(finished);
            metadataRepository.update(finalConversation);
        });
        listener.onAssistantCompleted(new RunStreamListener.AssistantCompleted(conversationId, assistantEntry));
        // 首次标题在成功终态事件之前：失败不影响已成功的主 Run
        maybeGenerateTitle(listener, conversationId, finalConversation, assistantEntry, running);
        listener.onRunCompleted(new RunStreamListener.RunCompleted(
                conversationId, finished, finalConversation));
    }

    // 首次标题：无 Title Entry 时基于第一次成功的 User/Assistant 交互生成；模型调用
    // 独立于 ReactAgent Checkpoint，标题失败保留默认标题且不影响成功 Run
    private void maybeGenerateTitle(
            RunStreamListener listener, UUID conversationId, Conversation conversation,
            Entry assistantEntry, Run running
    ) {
        Entry titleEntry = null;
        String normalized = null;
        try {
            List<Entry> path = historyRepository.read(conversationId).activePath(conversation.activeLeafEntryId());
            if (path.stream().anyMatch(e -> e.type() == EntryType.TITLE)) {
                return;
            }
            Entry firstUser = null;
            Entry firstAssistant = null;
            for (Entry entry : path) {
                if (firstUser == null && entry.type() == EntryType.USER_MESSAGE) {
                    firstUser = entry;
                } else if (firstUser != null && entry.type() == EntryType.ASSISTANT_MESSAGE
                        && firstUser.id().equals(entry.parentId())) {
                    firstAssistant = entry;
                    break;
                }
            }
            if (firstUser == null || firstAssistant == null) {
                return;
            }
            AgentTitleResult result = titleService.generateTitle(new AgentTitleRequest(List.of(
                    new AgentMessage(AgentMessage.Role.USER, TitleTemplate.render(
                            ((UserMessagePayload) firstUser.payload()).text(),
                            ((AssistantMessagePayload) firstAssistant.payload()).text())))));
            if (result.title() == null || result.title().isBlank()) {
                return;
            }
            normalized = ConversationTitle.normalize(result.title());
            if (normalized.isBlank()) {
                return;
            }
            long seq = conversation.lastConfirmedSeq() + 1;
            titleEntry = new Entry(
                    ConversationHistory.FORMAT_VERSION, conversationId, UUID.randomUUID(), seq,
                    assistantEntry.id(), EntryType.TITLE, Instant.now(),
                    new TitlePayload(normalized, running.id(), assistantEntry.id(),
                            result.provider(), result.model()));
            historyRepository.append(conversationId, titleEntry);
            // Title 只推进 seq 与标题，不推进活动叶子：模型上下文与 Checkpoint 保持 Assistant
            Conversation withTitle = new Conversation(
                    conversation.id(), conversation.workspaceId(), normalized,
                    conversation.historyFormatVersion(), conversation.activeLeafEntryId(), seq,
                    conversation.latestCompactionEntryId(), conversation.latestCompactionSeq(),
                    conversation.latestCompactionByteOffset(), conversation.createdAt(), Instant.now());
            transactionTemplate.executeWithoutResult(status -> metadataRepository.update(withTitle));
        } catch (RuntimeException ex) {
            // 标题失败或标题落盘异常不回滚已成功的 Assistant 或 Run：保留默认标题，
            // 下一次成功 Run 再尝试；Title Entry 已写而数据库未更新时由恢复流程修复
            return;
        }
        if (titleEntry != null) {
            listener.onTitleUpdated(new RunStreamListener.TitleUpdated(
                    conversationId, titleEntry, normalized));
        }
    }

    // 失败路径：不追加 Assistant Entry，只完成失败 Run；活动叶子保持 User 或本 Run 的 Compaction
    private void failRun(
            RunStreamListener listener, Conversation conversation, Run running, String code, String message
    ) {
        Run failed = new Run(
                running.id(), running.conversationId(), running.triggerEntryId(),
                RunStatus.FAILED, code, running.startedAt(), Instant.now());
        try {
            metadataRepository.updateRun(failed);
        } catch (RuntimeException ex) {
            // 数据库更新失败留给下一次恢复处理：JSONL 未变，Run 会在下次打开时转为 INTERRUPTED
        }
        listener.onRunFailed(new RunStreamListener.RunFailed(
                conversation.id(), code, message, failed, conversation));
    }

    /**
     * 从 Active Path 投影模型可见消息：只展开路径上最后一个 Compaction 的
     * Summary（前缀消息）与 Retained Tail 原文，再加其后的新消息；
     * 被摘要覆盖的历史不再出现。
     */
    private static List<AgentMessage> project(List<Entry> path) {
        int lastCompactionIndex = -1;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).type() == EntryType.COMPACTION) {
                lastCompactionIndex = i;
            }
        }
        List<AgentMessage> messages = new ArrayList<>();
        if (lastCompactionIndex >= 0) {
            CompactionPayload payload = (CompactionPayload) path.get(lastCompactionIndex).payload();
            messages.add(new AgentMessage(AgentMessage.Role.USER, SUMMARY_PREFIX + payload.summary()));
            // retainedTail 是压缩后保留的尾节点，用于后续的恢复重建
            // 避免再次重新按照 ID 重建整个路径，而是只重建被压缩的部分
            for (Entry tail : payload.retainedTail()) {
                messages.add(messageOf(tail));
            }
            for (int i = lastCompactionIndex + 1; i < path.size(); i++) {
                Entry entry = path.get(i);
                if (entry.type() == EntryType.USER_MESSAGE || entry.type() == EntryType.ASSISTANT_MESSAGE) {
                    messages.add(messageOf(entry));
                }
            }
            return messages;
        }
        for (Entry entry : path) {
            if (entry.type() == EntryType.USER_MESSAGE || entry.type() == EntryType.ASSISTANT_MESSAGE) {
                messages.add(messageOf(entry));
            }
        }
        return messages;
    }

    // 期望 Checkpoint 叶子：活动叶子是 Compaction 时期望从新投影重建（旧标记必然不匹配）；
    // 否则期望复用覆盖到追加前叶子（即叶子 parent）的既有 Checkpoint
    private static UUID expectedCheckpointLeaf(List<Entry> path) {
        Entry leaf = path.get(path.size() - 1);
        return leaf.type() == EntryType.COMPACTION ? leaf.id() : leaf.parentId();
    }

    private static AgentMessage messageOf(Entry entry) {
        return switch (entry.type()) {
            case USER_MESSAGE -> new AgentMessage(
                    AgentMessage.Role.USER, ((UserMessagePayload) entry.payload()).text());
            case ASSISTANT_MESSAGE -> new AgentMessage(
                    AgentMessage.Role.ASSISTANT, ((AssistantMessagePayload) entry.payload()).text());
            default -> throw new IllegalArgumentException("投影只能包含用户与助手消息: " + entry.id());
        };
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

    private record CompactionOutcome(boolean compacted, Conversation conversation, long estimatedTokens) {
    }

    private record MainOutcome(AgentResult result, Conversation conversation) {
    }
}
