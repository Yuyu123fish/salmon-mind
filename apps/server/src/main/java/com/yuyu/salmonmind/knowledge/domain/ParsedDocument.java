package com.yuyu.salmonmind.knowledge.domain;

/** Tika 解析后的正文、结构化统计和有限元信息；正文为空时由 Worker 决定失败语义。 */
public record ParsedDocument(
        String mediaType,
        String text,
        int pageCount,
        int textCharCount,
        ParsedDocumentMetadata metadata
) {

    /** 兼容仅关心正文统计的解析器测试和内部调用方。 */
    public ParsedDocument(String mediaType, String text, int pageCount, int textCharCount) {
        this(mediaType, text, pageCount, textCharCount, ParsedDocumentMetadata.empty());
    }

    public ParsedDocument {
        if (mediaType == null || mediaType.isBlank() || text == null || pageCount < 0 || textCharCount < 0
                || metadata == null) {
            throw new IllegalArgumentException("ParsedDocument 内容不完整");
        }
    }
}
