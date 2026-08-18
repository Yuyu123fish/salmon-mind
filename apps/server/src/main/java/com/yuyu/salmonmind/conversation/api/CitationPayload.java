package com.yuyu.salmonmind.conversation.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Assistant Entry 中持久化的已验证来源变体；不保存工具 schema、query、snippet 或原始响应。 */
public sealed interface CitationPayload permits LocalCitationPayload, WebCitationPayload {

    String referenceId();

    /** 从 Agent 已有回答中提取的有界相关性说明；旧 Entry 缺失时按 null 读取。 */
    String citationNote();

    /** HTTP JSON 中的稳定变体标记，供前端安全地区分本地与网页来源。 */
    @JsonProperty("kind")
    String kind();
}
