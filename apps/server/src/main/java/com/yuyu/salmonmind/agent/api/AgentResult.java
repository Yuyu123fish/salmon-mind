package com.yuyu.salmonmind.agent.api;

/**
 * 一次完整 Agent 调用的稳定结果。
 *
 * @param text     最终回答文本
 * @param provider 模型提供方标识
 * @param model    实际使用的模型名
 * @param usage    可获得的用量；框架未暴露时为空
 */
public record AgentResult(String text, String provider, String model, AgentUsage usage) {
}
