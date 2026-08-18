package com.yuyu.salmonmind.knowledge.api;

import java.util.UUID;

/**
 * READY 文档的可读 Evidence 预览；正文来自 Elasticsearch，不来自 PostgreSQL。
 * location 是解析器能提供的位置，无法稳定定位时使用 section/chunk 序号，不伪造页码。
 */
public record EvidencePreview(
        UUID id,
        int ordinal,
        String location,
        String text,
        int charCount
) {
}
