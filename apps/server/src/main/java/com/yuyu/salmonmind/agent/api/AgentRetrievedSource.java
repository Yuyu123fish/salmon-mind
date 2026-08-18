package com.yuyu.salmonmind.agent.api;

import java.time.Instant;

/**
 * 当前 Run 实际交给模型的有界来源预览。它比 Citation 更宽：来源可以最终未被回答采用，
 * 但不得携带完整 Tool Result、查询、原始响应或内部排序信息。
 */
public sealed interface AgentRetrievedSource
        permits AgentLocalRetrievedSource, AgentWebRetrievedSource {

    /** 当前 Run 内稳定的来源标识，例如 {@code L1} 或 {@code W2}。 */
    String referenceId();

    /** 来源预览的语义类型，不把网页摘要误称为网页原文。 */
    String excerptKind();

    /** 有界来源预览；内容为空时允许为 null。 */
    String sourceExcerpt();

    /** Server 收到本轮结果的时间。 */
    Instant retrievedAt();
}
