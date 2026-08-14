package com.yuyu.salmonmind.conversation.api;

/**
 * 发送 / 重试的稳定结果：更新后的 Conversation、触发用户 Entry、终态 Assistant Entry 与终态 Run。
 * 方法正常返回时 assistantEntry 与 run 恒为成功结果；Agent 失败不返回本记录，
 * 而是由协调器把 Run 标记为 FAILED 后抛出 agent::api 的稳定异常。
 */
public record ConversationRunResult(
        Conversation conversation,
        Entry userEntry,
        Entry assistantEntry,
        Run run
) {
}
