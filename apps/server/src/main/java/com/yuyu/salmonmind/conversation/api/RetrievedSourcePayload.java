package com.yuyu.salmonmind.conversation.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Assistant Entry 中持久化的本轮有界来源变体；不保存 query、原始响应或完整正文。 */
public sealed interface RetrievedSourcePayload
        permits LocalRetrievedSourcePayload, WebRetrievedSourcePayload {

    String referenceId();

    Instant retrievedAt();

    String excerptKind();

    String sourceExcerpt();

    /** HTTP JSON 中的稳定变体标记，供前端区分本地与网页来源。 */
    @JsonProperty("kind")
    String kind();
}
