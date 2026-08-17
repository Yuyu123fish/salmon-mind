package com.yuyu.salmonmind.conversation.application;

import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentExecutionException.AgentErrorCode;
import com.yuyu.salmonmind.agent.api.AgentCitation;
import com.yuyu.salmonmind.agent.api.AgentLocalCitation;
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
import com.yuyu.salmonmind.agent.api.AgentWebCitation;
import com.yuyu.salmonmind.agent.api.CheckpointPolicy;
import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CompactionPayload;
import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.ConversationException.ConversationErrorCode;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.Entry.EntryType;
import com.yuyu.salmonmind.conversation.api.CitationPayload;
import com.yuyu.salmonmind.conversation.api.LocalCitationPayload;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.api.Run.RunStatus;
import com.yuyu.salmonmind.conversation.api.RunStreamListener;
import com.yuyu.salmonmind.conversation.api.TitlePayload;
import com.yuyu.salmonmind.conversation.api.TokenUsage;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import com.yuyu.salmonmind.conversation.api.WebCitationPayload;
import com.yuyu.salmonmind.conversation.application.port.ConversationHistoryRepository;
import com.yuyu.salmonmind.conversation.application.port.ConversationMetadataRepository;
import com.yuyu.salmonmind.conversation.domain.ConversationCompactionPolicy;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import com.yuyu.salmonmind.conversation.domain.ConversationTitle;
import com.yuyu.salmonmind.conversation.domain.TitleTemplate;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 6. 成功提交点：Assistant JSONL 刷盘后，在同一数据库事务把 Run 更新为 SUCCEEDED
 *    并推进活动叶子，随后独立尝试标题持久化。事务提交后业务成功不可降级；
 * 7. 传输阶段：按序尽力发送 assistant_completed → 可选 title_updated → run_completed，
 *    写出失败只结束当前连接，绝不把 SUCCEEDED 降级为 FAILED；客户端刷新后从
 *    JSONL / 数据库读取权威成功状态；
 * 8. run_started 之后、成功提交点之前的一切失败通过 run_failed 收束为唯一终态。
 *
 * <p>上下文投影规则：路径上最后一个 Compaction 之前的内容全部被摘要或 Tail 覆盖；
 * 投影只展开最新 Compaction 的 Summary 与 Retained Tail 原文，再加其后的新消息。
 * 每次主调用前期望 Checkpoint 叶子 = 活动叶子（Compaction 时强制重建，否则复用
 * 到叶子之前的上下文节点），使压缩后的旧 Checkpoint 在结构上失效。
 */
@Component
class ConversationRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ConversationRunCoordinator.class);

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

    /**
     * 发送一条新用户消息并启动 Run。调用方必须已持有本 Conversation 的执行队列锁。
     *
     * <p>顺序：先以 JSONL 为权威恢复并校验 Workspace 归属，确认活动叶子不在待重试状态；
     * 预分配 Run / 用户 Entry / 回答 Entry ID 后，先把 User Entry 强制刷入 JSONL，再在同一
     * 数据库事务中创建 RUNNING Run 并推进活动叶子。JSONL 先写而数据库未写时，下次打开会按
     * JSONL 修复索引；Run 与叶子必须同事务提交，避免数据库出现 Run 领先叶子或反向。
     *
     * <p>durable 状态成立后才发出 {@code run_started} 并进入 {@link #execute}。此前的前置
     * 错误（Conversation 不存在、历史损坏、消息为空、待重试）以异常抛出，由 HTTP 层映射
     * JSON；此后失败由 {@link #execute} 收束为 {@code run_failed}。
     */
    void send(UUID conversationId, String text, RunStreamListener listener) {
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

    /**
     * 重试一条失败或中断的 Run。调用方必须已持有本 Conversation 的执行队列锁。
     *
     * <p>复用原用户 Entry，不追加重复用户消息，也不推进活动叶子；只为同一触发 Entry
     * 插入一条新的 RUNNING Run。旧 Run 不存在或不属于本 Conversation、仍为 RUNNING、
     * 或已 SUCCEEDED 时拒绝。可重试时触发 User 必须仍在当前 Active Path 上，且活动叶子
     * 还不是 Assistant——叶子可以是该 User（未压缩）或旧 Run 已追加的 Compaction（已压缩
     * 但无成功回答），不得因叶子不再是 User 而拒绝。
     *
     * <p>数据库只写入新 Run，叶子权威仍是上次 send / 压缩留下的状态。durable 之后发出
     * {@code run_started}（{@code isRetry=true}）并进入 {@link #execute}；此前错误以异常
     * 抛出，此后失败同样收束为 {@code run_failed}。
     */
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

    /**
     * 一次 Run 在 {@code run_started} 之后的执行体：预压缩 → 流式主调用（提供方 overflow
     * 且尚未输出 delta 时可强制压缩并重试一次）→ 成功落盘。
     *
     * <p>{@link #send} / {@link #retry} 已把 RUNNING Run 写成 durable 状态，本方法不再向
     * HTTP 层抛异常。压缩会推进活动叶子，因此局部 {@code current} 必须跟着更新，失败时
     * {@link #failRun} 才能报告正确叶子（User 或本 Run 的 Compaction）。成功路径由
     * {@link #finishSuccess} 先写完整 Assistant JSONL，再同事务完成 Run 与推进叶子；
     * 任何 Conversation / Agent / 运行时失败都收束为 {@code run_failed}，不追加 Assistant。
     */
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
            finishSuccess(listener, conversationId, outcome, running, answerEntryId);
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
        List<AgentMessage> projection = project(path);
        CheckpointPolicy checkpointPolicy = agentStream.requiresProjectionRebuild()
                ? CheckpointPolicy.REBUILD_FROM_PROJECTION : CheckpointPolicy.REUSE_IF_MATCH;
        AgentRequest request = new AgentRequest(
                conversationId.toString(), expectedCheckpointLeaf(path), answerEntryId, projection,
                checkpointPolicy);

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

            @Override
            public void onToolStarted(com.yuyu.salmonmind.agent.api.AgentToolStarted event) {
                listener.onToolStarted(new RunStreamListener.ToolStarted(
                        running.id(), event.toolCallId(), event.toolName()));
            }

            @Override
            public void onToolCompleted(com.yuyu.salmonmind.agent.api.AgentToolCompleted event) {
                listener.onToolCompleted(new RunStreamListener.ToolCompleted(
                        running.id(), event.toolCallId(), event.toolName(), event.durationMillis(),
                        event.provider(), event.sourceCount(), event.truncated(), event.degraded()));
            }

            @Override
            public void onToolFailed(com.yuyu.salmonmind.agent.api.AgentToolFailed event) {
                listener.onToolFailed(new RunStreamListener.ToolFailed(
                        running.id(), event.toolCallId(), event.toolName(), event.durationMillis(),
                        event.stableErrorCode()));
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

    /**
     * 成功路径：先把完整 Assistant Entry 刷入 JSONL，再在同一数据库事务完成 SUCCEEDED Run
     * 并推进活动叶子。delta 不形成历史，只有最终完整文本落盘一次。
     *
     * <p>Assistant Entry 必须使用预分配的 {@code answerEntryId}：Adapter 成功后会把它写回
     * Checkpoint 叶子标记，下一轮 {@link #expectedCheckpointLeaf}（活动叶子的 parent）
     * 与之相等才能复用；换成随机 ID 会导致复用永不成立。空回答视为模型失败，不追加空
     * Assistant，由 {@link #execute} 走失败路径后可重试。
     *
     * <p>本方法只负责「业务完成」：成功提交点就是下面的数据库事务提交。事务提交后
     * Run 已是不可降级的 SUCCEEDED，成功事件写出交给 {@link #sendSuccessEvents}，
     * 传输失败绝不回头调用 {@link #failRun}。
     */
    private void finishSuccess(
            RunStreamListener listener, UUID conversationId, MainOutcome outcome, Run running, UUID answerEntryId
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
                ConversationHistory.FORMAT_VERSION, conversationId, answerEntryId, answerSeq,
                outcome.conversation().activeLeafEntryId(), EntryType.ASSISTANT_MESSAGE, answeredAt,
                new AssistantMessagePayload(
                        result.text(), running.id(), result.provider(), result.model(), mapUsage(result.usage()),
                        mapCitations(result.citations())));
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

        // 标题持久化也属于业务侧（失败不影响已成功的主 Run），成功则产出待发送事件
        RunStreamListener.TitleUpdated titleEvent = maybeGenerateTitle(
                conversationId, finalConversation, assistantEntry, running);
        sendSuccessEvents(listener, conversationId, finished, finalConversation, assistantEntry, titleEvent);
    }

    /**
     * 传输阶段：成功提交点之后，按对外顺序尽力写出成功事件
     * {@code assistant_completed → 可选 title_updated → run_completed}。
     *
     * <p>这里的任何异常都只可能是 Listener/SSE 写出失败：Run 与 Conversation 已在该点
     * 之前提交为 SUCCEEDED，传输中断只是结束当前连接，绝不调用 {@link #failRun} 降级。
     * 客户端重新打开 Conversation 会读取 JSONL 与数据库的权威成功状态；某次写出失败后
     * 停止继续发送后续事件，但不回滚任何业务状态。此 catch 不得覆盖成功提交点之前的
     * 业务异常——那些异常仍由 {@link #execute} 走失败路径。
     */
    private void sendSuccessEvents(
            RunStreamListener listener, UUID conversationId, Run finished, Conversation finalConversation,
            Entry assistantEntry, RunStreamListener.TitleUpdated titleEvent
    ) {
        try {
            listener.onAssistantCompleted(new RunStreamListener.AssistantCompleted(conversationId, assistantEntry));
            if (titleEvent != null) {
                listener.onTitleUpdated(titleEvent);
            }
            listener.onRunCompleted(new RunStreamListener.RunCompleted(
                    conversationId, finished, finalConversation));
        } catch (RuntimeException ex) {
            // 已越过成功提交点：只记录传输中断，业务状态保持 SUCCEEDED
            log.warn("成功事件传输中断（conversation={}）：只结束当前连接，业务状态保持 SUCCEEDED",
                    conversationId, ex);
        }
    }

    /**
     * 首次成功交互后尝试生成标题并落盘，成功时返回待发送的 TitleUpdated 事件，否则返回
     * {@code null}。路径上已有 Title Entry 时直接返回 null；模型调用独立于 ReactAgent
     * Checkpoint，失败保留默认标题且不回滚已成功的 Assistant 或 Run。
     *
     * <p>Title 只推进确认序号与 Conversation 标题，不推进活动叶子，因此模型上下文与
     * Checkpoint 仍停在 Assistant。Title Entry 已写入 JSONL 而数据库未更新时，由下次
     * 打开的恢复流程修复。事件发送由调用方在传输阶段统一执行，本方法不做传输。
     */
    private RunStreamListener.TitleUpdated maybeGenerateTitle(
            UUID conversationId, Conversation conversation, Entry assistantEntry, Run running
    ) {
        Entry titleEntry = null;
        String normalized = null;
        try {
            List<Entry> path = historyRepository.read(conversationId).activePath(conversation.activeLeafEntryId());
            if (path.stream().anyMatch(e -> e.type() == EntryType.TITLE)) {
                return null;
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
                return null;
            }
            AgentTitleResult result = titleService.generateTitle(new AgentTitleRequest(List.of(
                    new AgentMessage(AgentMessage.Role.USER, TitleTemplate.render(
                            ((UserMessagePayload) firstUser.payload()).text(),
                            ((AssistantMessagePayload) firstAssistant.payload()).text())))));
            if (result.title() == null || result.title().isBlank()) {
                return null;
            }
            normalized = ConversationTitle.normalize(result.title());
            if (normalized.isBlank()) {
                return null;
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
            return null;
        }
        return titleEntry == null ? null : new RunStreamListener.TitleUpdated(conversationId, titleEntry, normalized);
    }

    /**
     * 失败路径：不追加 Assistant Entry，只把 Run 标为 FAILED；活动叶子保持 User 或本 Run
     * 已追加的 Compaction，因此用户只能重试而不能发送新消息。
     *
     * <p>数据库更新失败不向外抛：JSONL 未变，下次打开会把残留 RUNNING 转为 INTERRUPTED。
     * 无论数据库是否更新成功，都发出 {@code run_failed} 作为本流的唯一终态。
     */
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

    /**
     * 计算本次主调用期望的 Checkpoint 叶子。活动叶子是 Compaction 时返回该 Compaction
     * 自身，迫使旧标记不匹配并从新投影重建；否则返回叶子 parent，期望复用覆盖到
     * 「追加当前叶子之前」的既有 Checkpoint。
     */
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

    /**
     * 发送 / 重试的统一前置：确认 Conversation 属于当前 Workspace，再以 JSONL 为权威
     * 修复数据库索引。打开路径由 {@link ConversationApplicationService} 自行调用同一套
     * 恢复，不经过本协调器。
     */
    private RecoveryState recover(UUID conversationId) {
        UUID workspaceId = workspaceRegistry.current().id();
        Conversation conversation = metadataRepository.findById(conversationId);
        if (conversation == null || !workspaceId.equals(conversation.workspaceId())) {
            throw new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND, "Conversation 不存在");
        }
        ConversationRecoveryService.Reconciliation reconciliation = recoveryService.reconcile(conversationId, conversation);
        return new RecoveryState(reconciliation.conversation(), reconciliation.history());
    }

    /**
     * 活动叶子仍是待回答的用户 Entry、且该 Entry 存在未成功 Run 时，禁止发送新消息，
     * 调用方只能重试。叶子为空或已不是 User 时放行（后者由 {@link #retry} 自行校验）。
     */
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

    /**
     * 推进活动叶子、确认序号与标题并刷新更新时间；压缩三元组字段原样保留，避免普通
     * 发送 / 成功落盘误清压缩索引。
     */
    private static Conversation advance(
            Conversation conversation, UUID leafEntryId, long seq, String title, Instant updatedAt
    ) {
        return new Conversation(
                conversation.id(), conversation.workspaceId(), title, conversation.historyFormatVersion(),
                leafEntryId, seq, conversation.latestCompactionEntryId(),
                conversation.latestCompactionSeq(), conversation.latestCompactionByteOffset(),
                conversation.createdAt(), updatedAt);
    }

    /**
     * 在 Agent 结果边界把 {@link AgentUsage} 显式映射为 Conversation 持久化使用的
     * {@link TokenUsage}；两类不得混用。
     */
    private static TokenUsage mapUsage(AgentUsage usage) {
        return usage == null ? null : new TokenUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    /** Conversation 只接收 Agent 已核对的 Citation，不重新访问检索模块或解析回答正文。 */
    private static List<CitationPayload> mapCitations(List<AgentCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream().map(citation -> switch (citation) {
            case AgentLocalCitation local -> new LocalCitationPayload(
                    local.referenceId(), local.evidenceId(), local.revisionId(),
                    local.documentName(), local.location());
            case AgentWebCitation web -> new WebCitationPayload(
                    web.referenceId(), web.provider(), web.title(), web.url(), web.site(),
                    web.dateLabel(), web.retrievedAt());
        }).map(CitationPayload.class::cast).toList();
    }

    private static Entry findEntry(ConversationHistory history, UUID entryId) {
        for (Entry entry : history.entries()) {
            if (entry.id().equals(entryId)) {
                return entry;
            }
        }
        return null;
    }

    /** 恢复后的 Conversation 元数据与 JSONL 历史快照。 */
    private record RecoveryState(Conversation conversation, ConversationHistory history) {
    }

    /**
     * 主调用前压缩结果。{@code compacted=false} 时 {@code conversation} 仍是入参原件；
     * {@code compacted=true} 时已推进到新 Compaction 叶子。
     */
    private record CompactionOutcome(boolean compacted, Conversation conversation, long estimatedTokens) {
    }

    /**
     * 流式主调用成功结果。{@code conversation} 可能已被 overflow 强制压缩推进，
     * 成功落盘必须以它为叶子权威，而不是 {@link #execute} 入口时的快照。
     */
    private record MainOutcome(AgentResult result, Conversation conversation) {
    }
}
