package com.yuyu.salmonmind.knowledge;

import com.yuyu.salmonmind.knowledge.domain.ParsedDocumentMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 领域元信息只保持不可变集合，并能区分空记录与有值记录。 */
class ParsedDocumentMetadataTest {

    @Test
    void emptyMetadataIsStableAndAuthorsAreCopied() {
        var authors = new java.util.ArrayList<>(List.of("作者"));
        ParsedDocumentMetadata metadata = new ParsedDocumentMetadata(
                "标题", authors, null, null, "zh-CN", Instant.parse("2024-01-01T00:00:00Z"), null, "Writer");
        authors.add("不应泄漏");

        assertThat(metadata.hasValues()).isTrue();
        assertThat(metadata.authors()).containsExactly("作者");
        assertThat(ParsedDocumentMetadata.empty().hasValues()).isFalse();
        assertThat(ParsedDocumentMetadata.empty().authors()).isEmpty();
    }
}
