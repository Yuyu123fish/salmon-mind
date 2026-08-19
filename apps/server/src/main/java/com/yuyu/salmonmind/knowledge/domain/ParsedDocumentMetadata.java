package com.yuyu.salmonmind.knowledge.domain;

import java.time.Instant;
import java.util.List;

/**
 * 文档解析阶段提取的有限白名单元信息。
 *
 * <p>该记录属于不可变 Revision 的一部分，只表达调用方稳定需要的字段；
 * 原始 Tika Metadata、未知键、二进制值和正文均不进入此模型。</p>
 */
public record ParsedDocumentMetadata(
        String title,
        List<String> authors,
        String subject,
        String description,
        String language,
        Instant createdAt,
        Instant modifiedAt,
        String producer
) {

    public ParsedDocumentMetadata {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }

    /** 返回与数据库 JSONB 默认值对应的空元信息。 */
    public static ParsedDocumentMetadata empty() {
        return new ParsedDocumentMetadata(null, List.of(), null, null, null, null, null, null);
    }

    /** 详情接口据此决定是否展示元信息卡片。 */
    public boolean hasValues() {
        return title != null || !authors.isEmpty() || subject != null || description != null
                || language != null || createdAt != null || modifiedAt != null || producer != null;
    }
}
