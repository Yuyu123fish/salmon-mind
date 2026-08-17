package com.yuyu.salmonmind.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentChunkerTest {

    @Test
    void preservesParagraphBoundariesAndUsesBoundedOverlapForLongSections() {
        DocumentChunker chunker = new DocumentChunker(20, 5);

        var chunks = chunker.chunk("标题\n这是第一段。\n\n这是一个需要被切开的长段落，包含足够多的文字用于验证窗口边界。\n\n末段");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text()).isNotBlank());
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(20));
        assertThat(chunks.get(0).location()).isEqualTo("section 1");
        assertThat(chunks.stream().map(DocumentChunk::ordinal).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void blankDocumentProducesNoChunks() {
        assertThat(new DocumentChunker().chunk("  \n\n  ")).isEmpty();
    }
}
