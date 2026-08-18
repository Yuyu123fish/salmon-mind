package com.yuyu.salmonmind.agent.api;

import java.util.List;

/**
 * 一次完整 Agent 调用的稳定结果。
 *
 * @param text     最终回答文本
 * @param provider 模型提供方标识
 * @param model    实际使用的模型名
 * @param usage     可获得的用量；框架未暴露时为空
 * @param citations 最终正文实际引用且能映射到当前 Run 来源注册表的最小来源
 */
public record AgentResult(
        String text,
        String provider,
        String model,
        AgentUsage usage,
        List<AgentCitation> citations
) {

    public AgentResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 保持测试替身和既有调用方的四参数构造兼容；旧调用没有 Citation。 */
    public AgentResult(String text, String provider, String model, AgentUsage usage) {
        this(text, provider, model, usage, List.of());
    }
}
