package com.yuyu.salmonmind.conversation.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Assistant Entry 中持久化的已验证来源变体；不保存工具 schema、query、snippet 或原始响应。 */
public sealed interface CitationPayload permits LocalCitationPayload, WebCitationPayload {

    String referenceId();

    /** HTTP JSON 中的稳定变体标记，供前端安全地区分本地与网页来源。 */
    @JsonProperty("kind")
    String kind();
}
