package com.yuyu.salmonmind.agent.api;

/** Agent 最终回答中经 Server 核对过的来源；只允许 Local/Web 两种明确变体。 */
public sealed interface AgentCitation permits AgentLocalCitation, AgentWebCitation {

    /** 当前 Run 内模型可引用的稳定标记，例如 {@code L1} 或 {@code W2}。 */
    String referenceId();

    /** 从 Agent 已有回答中提取的有界相关性说明；无法取得时为 null。 */
    String citationNote();
}
