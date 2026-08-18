package com.yuyu.salmonmind.agent.api;

/**
 * 一个工具调用成功结束的平台事件；终态展示集中由 {@link AgentToolOutcomeDetail} 承载。
 *
 * <p>调用方应优先读取 {@code outcomeDetail}；旧访问器只为已有监听器保留，
 * {@code sourceCount} 无法证明时以 0 兼容返回，不能据此推断真实来源数。
 * {@code safeSummary} 只允许用于紧凑展示，不是模型可重放的工具结果。
 *
 * @param toolCallId    本次 Tool Call 的稳定身份，与 started 事件一一对应
 * @param toolName      模型选择的工具名
 * @param outcomeDetail 已归一化的终态字段；为空时由兼容构造补为无来源详情
 * @param safeSummary   有界安全摘要；为空时由终态 Provider/来源数生成
 */
public record AgentToolCompleted(
        String toolCallId,
        String toolName,
        AgentToolOutcomeDetail outcomeDetail,
        String safeSummary
) {

    /** 普通工具/旧测试构造兼容：没有来源状态。 */
    public AgentToolCompleted(String toolCallId, String toolName, long durationMillis) {
        this(toolCallId, toolName,
                new AgentToolOutcomeDetail(null, null, null, null, durationMillis, false, false),
                "工具执行完成");
    }

    /** 兼容旧事件构造；truncated 在旧合同中表示 Tool Result 截断。 */
    public AgentToolCompleted(
            String toolCallId,
            String toolName,
            long durationMillis,
            String provider,
            int sourceCount,
            boolean truncated,
            boolean degraded
    ) {
        this(toolCallId, toolName,
                new AgentToolOutcomeDetail(provider, null, null, sourceCount, durationMillis,
                        degraded, truncated),
                summary(provider, sourceCount, degraded));
    }

    public AgentToolCompleted {
        if (outcomeDetail == null) {
            outcomeDetail = new AgentToolOutcomeDetail(null, null, null, null, 0, false, false);
        }
        if (safeSummary == null || safeSummary.isBlank()) {
            safeSummary = summary(outcomeDetail.provider(), outcomeDetail.sourceCount(), outcomeDetail.degraded());
        }
    }

    public long durationMillis() {
        return outcomeDetail.durationMillis();
    }

    public String provider() {
        return outcomeDetail.provider();
    }

    public int sourceCount() {
        return outcomeDetail.sourceCount() == null ? 0 : outcomeDetail.sourceCount();
    }

    /** 兼容旧调用方；新代码应读取 {@link AgentToolOutcomeDetail#resultTruncated()}. */
    public boolean truncated() {
        return outcomeDetail.resultTruncated();
    }

    public boolean degraded() {
        return outcomeDetail.degraded();
    }

    private static String summary(String provider, Integer sourceCount, boolean degraded) {
        if (provider == null || provider.isBlank()) {
            return "工具执行完成";
        }
        String count = sourceCount == null ? "来源数未知" : sourceCount + " 个来源";
        return provider + " · " + count + (degraded ? " · 降级结果" : "");
    }
}
