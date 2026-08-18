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
        String sourceExcerpt
) implements RetrievedSourcePayload {

    @Override
    public String kind() {
        return "web";
    }
}
