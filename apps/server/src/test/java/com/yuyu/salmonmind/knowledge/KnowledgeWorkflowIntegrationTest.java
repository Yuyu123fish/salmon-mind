package com.yuyu.salmonmind.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyu.salmonmind.knowledge.api.DocumentDetail;
import com.yuyu.salmonmind.knowledge.api.DocumentSummary;
import com.yuyu.salmonmind.knowledge.api.DocumentUpload;
import com.yuyu.salmonmind.knowledge.api.EvidencePage;
import com.yuyu.salmonmind.knowledge.api.KnowledgeService;
import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.api.UploadInitRequest;
import com.yuyu.salmonmind.knowledge.api.UploadSessionView;
import com.yuyu.salmonmind.knowledge.application.KnowledgeUploadApplicationService;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.ObjectStoragePort;
import com.yuyu.salmonmind.knowledge.application.port.ResumableUploadStoragePort;
import com.yuyu.salmonmind.knowledge.application.KnowledgeUploadObjectKeys;
import com.yuyu.salmonmind.knowledge.domain.ParsedDocumentMetadata;
import com.yuyu.salmonmind.model.embedding.EmbeddingException;
import com.yuyu.salmonmind.model.embedding.EmbeddingResult;
import com.yuyu.salmonmind.model.embedding.EmbeddingService;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** S2-02~S2-04 端到端门禁：原件、元数据、Stream、解析、Embedding、索引和 READY 发布。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test",
                "salmon.knowledge.worker.enabled=true",
                "salmon.knowledge.worker.reclaim-idle=1s",
                "salmon.knowledge.worker.max-auto-retries=1",
                "salmon.knowledge.worker.repair-interval=1s",
                "salmon.knowledge.max-object-bytes=1048576",
                "salmon.knowledge.upload.resumable-threshold-bytes=1",
                "salmon.knowledge.upload.part-size-bytes=65536",
                "salmon.knowledge.upload.max-concurrent-parts=2"
        }
)
@Import(KnowledgeWorkflowIntegrationTest.DeterministicEmbeddingConfiguration.class)
class KnowledgeWorkflowIntegrationTest {

    private static final String RUSTFS_ACCESS_KEY = "salmonmind";
    private static final String RUSTFS_SECRET_KEY = "salmonmind-test-secret";
    private static final String RUSTFS_BUCKET = "salmon-knowledge-test";
    private static final String SEARCH_INDEX_PREFIX = "salmon-workflow-evidence-"
            + java.util.UUID.randomUUID().toString().replace("-", "");

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

    @Container
    static final GenericContainer<?> RUSTFS = new GenericContainer<>("rustfs/rustfs:1.0.0-beta.12")
            .withCommand("/data")
            .withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000")
            .withEnv("RUSTFS_CONSOLE_ADDRESS", "0.0.0.0:9001")
            .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
            .withEnv("RUSTFS_ACCESS_KEY", RUSTFS_ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", RUSTFS_SECRET_KEY)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200));

    @Autowired
    private KnowledgeService knowledge;

    @Autowired
    private KnowledgeMetadataPort metadata;

    @Autowired
    private ObjectStoragePort objectStorage;

    @Autowired
    private EvidenceIndexPort evidenceIndex;

    @Autowired
    private ResumableUploadStoragePort resumableStorage;

    @Autowired
    private KnowledgeUploadApplicationService resumableUploads;

    @Autowired
    private WorkspaceRegistry workspaceRegistry;

    @Autowired
    private DeterministicEmbeddingService embedding;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("salmon.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("salmon.knowledge.search.base-url",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
        registry.add("salmon.knowledge.search.index-prefix", () -> SEARCH_INDEX_PREFIX);
        registry.add("salmon.knowledge.content-store.endpoint",
                () -> "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000));
        registry.add("salmon.knowledge.content-store.access-key", () -> RUSTFS_ACCESS_KEY);
        registry.add("salmon.knowledge.content-store.secret-key", () -> RUSTFS_SECRET_KEY);
        registry.add("salmon.knowledge.content-store.bucket", () -> RUSTFS_BUCKET);
    }

    @Test
    void uploadReturnsQuicklyPersistsOriginalDispatchesAndPublishesReady() throws Exception {
        byte[] original = "第一段知识内容。\n\n第二段知识内容。".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant startedAt = Instant.now();

        DocumentSummary accepted = knowledge.upload(new DocumentUpload(
                "guide.txt", "text/plain", new ByteArrayInputStream(original)));

        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(3));
        assertThat(accepted.name()).isEqualTo("guide.txt");
        assertThat(accepted.sizeBytes()).isEqualTo(original.length);
        assertThat(accepted.sha256()).hasSize(64);
        assertThat(accepted.state()).isIn("PENDING_DISPATCH", "QUEUED", "PARSING", "EMBEDDING", "INDEXING", "READY");

        KnowledgeMetadataPort.StoredDocument stored = awaitReady(accepted.id());
        assertThat(stored.latestJob().state().name()).isEqualTo("READY");
        assertThat(stored.evidenceCount()).isGreaterThan(0);
        assertThat(stored.latestJob().streamMessageId()).isNotBlank();

        Path downloaded = Files.createTempFile("salmon-knowledge-original-", ".txt");
        try {
            objectStorage.download(stored.revision().objectKey(), downloaded);
            assertThat(Files.readAllBytes(downloaded)).containsExactly(original);
        } finally {
            Files.deleteIfExists(downloaded);
        }

        DocumentDetail detail = knowledge.detail(accepted.id());
        assertThat(detail.jobs()).singleElement().satisfies(job -> {
            assertThat(job.state()).isEqualTo("READY");
            assertThat(job.attemptNumber()).isEqualTo(1);
        });
        EvidencePage evidence = knowledge.evidence(accepted.id(), 0, 20);
        assertThat(evidence.total()).isGreaterThan(0);
        assertThat(evidence.items()).extracting(item -> item.text()).anyMatch(text -> text.contains("第一段"));
    }

    @Test
    void parsedMetadataRoundTripsAsTypedJsonbAndOldRevisionDefaultsToEmpty() {
        UUID workspaceId = workspaceRegistry.current().id();
        DocumentSummary accepted = knowledge.upload(new DocumentUpload(
                "metadata-only.txt", "text/plain", new ByteArrayInputStream("metadata body".getBytes(StandardCharsets.UTF_8))));
        assertThat(metadata.findRevision(accepted.revisionId()).parsedMetadata().hasValues()).isFalse();

        ParsedDocumentMetadata expected = new ParsedDocumentMetadata(
                "Round-trip 标题", List.of("作者一", "作者二"), "主题", "描述", "zh-CN",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"), "测试工具");
        metadata.updateParseMetadata(accepted.revisionId(), 2, 12, expected);

        KnowledgeMetadataPort.StoredRevision actual = metadata.findRevision(accepted.revisionId());
        assertThat(actual.pageCount()).isEqualTo(2);
        assertThat(actual.textCharCount()).isEqualTo(12);
        assertThat(actual.parsedMetadata()).isEqualTo(expected);
    }

    @Test
    void workerPersistsParsedMetadataBeforePublishingReady() throws Exception {
        DocumentSummary accepted = knowledge.upload(new DocumentUpload(
                "metadata-worker.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new ByteArrayInputStream(minimalDocx())));

        KnowledgeMetadataPort.StoredDocument stored = awaitReady(accepted.id());

        assertThat(stored.revision().parsedMetadata().title()).isEqualTo("测试文档标题");
        assertThat(stored.revision().parsedMetadata().authors()).containsExactly("测试作者");
        assertThat(stored.revision().parsedMetadata().subject()).isEqualTo("测试主题");
        assertThat(stored.revision().parsedMetadata().createdAt())
                .isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    void resumableUploadUsesRealRedisReceiptsRustFsObjectsAndIdempotentPostgresSubmission() throws Exception {
        byte[] original = ("可恢复上传正文。".repeat(3_000)).getBytes(StandardCharsets.UTF_8);
        UUID workspaceId = workspaceRegistry.current().id();
        UploadSessionView initialized = resumableUploads.init(new UploadInitRequest(
                "chunked.txt", "text/plain", original.length,
                "chunked.txt|" + original.length + "|42", 42));

        int partSize = initialized.partSizeBytes();
        for (int part = 1; part <= initialized.totalParts(); part++) {
            int offset = (part - 1) * partSize;
            int length = Math.min(partSize, original.length - offset);
            byte[] bytes = java.util.Arrays.copyOfRange(original, offset, offset + length);
            UploadSessionView progress = resumableUploads.putPart(initialized.sessionId(), part, length,
                    sha256(bytes), new ByteArrayInputStream(bytes));
            assertThat(progress.confirmedBytes()).isEqualTo(Math.min((long) part * partSize, original.length));
        }

        DocumentSummary accepted = resumableUploads.complete(initialized.sessionId());
        DocumentSummary repeated = resumableUploads.complete(initialized.sessionId());
        assertThat(repeated.id()).isEqualTo(accepted.id());
        KnowledgeMetadataPort.StoredDocument submitted = metadata.find(workspaceId, accepted.id());
        assertThat(submitted).isNotNull();
        assertThat(submitted.revision().sizeBytes()).isEqualTo(original.length);
        assertThat(submitted.revision().sha256()).isEqualTo(sha256(original));
        assertThat(submitted.latestJob().state().name())
                .isIn("PENDING_DISPATCH", "QUEUED", "PARSING", "EMBEDDING", "INDEXING", "READY");

        UploadSessionView canceled = resumableUploads.init(new UploadInitRequest(
                "cancelled.txt", "text/plain", original.length, "cancelled|" + original.length, 43));
        int cancelLength = Math.min(canceled.partSizeBytes(), original.length);
        byte[] cancelBytes = java.util.Arrays.copyOf(original, cancelLength);
        resumableUploads.putPart(canceled.sessionId(), 1, cancelLength, sha256(cancelBytes),
                new ByteArrayInputStream(cancelBytes));
        resumableUploads.cancel(canceled.sessionId());
        String bucket = LocalDate.now(ZoneOffset.UTC).toString().replace("-", "");
        String prefix = KnowledgeUploadObjectKeys.partPrefix(bucket, workspaceId, canceled.sessionId());
        assertThat(resumableStorage.listObjects(prefix, null, 100).objects()).isEmpty();
    }

    @Test
    void allWhitelistedFormatsCanReachReady() throws Exception {
        assertReady("notes.md", "text/markdown", "# Markdown 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8));
        assertReady("report.pdf", "application/pdf", minimalPdf());
        assertReady("letter.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", minimalDocx());
    }

    @Test
    void retryReusesRevisionAfterRetryableEmbeddingFailure() throws Exception {
        embedding.failNext(2);
        DocumentSummary accepted = knowledge.upload(new DocumentUpload(
                "retry.txt", "text/plain", new ByteArrayInputStream("需要重试的正文".getBytes(StandardCharsets.UTF_8))));

        KnowledgeMetadataPort.StoredDocument failed = awaitState(accepted.id(), "FAILED");
        assertThat(failed.latestJob().retryable()).isTrue();
        UUID revisionId = failed.revision().id();
        UUID failedJobId = failed.latestJob().id();

        DocumentSummary retried = knowledge.retry(accepted.id());
        assertThat(retried.revisionId()).isEqualTo(revisionId);
        assertThat(retried.latestJobId()).isNotEqualTo(failedJobId);
        assertThat(retried.state()).isIn("PENDING_DISPATCH", "QUEUED", "PARSING", "EMBEDDING", "INDEXING", "READY");

        KnowledgeMetadataPort.StoredDocument ready = awaitReady(accepted.id());
        assertThat(ready.revision().id()).isEqualTo(revisionId);
        assertThat(ready.jobs()).hasSize(2);
        assertThat(ready.jobs()).extracting(KnowledgeMetadataPort.StoredJob::attemptNumber)
                .containsExactly(2, 1);
    }

    @Test
    void deletesReadyDocumentAcrossPostgresElasticsearchAndRustFs() throws Exception {
        DocumentSummary accepted = knowledge.upload(new DocumentUpload(
                "delete-me.txt", "text/plain", new ByteArrayInputStream("需要完整删除的正文".getBytes(StandardCharsets.UTF_8))));
        KnowledgeMetadataPort.StoredDocument ready = awaitReady(accepted.id());
        KnowledgeMetadataPort.RetrievalScope before = metadata.currentRetrievalScope(
                workspaceRegistry.current().id(), 512);
        String physicalIndex = before.physicalIndex();
        UUID revisionId = ready.revision().id();
        String objectKey = ready.revision().objectKey();

        knowledge.delete(accepted.id());

        assertThat(metadata.find(workspaceRegistry.current().id(), accepted.id())).isNull();
        assertThat(knowledge.list()).noneMatch(document -> document.id().equals(accepted.id()));
        assertThat(evidenceIndex.countForRevision(physicalIndex, revisionId)).isZero();
        KnowledgeMetadataPort.RetrievalScope after = metadata.currentRetrievalScope(
                workspaceRegistry.current().id(), 512);
        assertThat(after == null ? List.<UUID>of() : after.readyRevisionIds()).doesNotContain(revisionId);
        assertThatThrownBy(() -> knowledge.detail(accepted.id()))
                .isInstanceOf(KnowledgeException.class)
                .extracting(error -> ((KnowledgeException) error).code())
                .isEqualTo(KnowledgeException.Code.DOCUMENT_NOT_FOUND);

        Path downloaded = Files.createTempFile("salmon-knowledge-deleted-", ".bin");
        try {
            assertThatThrownBy(() -> objectStorage.download(objectKey, downloaded))
                    .isInstanceOf(KnowledgeException.class)
                    .extracting(error -> ((KnowledgeException) error).code())
                    .isEqualTo(KnowledgeException.Code.OBJECT_STORAGE_UNAVAILABLE);
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }

    @Test
    void failedDocumentWithNoEvidenceCanBeDeletedAndCannotCrossWorkspace() throws Exception {
        embedding.failNext(2);
        DocumentSummary failed = knowledge.upload(new DocumentUpload(
                "failed-delete.txt", "text/plain", new ByteArrayInputStream("删除失败文档".getBytes(StandardCharsets.UTF_8))));
        KnowledgeMetadataPort.StoredDocument stored = awaitState(failed.id(), "FAILED");
        assertThat(stored.evidenceCount()).isZero();

        UUID otherWorkspace = UUID.randomUUID();
        assertThatThrownBy(() -> metadata.markDeleting(otherWorkspace, failed.id()))
                .isInstanceOf(KnowledgeException.class)
                .extracting(error -> ((KnowledgeException) error).code())
                .isEqualTo(KnowledgeException.Code.DOCUMENT_NOT_FOUND);
        assertThat(metadata.find(workspaceRegistry.current().id(), failed.id())).isNotNull();

        knowledge.delete(failed.id());

        assertThat(metadata.find(workspaceRegistry.current().id(), failed.id())).isNull();
    }

    private void assertReady(String fileName, String mediaType, byte[] content) throws Exception {
        DocumentSummary accepted = knowledge.upload(new DocumentUpload(
                fileName, mediaType, new ByteArrayInputStream(content)));
        KnowledgeMetadataPort.StoredDocument ready = awaitReady(accepted.id());
        assertThat(ready.latestJob().state().name()).isEqualTo("READY");
        assertThat(ready.evidenceCount()).isGreaterThan(0);
    }

    private KnowledgeMetadataPort.StoredDocument awaitReady(java.util.UUID documentId) throws InterruptedException {
        return awaitState(documentId, "READY");
    }

    private KnowledgeMetadataPort.StoredDocument awaitState(java.util.UUID documentId, String expectedState)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            KnowledgeMetadataPort.StoredDocument stored = metadata.find(workspaceRegistry.current().id(), documentId);
            if (stored != null && stored.latestJob() != null
                    && stored.latestJob().state().name().equals(expectedState)) {
                return stored;
            }
            if (stored != null && stored.latestJob() != null && stored.latestJob().state().terminal()) {
                if (!stored.latestJob().state().name().equals(expectedState)) {
                    fail("文档提前进入终态: " + knowledge.detail(documentId));
                }
            }
            Thread.sleep(250);
        }
        fail("文档未在 30 秒内进入 " + expectedState + ": " + knowledge.detail(documentId));
        return null;
    }

    private static byte[] minimalPdf() {
        return ("%PDF-1.4\n"
                + "1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n"
                + "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n"
                + "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 4 0 R >>endobj\n"
                + "4 0 obj<< /Length 39 >>stream\nBT /F1 12 Tf (PDF Gate) Tj ET\nendstream endobj\n"
                + "5 0 obj<< /Title (测试PDF标题) /Author (测试PDF作者) /Subject (测试主题) >>endobj\n"
                + "trailer<< /Root 1 0 R /Info 5 0 R >>\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] minimalDocx() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>");
            put(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>测试文档标题</dc:title><dc:creator>测试作者</dc:creator><dc:subject>测试主题</dc:subject><dcterms:created xsi:type=\"dcterms:W3CDTF\">2024-01-01T00:00:00Z</dcterms:created></cp:coreProperties>");
            put(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>DOCX Gate 文本</w:t></w:r></w:p><w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>");
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicEmbeddingConfiguration {

        @Bean
        @Primary
        DeterministicEmbeddingService deterministicEmbeddingService() {
            return new DeterministicEmbeddingService();
        }
    }

    static class DeterministicEmbeddingService implements EmbeddingService {

        private volatile boolean failNext;
        private volatile int failuresRemaining;

        void failNext(int count) {
            failNext = count > 0;
            failuresRemaining = Math.max(0, count);
        }

        @Override
        public EmbeddingResult embed(java.util.List<String> texts) {
            if (failNext) {
                failuresRemaining--;
                failNext = failuresRemaining > 0;
                throw new EmbeddingException(EmbeddingException.Code.FAILED, "测试 Embedding 暂时不可用");
            }
            return new EmbeddingResult(
                    "test",
                    "deterministic-embedding",
                    texts.stream().map(text -> Collections.nCopies(EmbeddingService.DIMENSIONS, 0.01f)).toList());
        }

    }
}
