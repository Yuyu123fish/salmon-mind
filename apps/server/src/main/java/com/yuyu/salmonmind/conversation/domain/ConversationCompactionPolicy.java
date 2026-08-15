package com.yuyu.salmonmind.conversation.domain;

import com.yuyu.salmonmind.conversation.api.Entry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 运行时上下文压缩的纯规则：预算派生、主调用前输入计量、Retained Tail 切分、压缩后重新计量、
 * usage 锚点有效性、Summary 结构校验与 Prompt 渲染。本类不依赖文件 I/O、数据库、Spring AI
 * 或外部 tokenizer；token 估算通过 {@link TokenEstimator} seam 注入，便于确定性测试与部署时替换。
 *
 * <p>Spec 关键合同（本类为唯一实现处）：
 * <ul>
 *   <li>触发阈值 = 工作窗口 − 输出预留（当前 262,144 − 65,432 = 196,712）；达到或超过即压缩。</li>
 *   <li>计量基线优先取最近一次有效、同模型 Assistant 响应的 usage.totalTokens，再加该 usage
 *       未覆盖的模型可见内容（含本次 User Entry）；usage 锚点必须晚于当前 Active Path 上的
 *       最近 Compaction，压缩后旧锚点立即失效。</li>
 *   <li>Retained Tail 从候选区末尾反向累计，达到目标后把切点移动到 User Entry 边界，
 *       保证不拆开一组 user/assistant 交互；本次 User Entry 永远保留。</li>
 *   <li>第二次及以后压缩以 previousSummary 增量吸收新退出原文区的消息，不重新摘要全部原始历史。</li>
 * </ul>
 */
public final class ConversationCompactionPolicy {

    /** 单条消息文本之外的角色与序列化固定开销（保守值），计量时逐条累加，避免低估。 */
    static final long MESSAGE_SERIALIZATION_OVERHEAD = 8L;

    /** 供测试与 application 层直接实例化的纯规则构造器；本类不是 Spring Bean。 */
    public ConversationCompactionPolicy() {
    }

    /** 压缩预算。五个数值相互独立，不派生复用：物理窗口、工作窗口、输出预留、Tail 目标、摘要输出上限。 */
    public record Budgets(
            long physicalContextWindow,
            long workingContextWindow,
            long outputReserve,
            long retainedTailTarget,
            long summaryMaxOutputTokens,
            double summaryTemperature
    ) {
        public Budgets {
            if (physicalContextWindow <= 0 || workingContextWindow <= 0 || outputReserve < 0
                    || retainedTailTarget < 0 || summaryMaxOutputTokens <= 0) {
                throw new IllegalArgumentException("压缩预算必须为正数且输出预留不能为负");
            }
            if (workingContextWindow + outputReserve > physicalContextWindow) {
                throw new IllegalArgumentException("工作窗口与输出预留之和不能超过物理窗口");
            }
        }

        /** 主请求输入触发阈值；输入预计达到或超过该值时必须先压缩。 */
        public long triggerThreshold() {
            return workingContextWindow - outputReserve;
        }
    }

    /** 单条文本的 token 估算 seam。实现必须是确定性、可测试且偏保守（不低估）。 */
    @FunctionalInterface
    public interface TokenEstimator {
        long estimate(String text);
    }

    /**
     * 主调用前输入计量：
     * <ul>
     *   <li>锚点有效（usageAnchorTokens 非空）时：锚点 totalTokens + 锚点之后新增消息的估算；
     *       锚点已包含 system prompt、摘要与既有消息，不得重复计算。</li>
     *   <li>无锚点时：system prompt + previousSummary（若有）+ 全部投影消息的估算。
     *       projectedEntries 必须与 entriesAfterAnchor 互斥且不相交：有锚点时投影中
     *       锚点覆盖的部分由锚点承担，entriesAfterAnchor 是其余部分；无锚点时
     *       entriesAfterAnchor 必须为空。</li>
     * </ul>
     */
    public long estimateNextInputTokens(
            Budgets budgets,
            TokenEstimator estimator,
            long systemPromptTokens,
            String previousSummary,
            List<Entry> projectedEntries,
            Long usageAnchorTokens,
            List<Entry> entriesAfterAnchor
    ) {
        if (usageAnchorTokens != null) {
            if (entriesAfterAnchor.isEmpty()) {
                throw new IllegalArgumentException("有 usage 锚点时 entriesAfterAnchor 不能为空");
            }
            return usageAnchorTokens + estimateMessages(estimator, entriesAfterAnchor);
        }
        if (!entriesAfterAnchor.isEmpty()) {
            throw new IllegalArgumentException("无 usage 锚点时 entriesAfterAnchor 必须为空");
        }
        long total = systemPromptTokens + estimateMessages(estimator, projectedEntries);
        if (previousSummary != null && !previousSummary.isBlank()) {
            total += estimator.estimate(previousSummary);
        }
        return total;
    }

    /** 达到或超过触发阈值时必须压缩。 */
    public boolean shouldCompact(long estimatedInputTokens, Budgets budgets) {
        return estimatedInputTokens >= budgets.triggerThreshold();
    }

    /**
     * 计划一次压缩：从候选区（无旧压缩时是全部投影消息；有旧压缩时是旧 retainedTail +
     * 该 Compaction 后的新消息 + 本次 User Entry）切出 Retained Tail 与退出原文区。
     * Tail 从末尾反向累计到目标后，若最旧一条是 Assistant 则继续向前并入其 User，
     * 保证 Tail 以 User Entry 开头；Tail 可能因单个完整交互超出目标（允许超出）。
     *
     * @return 摘要输入（增量时含 previousSummary）、退出原文区 Entry、被摘要覆盖的最后一个
     *         Entry id 与保留的 Tail。退出区为空表示不需要压缩（全部内容都保留在 Tail 中）。
     */
    public CompactionPlan planCompaction(
            Budgets budgets,
            TokenEstimator estimator,
            String previousSummary,
            List<Entry> candidateEntries
    ) {
        List<Entry> tail = new ArrayList<>();
        long accumulated = 0;
        for (int i = candidateEntries.size() - 1; i >= 0; i--) {
            Entry entry = candidateEntries.get(i);
            tail.add(0, entry);
            accumulated += estimateMessage(estimator, entry);
            if (accumulated >= budgets.retainedTailTarget()
                    && entry.type() == Entry.EntryType.USER_MESSAGE) {
                break;
            }
        }

        List<Entry> summarized = candidateEntries.subList(0, candidateEntries.size() - tail.size());
        if (summarized.isEmpty()) {
            // 全部内容都保留在 Tail 中（候选区未达到目标或单条交互已占满）：不需要压缩
            return new CompactionPlan(List.copyOf(tail), List.of(), null, "");
        }
        UUID coveredThrough = summarized.get(summarized.size() - 1).id();
        String input = previousSummary == null || previousSummary.isBlank()
                ? SummaryTemplate.firstTime(summarized)
                : SummaryTemplate.incremental(previousSummary, summarized);
        return new CompactionPlan(List.copyOf(tail), List.copyOf(summarized), coveredThrough, input);
    }

    /**
     * 压缩后重新计量：Summary + Retained Tail + Compaction 后新增消息。刻意不接受任何 usage 锚点，
     * 压缩前锚点在本步结构性失效，不会再次触发压缩。
     */
    public long reestimateAfterCompaction(
            Budgets budgets,
            TokenEstimator estimator,
            long systemPromptTokens,
            String summary,
            List<Entry> retainedTail,
            List<Entry> entriesAfterCompaction
    ) {
        List<Entry> all = new ArrayList<>(retainedTail.size() + entriesAfterCompaction.size());
        all.addAll(retainedTail);
        all.addAll(entriesAfterCompaction);
        return systemPromptTokens + estimator.estimate(summary) + estimateMessages(estimator, all);
    }

    /**
     * usage 锚点有效性：锚点 Entry 必须位于当前 Active Path，且在路径上最近 Compaction 之后；
     * 路径上无 Compaction 时只要在路径上即有效。锚点在分支上或早于/等于最近压缩都属于失效，
     * 调用方应把锚点视为不存在（传入 null）走完整估算。
     */
    public boolean isUsageAnchorValid(UUID anchorEntryId, List<Entry> activePath) {
        if (anchorEntryId == null) {
            return false;
        }
        int anchorIndex = -1;
        int compactionIndex = -1;
        for (int i = 0; i < activePath.size(); i++) {
            Entry entry = activePath.get(i);
            if (entry.id().equals(anchorEntryId)) {
                anchorIndex = i;
            }
            if (entry.type() == Entry.EntryType.COMPACTION) {
                compactionIndex = i;
            }
        }
        return anchorIndex >= 0 && anchorIndex > compactionIndex;
    }

    /**
     * Summary 结构校验：非空、未被截断的完整结构必须包含全部固定一级标题。校验失败时
     * 不追加 Compaction，Run 以稳定压缩失败结束。
     */
    public boolean isValidSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        return SummaryTemplate.FIXED_HEADINGS.stream()
                .allMatch(heading -> summary.contains("## " + heading));
    }

    private long estimateMessages(TokenEstimator estimator, List<Entry> entries) {
        long total = 0;
        for (Entry entry : entries) {
            total += estimateMessage(estimator, entry);
        }
        return total;
    }

    private long estimateMessage(TokenEstimator estimator, Entry entry) {
        return switch (entry.type()) {
            case USER_MESSAGE -> estimator.estimate(textOf(entry))
                    + MESSAGE_SERIALIZATION_OVERHEAD;
            case ASSISTANT_MESSAGE -> estimator.estimate(textOf(entry))
                    + MESSAGE_SERIALIZATION_OVERHEAD;
            case COMPACTION -> throw new IllegalArgumentException("候选区不能包含 Compaction Entry: " + entry.id());
            case TITLE -> throw new IllegalArgumentException("候选区不能包含 Title Entry: " + entry.id());
        };
    }

    private static String textOf(Entry entry) {
        return switch (entry.payload()) {
            case com.yuyu.salmonmind.conversation.api.UserMessagePayload p -> p.text();
            case com.yuyu.salmonmind.conversation.api.AssistantMessagePayload p -> p.text();
            case com.yuyu.salmonmind.conversation.api.CompactionPayload p ->
                    throw new IllegalArgumentException("Compaction Entry 没有可计量文本: " + entry.id());
            case com.yuyu.salmonmind.conversation.api.TitlePayload p ->
                    throw new IllegalArgumentException("Title Entry 没有可计量文本: " + entry.id());
        };
    }

    /** 一次压缩计划的纯结果：保留原文、退出摘要区、覆盖边界与直接可用的摘要模型输入。 */
    public record CompactionPlan(
            List<Entry> retainedTail,
            List<Entry> summarizedEntries,
            UUID coveredThroughEntryId,
            String summarizeInput
    ) {
    }
}
