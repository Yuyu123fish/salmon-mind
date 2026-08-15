package com.yuyu.salmonmind.agent.api;

/**
 * 结构化上下文摘要能力：独立于 ReactAgent Checkpoint 的轻量非流式模型调用。
 * 摘要失败（调用失败、输出被长度截断）以 {@link AgentExecutionException} 抛出；
 * 摘要文本的结构校验属于 conversation 纯规则，不在此处进行。
 */
public interface AgentSummaryService {

    AgentSummaryResult summarize(AgentSummaryRequest request);
}
