package com.yuyu.salmonmind.agent.api;

import java.time.Instant;

/** 已由当前 Run Web Tool 结果核对的网页 Citation；Note 只来自 Agent 已有回答。 */
public record AgentWebCitation(
        String referenceId,
        String provider,
        String title,
        String url,
        String site,
        String dateLabel,
        Instant retrievedAt,
        String citationNote
) implements AgentCitation {

    /** 兼容没有 Citation Note 的既有调用方与历史测试替身。 */
    public AgentWebCitation(
            String referenceId, String provider, String title, String url, String site,
            String dateLabel, Instant retrievedAt
    ) {
        this(referenceId, provider, title, url, site, dateLabel, retrievedAt, null);
    }
}
