package com.yuyu.salmonmind.agent.api;

import java.time.Instant;

/** 网页搜索结果的有界回看来源；摘录只来自 Provider summary/snippet。 */
public record AgentWebRetrievedSource(
        String referenceId,
        String provider,
        String title,
        String url,
        String site,
        String dateLabel,
        Instant retrievedAt,
        String excerptKind,
        String sourceExcerpt
) implements AgentRetrievedSource {
}
