package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;

/** 网页结果的最小持久化引用；URL 已在 Server 侧验证为 HTTP(S)。 */
public record WebCitationPayload(
        String referenceId,
        String provider,
        String title,
        String url,
        String site,
        String dateLabel,
        Instant retrievedAt,
        String citationNote
) implements CitationPayload {

    /** 兼容旧 JSONL 与既有调用方没有 Note 的构造。 */
    public WebCitationPayload(
            String referenceId, String provider, String title, String url, String site,
            String dateLabel, Instant retrievedAt
    ) {
        this(referenceId, provider, title, url, site, dateLabel, retrievedAt, null);
    }

    @Override
    public String kind() {
        return "web";
    }
}
