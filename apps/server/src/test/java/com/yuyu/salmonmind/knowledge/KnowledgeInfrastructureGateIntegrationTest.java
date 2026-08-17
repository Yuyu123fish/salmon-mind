package com.yuyu.salmonmind.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.yuyu.salmonmind.knowledge.application.port.DocumentParserPort;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.persistence.redis.RedissonClientProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** S2-01 硬 Gate：Redis Stream reclaim、Tika 四格式和 ES 2560 维 mapping。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test",
                "salmon.knowledge.worker.enabled=false",
                "salmon.knowledge.search.index-prefix=salmon-gate-evidence"
        }
)
class KnowledgeInfrastructureGateIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
            "docker.elastic.co/elasticsearch/elasticsearch:8.13.0")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200);

    @Autowired
    private DocumentParserPort parser;

    @Autowired
    private EvidenceIndexPort evidenceIndex;

    @Autowired
    private KnowledgeQueuePort queue;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("salmon.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("salmon.knowledge.search.base-url",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
        registry.add("salmon.knowledge.content-store.endpoint", () -> "");
    }

    @Test
    void redisStreamReadsAcknowledgesAndReclaimsPendingMessage() throws InterruptedException {
        UUID jobId = UUID.randomUUID();
        String messageId = queue.dispatch(jobId, 1);
        var first = queue.read("gate-consumer-a", 1, Duration.ofSeconds(2));
        assertThat(first).singleElement().satisfies(message -> {
            assertThat(message.messageId()).isEqualTo(messageId);
            assertThat(message.jobId()).isEqualTo(jobId);
        });

        Thread.sleep(80);
        var reclaimed = queue.reclaim("gate-consumer-b", Duration.ofMillis(1), 10);
        assertThat(reclaimed).singleElement().extracting(KnowledgeQueuePort.QueueMessage::messageId)
                .isEqualTo(messageId);
        queue.acknowledge(messageId);
    }

    @Test
    void tikaDetectsAndParsesTheFourSupportedFixtures() throws Exception {
        Path directory = Files.createTempDirectory("salmon-tika-gate-");
        try {
            Path txt = Files.writeString(directory.resolve("sample.txt"), "普通文本\n第二行", StandardCharsets.UTF_8);
            Path md = Files.writeString(directory.resolve("sample.md"), "# 标题\n\nMarkdown 正文", StandardCharsets.UTF_8);
            Path pdf = Files.write(directory.resolve("sample.pdf"), minimalPdf());
            Path docx = Files.write(directory.resolve("sample.docx"), minimalDocx());

            assertThat(parser.detect(txt)).startsWith("text/");
            assertThat(parser.detect(md)).startsWith("text/");
            assertThat(parser.detect(pdf)).isEqualTo("application/pdf");
            assertThat(parser.detect(docx)).isEqualTo(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertThatCode(() -> parser.parse(txt, parser.detect(txt))).doesNotThrowAnyException();
            assertThatCode(() -> parser.parse(md, parser.detect(md))).doesNotThrowAnyException();
            assertThatCode(() -> parser.parse(pdf, parser.detect(pdf))).doesNotThrowAnyException();
            assertThatCode(() -> parser.parse(docx, parser.detect(docx))).doesNotThrowAnyException();
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    void elasticsearchAcceptsTheFixed2560DimensionMappingAndEvidence() {
        String index = evidenceIndex.ensureIndex();
        UUID revisionId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        List<Float> vector = java.util.Collections.nCopies(2560, 0.01f);
        evidenceIndex.upsert(index, new EvidenceIndexPort.IndexedEvidence(
                evidenceId, revisionId, sourceId, 0, "section 1", "Gate 文本", vector, "a".repeat(64)));

        assertThat(evidenceIndex.countForRevision(index, revisionId)).isEqualTo(1);
        assertThat(evidenceIndex.pageForRevision(index, revisionId, 0, 10))
                .singleElement().extracting(EvidenceIndexPort.IndexedEvidence::id).isEqualTo(evidenceId);
    }

    @Test
    void bm25AndKnnApplyTheRevisionPrefilterBeforeReturningCandidates() {
        String index = evidenceIndex.ensureIndex();
        UUID readyRevision = UUID.randomUUID();
        UUID residualRevision = UUID.randomUUID();
        UUID readyEvidence = UUID.randomUUID();
        UUID residualEvidence = UUID.randomUUID();
        List<Float> readyVector = java.util.Collections.nCopies(2560, 0.02f);
        List<Float> residualVector = java.util.Collections.nCopies(2560, 0.03f);

        evidenceIndex.upsert(index, new EvidenceIndexPort.IndexedEvidence(
                readyEvidence, readyRevision, UUID.randomUUID(), 0, "section 1",
                "本地知识库 local knowledge", readyVector, "b".repeat(64)));
        evidenceIndex.upsert(index, new EvidenceIndexPort.IndexedEvidence(
                residualEvidence, residualRevision, UUID.randomUUID(), 0, "section 2",
                "本地知识库 local knowledge", residualVector, "c".repeat(64)));

        assertThat(evidenceIndex.searchText(index, "local", List.of(readyRevision), 40))
                .extracting(EvidenceIndexPort.RankedEvidence::id)
                .containsExactly(readyEvidence);
        assertThat(evidenceIndex.searchText(index, "知识库", List.of(readyRevision), 40))
                .extracting(EvidenceIndexPort.RankedEvidence::id)
                .containsExactly(readyEvidence);
        assertThat(evidenceIndex.searchVector(index, readyVector, List.of(readyRevision), 40, 200))
                .extracting(EvidenceIndexPort.RankedEvidence::id)
                .containsExactly(readyEvidence);
    }

    private static byte[] minimalPdf() {
        return ("%PDF-1.4\n"
                + "1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n"
                + "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n"
                + "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 4 0 R >>endobj\n"
                + "4 0 obj<< /Length 39 >>stream\nBT /F1 12 Tf (Gate) Tj ET\nendstream endobj\n"
                + "trailer<< /Root 1 0 R >>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] minimalDocx() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            put(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Gate 文本</w:t></w:r></w:p><w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>");
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }
}
