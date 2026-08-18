package com.yuyu.salmonmind.agent.api;

/**
 * Agent 对一次主调用需要预留的输入预算描述。
 *
 * <p>Conversation 只消费两个数值，不读取模型提示词或工具定义。静态值覆盖实际
 * system prompt、框架固定消息和已注册 Tool schema；动态值覆盖当前 Run 可能送回
 * 模型的工具调用、工具结果及其消息封装。该对象不触发模型、Redis 或外部 Provider。
 *
 * @param staticInputTokens  当前 Agent 固定输入的保守 token 估算
 * @param dynamicInputTokens 当前 Run 工具消息的最大保守输入预算
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
