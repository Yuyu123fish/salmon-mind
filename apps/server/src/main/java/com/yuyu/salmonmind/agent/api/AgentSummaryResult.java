package com.yuyu.salmonmind.agent.api;

/**
 * 上下文摘要结果。summary 为模型返回的原始文本（结构合法性由 conversation 的纯规则校验）；
 * usage 为本次摘要调用的用量，可写入 Compaction Entry。
 */
public record AgentSummaryResult(String summary, AgentUsage usage) {
}
