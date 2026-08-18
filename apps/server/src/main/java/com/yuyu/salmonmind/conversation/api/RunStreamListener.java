package com.yuyu.salmonmind.conversation.api;

import java.util.UUID;

/**
 * 一次发送 / 重试的 SSE 事件消费合同。调用方（web 层）实现本接口把事件转换为
 * 标准 SSE 帧；application 只按下列顺序回调，不直接接触 HTTP 或序列化。
 *
 * <p>顺序合同：恰好一次 {@link #onRunStarted}；随后零或一次
 * {@link #onCompactionCompleted}，以及零到多次 reasoning、工具状态和回答事件；
 * 成功时恰好一次 {@link #onAssistantCompleted}；零或一次 {@link #onTitleUpdated}；
 * 最后以 {@link #onRunCompleted} 或 {@link #onRunFailed} 恰好一次结束，
 * 终态事件之后不得再有业务事件。
 *
 * <p>失败边界：onRunStarted 之前的前置错误（Conversation 不存在、历史损坏、
 * 输入非法、忙碌）以异常抛出并由 HTTP 层映射为 JSON 错误；onRunStarted 之后
 * 的所有失败只能通过 onRunFailed 收束。SSE 客户端断线不改变本合同的执行语义：
 * 回调方可以停止发送但 Run 继续完成持久化。
 */
public interface RunStreamListener {

    /** run_started：User Entry 与 RUNNING Run 已持久化后发出；isRetry 标记复用既有触发 Entry。 */
    record RunStarted(UUID conversationId, Run run, Entry userEntry, boolean isRetry) {
    }

    /** compaction_completed：Compaction Entry 已追加、数据库压缩索引已更新。 */
    record CompactionCompleted(UUID conversationId, Entry compactionEntry, Conversation conversation) {
    }

    /** assistant_delta：主回答增量文本，只用于临时显示，不构成最终结果。 */
    record AssistantDelta(UUID runId, String delta) {
    }

    /** reasoning_delta：模型公开提供的可展示推理增量，不属于最终回答正文。 */
    record ReasoningDelta(UUID runId, String delta) {
    }

    /** 工具开始事件；不携带 query、正文或原始参数。 */
    record ToolStarted(UUID runId, String toolCallId, String toolName, String safeSummary) {
    }

    /** 工具成功结束事件；来源状态只保留 Provider、数量和边界标记。 */
    record ToolCompleted(
            UUID runId, String toolCallId, String toolName, long durationMillis,
            String provider, int sourceCount, boolean truncated, boolean degraded, String safeSummary
    ) {
        public ToolCompleted(UUID runId, String toolCallId, String toolName, long durationMillis) {
            this(runId, toolCallId, toolName, durationMillis, null, 0, false, false, "工具执行完成");
        }
    }

    /** 工具失败事件；错误码稳定且不携带内部堆栈或工具结果。 */
    record ToolFailed(
            UUID runId, String toolCallId, String toolName, long durationMillis,
            String stableErrorCode, String safeMessage
    ) {
    }

    /** assistant_completed：完整且已持久化的 Assistant Entry。 */
    record AssistantCompleted(UUID conversationId, Entry assistantEntry) {
    }

    /** title_updated：Title Entry 已追加，并携带包含该确认序号的最新 Conversation 快照。 */
    record TitleUpdated(UUID conversationId, Entry titleEntry, String title, Conversation conversation) {
    }

    /** run_completed：唯一成功终态。 */
    record RunCompleted(UUID conversationId, Run run, Conversation conversation) {
    }

    /** run_failed：唯一失败终态；errorCode 为 Spec 稳定错误码。 */
    record RunFailed(UUID conversationId, String errorCode, String message, Run run, Conversation conversation) {
    }

    void onRunStarted(RunStarted event);

    void onCompactionCompleted(CompactionCompleted event);

    void onAssistantDelta(AssistantDelta event);

    default void onReasoningDelta(ReasoningDelta event) {
    }

    default void onToolStarted(ToolStarted event) {
    }

    default void onToolCompleted(ToolCompleted event) {
    }

    default void onToolFailed(ToolFailed event) {
    }

    void onAssistantCompleted(AssistantCompleted event);

    void onTitleUpdated(TitleUpdated event);

    void onRunCompleted(RunCompleted event);

    void onRunFailed(RunFailed event);
}
