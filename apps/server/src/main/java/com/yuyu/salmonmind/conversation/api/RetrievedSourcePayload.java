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

    /** 首次把来源送入模型上下文的工具调用；旧历史没有该字段时为空。 */
    String originToolCallId();

    /** 来源在首次最终有界 Tool Result 中的 1-based 位置；旧历史时为空。 */
    Integer resultPosition();

    /** Provider 返回的合法正整数位次；本地来源为空。 */
    Integer providerRank();

    /** HTTP JSON 中的稳定变体标记，供前端区分本地与网页来源。 */
    @JsonProperty("kind")
    String kind();
}
