package com.yuyu.salmonmind.agent.api;

import java.time.Instant;

/** 已由当前 Run Web Tool 结果核对的最小网页 Citation，不携带搜索摘要或原始响应。 */
public record AgentWebCitation(
        String referenceId,
        String provider,
        String title,
        String url,
        String site,
        String dateLabel,
        Instant retrievedAt
) implements AgentCitation {
}
