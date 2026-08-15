package com.yuyu.salmonmind.agent.api;

/**
 * Conversation 标题生成能力：独立于 ReactAgent Checkpoint 的轻量非流式模型调用。
 * 模型调用失败以 {@link AgentExecutionException} 抛出；返回空白或截断时 title 为 null，
 * 不影响已经成功的主 Run。
 */
public interface AgentTitleService {

    AgentTitleResult generateTitle(AgentTitleRequest request);
}
