package com.yuyu.salmonmind.agent.api;

import java.util.List;

/**
 * 一次完整 Agent 调用的稳定结果。
 *
 * @param text     最终回答文本
 * @param provider 模型提供方标识
 * @param model    实际使用的模型名
 * @param usage     可获得的用量；框架未暴露时为空
 * @param citations       最终正文实际引用且能映射到当前 Run 来源注册表的来源
 * @param retrievedSources 本轮实际交给模型且经过预算裁剪的全部来源
 * @param trace            有界展示轨迹；只用于 UI/历史审查，禁止重新送入模型上下文
 * @param completionStatus 输出是否自然结束；长度收束时保留已生成正文
 * @param completionDetailCode 仅描述续写阶段异常等稳定诊断，普通长度收束为空
 * @param callChain 本次 Run 成功准备的调用链最小引用；准备失败或没有有效草稿时为空
 */
public record AgentResult(
        String text,
        String provider,
        String model,
        AgentUsage usage,
        List<AgentCitation> citations,
        List<AgentRetrievedSource> retrievedSources,
        List<AgentRunTraceItem> trace,
        AgentCompletionStatus completionStatus,
        String completionDetailCode,
        AgentCallChainReference callChain
) {

    public AgentResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        retrievedSources = retrievedSources == null ? List.of() : List.copyOf(retrievedSources);
        trace = trace == null ? List.of() : List.copyOf(trace);
        completionStatus = completionStatus == null ? AgentCompletionStatus.COMPLETE : completionStatus;
    }

    /** 保持既有五参数调用兼容；旧调用没有 Run Trace。 */
    public AgentResult(
        String text, String provider, String model, AgentUsage usage, List<AgentCitation> citations
    ) {
        this(text, provider, model, usage, citations, List.of(), List.of(),
                AgentCompletionStatus.COMPLETE, null, null);
    }

    /** 保持既有六参数调用兼容；旧调用没有 Retrieved Source。 */
    public AgentResult(
            String text, String provider, String model, AgentUsage usage,
            List<AgentCitation> citations, List<AgentRunTraceItem> trace
    ) {
        this(text, provider, model, usage, citations, List.of(), trace,
                AgentCompletionStatus.COMPLETE, null, null);
    }

    /** 兼容已有调用方提供 Retrieved Source 与 Trace 的七参数构造。 */
    public AgentResult(
            String text, String provider, String model, AgentUsage usage,
            List<AgentCitation> citations, List<AgentRetrievedSource> retrievedSources,
            List<AgentRunTraceItem> trace
    ) {
        this(text, provider, model, usage, citations, retrievedSources, trace,
                AgentCompletionStatus.COMPLETE, null, null);
    }

    /** 保持测试替身和既有调用方的四参数构造兼容；旧调用没有 Citation。 */
    public AgentResult(String text, String provider, String model, AgentUsage usage) {
        this(text, provider, model, usage, List.of(), List.of(), List.of(),
                AgentCompletionStatus.COMPLETE, null, null);
    }

    /** 兼容已有调用方提供完整完成状态但没有调用链引用的九参数构造。 */
    public AgentResult(
            String text, String provider, String model, AgentUsage usage,
            List<AgentCitation> citations, List<AgentRetrievedSource> retrievedSources,
            List<AgentRunTraceItem> trace, AgentCompletionStatus completionStatus,
            String completionDetailCode
    ) {
        this(text, provider, model, usage, citations, retrievedSources, trace,
                completionStatus, completionDetailCode, null);
    }

}
