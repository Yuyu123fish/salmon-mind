package com.yuyu.salmonmind.agent.api;

/**
 * 会话感知的 Agent 公开入口。conversation 模块只通过本接口与 Agent 交互，
 * 不接触 ReactAgent、RunnableConfig、RedisSaver 或 Spring AI Message 类型。
 */
public interface AgentSession {

    /**
     * 完成一次完整的 Agent 回答。
     *
     * @throws AgentExecutionException 模型未配置、模型调用失败或 Redis 不可用
     */
    AgentResult complete(AgentRequest request);
}
