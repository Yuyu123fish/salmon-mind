package com.yuyu.salmonmind.knowledge.domain;

/** Tika 解析后的纯文本结果；正文为空时由 Worker 按格式决定 FAILED 或 OCR_REQUIRED。 */
public record ParsedDocument(String mediaType, String text, int pageCount, int textCharCount) {

    public ParsedDocument {
        if (mediaType == null || mediaType.isBlank() || text == null || pageCount < 0 || textCharCount < 0) {
            throw new IllegalArgumentException("ParsedDocument 内容不完整");
        }
    }
}
