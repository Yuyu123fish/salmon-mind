package com.yuyu.salmonmind.conversation.api;

/**
 * conversation 持久化 Entry 使用的 token 用量；由 application 在 Agent 结果边界
 * 从 agent::api 的 AgentUsage 显式映射，避免 Entry 与 JSONL 依赖 agent 类型。
 */
public record TokenUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
}
