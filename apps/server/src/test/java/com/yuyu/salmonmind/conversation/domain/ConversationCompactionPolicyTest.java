package com.yuyu.salmonmind.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CompactionPayload;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.LocalCitationPayload;
import com.yuyu.salmonmind.conversation.api.TokenUsage;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import org.junit.jupiter.api.Test;

/**
 * 压缩纯规则聚焦测试：显式阈值边界、usage 锚点失效、本次 User 参与计量、
 * Retained Tail 的 User 边界、64K 目标与增量摘要输入。使用确定性 estimator（1 token/字符），
 * 不依赖 I/O、数据库或模型。
 */
class ConversationCompactionPolicyTest {

    private static final UUID CONVERSATION = UUID.randomUUID();

    private final ConversationCompactionPolicy policy = new ConversationCompactionPolicy();

    // 小预算便于测试：trigger-input-tokens 显式为 1500
    private final ConversationCompactionPolicy.Budgets budgets = new ConversationCompactionPolicy.Budgets(
            100_000, 1500, 500, 600, 800, 0.1);

    private final ConversationCompactionPolicy.TokenEstimator estimator = text -> text.length();

    @Test
    void triggersCompactionOnlyAtThresholdWithUsageAnchor() {
        // 锚点 1200 覆盖 system prompt 与既有历史；新增内容 = 本次 User 文本 + 序列化开销(8)
        Entry currentUser = user(1, null, "x".repeat(291));
        long below = policy.estimateNextInputTokens(budgets, estimator, 100, null, List.of(currentUser),
                1200L, List.of(currentUser));
        assertThat(below).isEqualTo(1499L);
        assertThat(policy.shouldCompact(below, budgets)).isFalse();

        Entry biggerUser = user(1, null, "x".repeat(292));
        long at = policy.estimateNextInputTokens(budgets, estimator, 100, null, List.of(biggerUser),
                1200L, List.of(biggerUser));
        assertThat(at).isEqualTo(1500L);
        assertThat(policy.shouldCompact(at, budgets)).isTrue();
    }

    @Test
    void triggersCompactionAtThresholdWithoutAnchorIncludingSystemPromptAndCurrentUser() {
        // 无锚点：system prompt + 全部投影消息（本次 User 在内）
        Entry u1 = user(1, null, "x".repeat(600));
        Entry a1 = assistant(2, u1.id(), "x".repeat(500));
        Entry currentUser = user(3, a1.id(), "x".repeat(276));
        List<Entry> projected = List.of(u1, a1, currentUser);

        long at = policy.estimateNextInputTokens(budgets, estimator, 100, null, projected, null, List.of());
        assertThat(at).isEqualTo(100 + 608 + 508 + 284);
        assertThat(policy.shouldCompact(at, budgets)).isTrue();

        // 本次 User 少 1 字符：1500 - 1 = 1499，不压缩——证明本次 User 参与计量
        Entry smallerUser = user(3, a1.id(), "x".repeat(275));
        long below = policy.estimateNextInputTokens(budgets, estimator, 100, null,
                List.of(u1, a1, smallerUser), null, List.of());
        assertThat(below).isEqualTo(1499L);
        assertThat(policy.shouldCompact(below, budgets)).isFalse();
    }

    @Test
    void reestimateAfterCompactionNeverAcceptsOldUsageAnchor() {
        // 压缩后重计量 = system prompt + Summary + Retained Tail + Compaction 后消息；
        // API 不接受任何 usage 锚点，旧锚点在结构上失效，不会因压缩前大用量再次触发
        Entry u1 = user(1, null, "x".repeat(50));
        Entry a1 = assistant(2, u1.id(), "x".repeat(50));
        Entry after = user(3, a1.id(), "x".repeat(50));
        long estimate = policy.reestimateAfterCompaction(
                budgets, estimator, 100, "x".repeat(100), List.of(u1, a1), List.of(after));
        assertThat(estimate).isEqualTo(100 + 100 + (50 + 8) * 2 + (50 + 8));
    }

    @Test
    void usageAnchorIsValidOnlyOnActivePathAfterLatestCompaction() {
        Entry u1 = user(1, null, "一");
        Entry a1 = assistant(2, u1.id(), "答");
        Entry u2 = user(3, a1.id(), "二");
        Entry a2 = assistant(4, u2.id(), "答二");
        Entry c1 = compaction(5, a2.id());
        Entry u3 = user(6, c1.id(), "三");
        Entry a3 = assistant(7, u3.id(), "答三");
        List<Entry> path = List.of(u1, a1, u2, a2, c1, u3, a3);

        // 压缩前的 usage 失效；压缩后同一路径上的 usage 有效
        assertThat(policy.isUsageAnchorValid(a2.id(), path)).isFalse();
        assertThat(policy.isUsageAnchorValid(a3.id(), path)).isTrue();
        // 不在路径上的锚点（分支消息）失效
        assertThat(policy.isUsageAnchorValid(UUID.randomUUID(), path)).isFalse();
        // 无压缩路径上，路径内锚点有效
        assertThat(policy.isUsageAnchorValid(a2.id(), List.of(u1, a1, u2, a2))).isTrue();
    }

    @Test
    void retainedTailMovesCutToUserBoundaryWithoutSplittingPairs() {
        // target=600：倒序累计 U3(9)→A2(6012≥600 但 A2 是 Assistant 不停)→U2(6021, User 停)，
        // tail=[U2,A2,U3] 以 User 开头，不拆开 U2/A2 交互
        Entry u1 = user(1, null, "x");
        Entry a1 = assistant(2, u1.id(), "x");
        Entry u2 = user(3, a1.id(), "x");
        Entry a2 = assistant(4, u2.id(), "x".repeat(5995));
        Entry currentUser = user(5, a2.id(), "x");
        List<Entry> candidates = List.of(u1, a1, u2, a2, currentUser);

        ConversationCompactionPolicy.CompactionPlan plan =
                policy.planCompaction(budgets, estimator, null, candidates);

        assertThat(plan.retainedTail()).containsExactly(u2, a2, currentUser);
        assertThat(plan.summarizedEntries()).containsExactly(u1, a1);
        assertThat(plan.coveredThroughEntryId()).isEqualTo(a1.id());
    }

    @Test
    void retainedTailReachesTargetWithOvershootAndKeepsCurrentUser() {
        // 单个交互远超 target：允许超出（tail 以 User 开头即可）
        Entry u1 = user(1, null, "x");
        Entry a1 = assistant(2, u1.id(), "x");
        Entry u2 = user(3, a1.id(), "x");
        Entry a2 = assistant(4, u2.id(), "x".repeat(6000));
        Entry currentUser = user(5, a2.id(), "x");

        ConversationCompactionPolicy.CompactionPlan plan = policy.planCompaction(
                budgets, estimator, null, List.of(u1, a1, u2, a2, currentUser));

        assertThat(plan.retainedTail()).containsExactly(u2, a2, currentUser);
        // 本次 User 永远保留
        assertThat(plan.retainedTail().get(plan.retainedTail().size() - 1)).isEqualTo(currentUser);
        // 全部候选都不够 target 时：所有内容进入 Tail，无摘要区
        ConversationCompactionPolicy.CompactionPlan tiny = policy.planCompaction(
                budgets, estimator, null, List.of(u1, a1));
        assertThat(tiny.retainedTail()).containsExactly(u1, a1);
        assertThat(tiny.summarizedEntries()).isEmpty();
    }

    @Test
    void incrementalSummarizeInputContainsPreviousSummaryAndOnlyExitedMessages() {
        // 尺寸设计：倒序累计 currentUser(58)→a2(366)→u2(674≥600, User 停)，u1/a1 退出摘要区
        Entry u1 = user(1, null, "x".repeat(100));
        Entry a1 = assistant(2, u1.id(), "x".repeat(100));
        Entry u2 = user(3, a1.id(), "x".repeat(300));
        Entry a2 = assistant(4, u2.id(), "x".repeat(300));
        Entry currentUser = user(5, a2.id(), "x".repeat(50));

        // 增量：previousSummary + 本次退出原文区的消息（u1/a1），已进入旧摘要且未变化的原始历史不重复
        ConversationCompactionPolicy.CompactionPlan incremental = policy.planCompaction(
                budgets, estimator, "旧摘要", List.of(u1, a1, u2, a2, currentUser));
        assertThat(incremental.summarizedEntries()).containsExactly(u1, a1);
        assertThat(incremental.summarizeInput()).contains("旧摘要");
        assertThat(incremental.summarizeInput()).contains("用户：" + u1text(u1));
        assertThat(incremental.summarizeInput()).contains("助手：" + a1text(a1));
        assertThat(incremental.summarizeInput()).doesNotContain(u2text(u2));

        // 首次：无 previousSummary，输入只含退出消息
        ConversationCompactionPolicy.CompactionPlan first = policy.planCompaction(
                budgets, estimator, null, List.of(u1, a1, u2, a2, currentUser));
        assertThat(first.summarizeInput()).doesNotContain("旧摘要");
        assertThat(first.summarizeInput()).contains("用户：" + u1text(u1));
        assertThat(first.summarizeInput()).contains("助手：" + a1text(a1));
        // 固定一级标题齐全
        for (String heading : SummaryTemplate.FIXED_HEADINGS) {
            assertThat(first.summarizeInput()).contains("## " + heading);
        }
    }

    @Test
    void validSummaryRequiresAllFixedHeadingsAndNonBlank() {
        String complete = "## 用户目标\n目标\n## 约束与偏好\n约束\n## 当前状态\n状态\n"
                + "## 关键决定\n决定\n## 关键上下文\n上下文\n## 未解决问题\n问题\n## 下一步\n下一步";
        assertThat(policy.isValidSummary(complete)).isTrue();
        assertThat(policy.isValidSummary(complete.replace("## 下一步", ""))).isFalse();
        assertThat(policy.isValidSummary("   ")).isFalse();
        assertThat(policy.isValidSummary(null)).isFalse();
    }

    @Test
    void citationProjectionIsUsedBySummaryAndTokenEstimation() {
        Entry assistant = new Entry(1, CONVERSATION, UUID.randomUUID(), 2, null,
                Entry.EntryType.ASSISTANT_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new AssistantMessagePayload("回答", UUID.randomUUID(), "provider", "model", null,
                        List.of(new LocalCitationPayload("L1", UUID.randomUUID(), UUID.randomUUID(),
                                "manual.md", "p1"))));
        Entry laterUser = user(3, assistant.id(), "后续问题");
        Entry laterAssistant = assistant(4, laterUser.id(), "后续回答");

        long estimated = policy.estimateNextInputTokens(
                budgets, String::length, 0, null, List.of(assistant), null, List.of());
        ConversationCompactionPolicy.CompactionPlan plan = policy.planCompaction(
                new ConversationCompactionPolicy.Budgets(100_000, 1500, 500, 20, 800, 0.1),
                String::length, null, List.of(user(1, null, "首问"), assistant, laterUser, laterAssistant));

        assertThat(estimated).isGreaterThan("回答".length() + 8L);
        assertThat(plan.summarizeInput()).contains("source=LOCAL").contains("L1");
    }

    @Test
    void budgetsDeriveThresholdAndRejectInvalidCombinations() {
        assertThat(budgets.triggerThreshold()).isEqualTo(1500);
        assertThat(new ConversationCompactionPolicy.Budgets(30, 20, 1, 1, 1, 0.1).triggerThreshold()).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ConversationCompactionPolicy.Budgets(10, 20, 5, 1, 1, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Entry user(long seq, UUID parentId, String text) {
        return new Entry(1, CONVERSATION, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.USER_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new UserMessagePayload(text, UUID.randomUUID()));
    }

    private Entry assistant(long seq, UUID parentId, String text) {
        return new Entry(1, CONVERSATION, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.ASSISTANT_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new AssistantMessagePayload(text, UUID.randomUUID(), "test-provider", "test-model",
                        new TokenUsage(10L, 5L, 15L)));
    }

    private Entry compaction(long seq, UUID parentId) {
        return new Entry(1, CONVERSATION, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.COMPACTION, Instant.parse("2026-08-01T00:00:00Z"),
                new CompactionPayload("摘要", parentId, List.of(), 100L, null));
    }

    private static String u1text(Entry entry) {
        return ((UserMessagePayload) entry.payload()).text();
    }

    private static String a1text(Entry entry) {
        return ((AssistantMessagePayload) entry.payload()).text();
    }

    private static String u2text(Entry entry) {
        return ((UserMessagePayload) entry.payload()).text();
    }
}
