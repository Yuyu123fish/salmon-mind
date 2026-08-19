package com.yuyu.salmonmind.knowledge.infrastructure.tika;

import com.yuyu.salmonmind.knowledge.domain.ParsedDocument;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PDF;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/** Tika 白名单投影覆盖控制字符、多作者、时间和总大小边界。 */
class TikaDocumentParserTest {

    @Test
    void projectorKeepsOnlyBoundedWhitelistedMetadata() {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.TITLE, "  标题\u0000\t带空白  ");
        metadata.add(TikaCoreProperties.CREATOR, "作者一");
        metadata.add(TikaCoreProperties.CREATOR, "作者一");
        metadata.add(TikaCoreProperties.CREATOR, "作者二");
        metadata.set(TikaCoreProperties.DESCRIPTION, "x".repeat(20_000));
        metadata.set(TikaCoreProperties.CREATED, "not-a-date");
        metadata.set(PDF.PRODUCER, "生成器");
        metadata.set("unknown.binary", "不应进入领域记录");

        var projected = TikaMetadataProjector.project(metadata);

        assertThat(projected.title()).isEqualTo("标题 带空白");
        assertThat(projected.authors()).containsExactly("作者一", "作者二");
        assertThat(projected.createdAt()).isNull();
        assertThat(projected.producer()).isEqualTo("生成器");
        assertThat(projected.description()).hasSizeLessThanOrEqualTo(TikaMetadataProjector.MAX_FIELD_CHARS);
        assertThat(projected.toString().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(
                TikaMetadataProjector.MAX_SERIALIZED_BYTES + 256);
    }

    @Test
    void textParseReturnsTypedMetadataAndStats() throws Exception {
        var file = Files.createTempFile("salmon-tika-metadata-", ".txt");
        try {
            Files.writeString(file, "标题\n正文", StandardCharsets.UTF_8);
            ParsedDocument parsed = new TikaDocumentParser().parse(file, "text/plain");
            assertThat(parsed.mediaType()).startsWith("text/");
            assertThat(parsed.text()).contains("正文");
            assertThat(parsed.metadata()).isNotNull();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
