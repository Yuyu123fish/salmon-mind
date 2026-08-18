package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;

/** 网页搜索结果的有界来源回看记录；excerptKind 标识它是搜索摘要而非网页原文。 */
public record WebRetrievedSourcePayload(
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
) implements RetrievedSourcePayload {

    /** 兼容没有首次召回位置与 Provider 位次的既有来源。 */
    public WebRetrievedSourcePayload(
            String referenceId,
            String provider,
            String title,
            String url,
            String site,
            String dateLabel,
            Instant retrievedAt,
            String excerptKind,
            String sourceExcerpt
    ) {
        this(referenceId, provider, title, url, site, dateLabel, retrievedAt, excerptKind, sourceExcerpt,
                null, null, null);
    }

    @Override
    public String kind() {
        return "web";
    }
}
