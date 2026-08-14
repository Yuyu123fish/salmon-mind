package com.yuyu.salmonmind.agent.api;

/**
 * 模型调用的 token 用量；任一字段都可能为空。
 */
public record AgentUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
}
