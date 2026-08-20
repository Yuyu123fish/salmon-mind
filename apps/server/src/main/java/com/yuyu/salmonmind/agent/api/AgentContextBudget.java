package com.yuyu.salmonmind.agent.api;

/**
 * Agent 对一次主调用需要预留的输入预算描述。
 *
 * <p>Conversation 只消费两个数值，不读取模型提示词或工具定义。静态值覆盖实际
 * system prompt、框架固定消息和已注册 Tool schema；动态值覆盖当前 Run 可能送回
 * 模型的工具调用/响应消息封装以及收尾预留，不预留所有工具结果的最大正文。真实
 * Tool Result 由下一次模型调用前的 Run Context Meter 计量。该对象不触发模型、Redis
 * 或外部 Provider。
 *
 * @param staticInputTokens  当前 Agent 固定输入的保守 token 估算
 * @param dynamicInputTokens 当前 Run 工具调用/响应消息封装与收尾预留的保守输入预算
 */
public record AgentContextBudget(long staticInputTokens, long dynamicInputTokens) {

    /** 没有工具的测试替身使用的兼容预算。 */
    public static final AgentContextBudget ZERO = new AgentContextBudget(0, 0);

    public AgentContextBudget {
        if (staticInputTokens < 0 || dynamicInputTokens < 0) {
            throw new IllegalArgumentException("Agent 上下文预算不能为负数");
        }
    }
}
