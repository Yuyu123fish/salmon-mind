package com.yuyu.salmonmind.knowledge.domain;

/** 结构优先切片后的最小索引单元；位置描述不伪造无法稳定取得的页码。 */
public record DocumentChunk(int ordinal, String location, String text) {

    public DocumentChunk {
        if (ordinal < 0 || location == null || location.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("DocumentChunk 内容不完整");
        }
    }

    public int charCount() {
        return text.length();
    }
}
