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
        String sourceExcerpt,
        String originToolCallId,
        Integer resultPosition,
        Integer providerRank
) implements AgentRetrievedSource {

    /** 兼容没有首次召回位置与 Provider 位次的既有来源。 */
    public AgentWebRetrievedSource(
            String referenceId, String provider, String title, String url, String site, String dateLabel,
            Instant retrievedAt, String excerptKind, String sourceExcerpt
    ) {
        this(referenceId, provider, title, url, site, dateLabel, retrievedAt, excerptKind, sourceExcerpt,
                null, null, null);
    }
}
