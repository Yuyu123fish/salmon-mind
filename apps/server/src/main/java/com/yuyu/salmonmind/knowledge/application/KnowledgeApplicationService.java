package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.api.DocumentDetail;
import com.yuyu.salmonmind.knowledge.api.DocumentSummary;
import com.yuyu.salmonmind.knowledge.api.DocumentUpload;
import com.yuyu.salmonmind.knowledge.api.EvidencePage;
import com.yuyu.salmonmind.knowledge.api.EvidencePreview;
import com.yuyu.salmonmind.knowledge.api.IngestionJobView;
import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.api.KnowledgeService;
import com.yuyu.salmonmind.knowledge.application.port.DocumentParserPort;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.knowledge.application.port.ObjectStoragePort;
import com.yuyu.salmonmind.knowledge.domain.DocumentFormat;
import com.yuyu.salmonmind.knowledge.domain.IngestionJobState;
import com.yuyu.salmonmind.knowledge.domain.KnowledgeSourceLifecycle;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Knowledge 对外用例编排：同步接收原件，提交 PostgreSQL 后再投递 Stream；
 * 解析、Embedding、索引和 READY 发布全部交给后台 Worker。
 */
@Service
class KnowledgeApplicationService implements KnowledgeService {

    private final WorkspaceRegistry workspaceRegistry;
    private final KnowledgeMetadataPort metadata;
    private final KnowledgeQueuePort queue;
    private final ObjectStoragePort objectStorage;
    private final DocumentParserPort parser;
    private final EvidenceIndexPort index;
    private final KnowledgeDeletion deletion;
    private final long maxObjectBytes;

    KnowledgeApplicationService(
            WorkspaceRegistry workspaceRegistry,
            KnowledgeMetadataPort metadata,
            KnowledgeQueuePort queue,
            ObjectStoragePort objectStorage,
            DocumentParserPort parser,
            EvidenceIndexPort index,
            KnowledgeDeletion deletion,
            @Value("${salmon.knowledge.max-object-bytes:52428800}") long maxObjectBytes
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.metadata = metadata;
        this.queue = queue;
        this.objectStorage = objectStorage;
        this.parser = parser;
        this.index = index;
        this.deletion = deletion;
        this.maxObjectBytes = maxObjectBytes;
    }

    @Override
    public DocumentSummary upload(DocumentUpload upload) {
        Path temp = null;
        try {
            String name = safeFileName(upload.fileName());
            DocumentFormat format = formatOf(name);
            temp = Files.createTempFile("salmon-knowledge-", ".upload");
            String sha256 = copyAndHash(upload.content(), temp);
            long size = Files.size(temp);
            if (size == 0 && (format == DocumentFormat.TEXT || format == DocumentFormat.MARKDOWN)) {
                // Tika 对空文本通常只能给出 octet-stream；空文档允许进入异步失败状态，
                // 这样上传仍保持快速返回，且 Worker 不会把空正文发布为 READY。
                String detected = "text/plain";
                validateMediaType(format, upload.declaredMediaType(), detected);
                return persistAndDispatch(name, upload.declaredMediaType(), detected, format, size, sha256, temp);
            }
            String detected = parser.detect(temp);
            validateMediaType(format, upload.declaredMediaType(), detected);
            return persistAndDispatch(name, upload.declaredMediaType(), detected, format, size, sha256, temp);
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "读取上传文件失败", ex);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败只记录在本地文件系统层，不改变已经提交的业务状态。
                }
            }
        }
    }

    private DocumentSummary persistAndDispatch(
            String name,
            String declaredMediaType,
            String detectedMediaType,
            DocumentFormat format,
            long size,
            String sha256,
            Path temp
        ) {
        String objectKey = "knowledge/documents/" + UUID.randomUUID() + ".bin";
        boolean stored = false;
        boolean metadataCommitted = false;
        try {
            String mediaType = normalizeMediaType(declaredMediaType, detectedMediaType);
            objectStorage.put(temp, objectKey, mediaType);
            stored = true;
            KnowledgeMetadataPort.Submission submission = metadata.createSubmission(
                    workspaceRegistry.current().id(), name, format, mediaType, detectedMediaType,
                    size, sha256, objectKey);
            metadataCommitted = true;
            try {
                String messageId = queue.dispatch(submission.jobId(), submission.attemptNumber(), 1);
                metadata.markQueued(submission.jobId(), messageId);
            } catch (KnowledgeException ex) {
                if (ex.code() != KnowledgeException.Code.KNOWLEDGE_QUEUE_UNAVAILABLE) {
                    throw ex;
                }
                // 原件与 PostgreSQL 已是权威，保留 PENDING_DISPATCH，后台补投器稍后修复。
            }
            return toSummary(requireDocument(submission.sourceId()));
        } catch (RuntimeException ex) {
            // PostgreSQL 提交后原件已经属于该 Revision；即使响应失败，也必须保留它，
            // 让 PENDING_DISPATCH 补投器或后续诊断继续以数据库为准。
            if (stored && !metadataCommitted) {
                objectStorage.deleteBestEffort(objectKey);
            }
            throw ex;
        }
    }

    @Override
    public List<DocumentSummary> list() {
        return metadata.list(workspaceRegistry.current().id()).stream()
                .map(KnowledgeApplicationService::toSummary).toList();
    }

    @Override
    public DocumentDetail detail(UUID documentId) {
        KnowledgeMetadataPort.StoredDocument document = requireDocument(documentId);
        return new DocumentDetail(
                toSummary(document),
                document.jobs().stream().map(KnowledgeApplicationService::toJobView).toList(),
                document.revision().pageCount(), document.revision().textCharCount(),
                document.revision().parsedMetadata());
    }

    @Override
    public EvidencePage evidence(UUID documentId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "Evidence 分页参数无效");
        }
        KnowledgeMetadataPort.StoredDocument document = requireDocument(documentId);
        if (document.lifecycle() != KnowledgeSourceLifecycle.ACTIVE) {
            throw new KnowledgeException(KnowledgeException.Code.DOCUMENT_DELETE_NOT_ALLOWED,
                    "文档正在删除，不能读取切片");
        }
        if (document.latestJob() == null || document.latestJob().state() != IngestionJobState.READY) {
            throw new KnowledgeException(KnowledgeException.Code.DOCUMENT_NOT_READY, "文档尚未完成入库");
        }
        String indexName = index.ensureIndex();
        int offset = page * size;
        long total = index.countForRevision(indexName, document.revision().id());
        List<EvidencePreview> items = index.pageForRevision(indexName, document.revision().id(), offset, size)
                .stream().map(item -> new EvidencePreview(
                        item.id(), item.ordinal(), item.location(), item.text(), item.charCount())).toList();
        return new EvidencePage(items, page, size, total);
    }

    @Override
    public DocumentSummary retry(UUID documentId) {
        KnowledgeMetadataPort.StoredJob retry = metadata.createRetry(workspaceRegistry.current().id(), documentId);
        try {
            String messageId = queue.dispatch(retry.id(), retry.attemptNumber(), 1);
            metadata.markQueued(retry.id(), messageId);
        } catch (KnowledgeException ex) {
            if (ex.code() != KnowledgeException.Code.KNOWLEDGE_QUEUE_UNAVAILABLE) {
                throw ex;
            }
        }
        return toSummary(requireDocument(documentId));
    }

    @Override
    public void delete(UUID documentId) {
        deletion.delete(workspaceRegistry.current().id(), documentId);
    }

    private KnowledgeMetadataPort.StoredDocument requireDocument(UUID documentId) {
        KnowledgeMetadataPort.StoredDocument document = metadata.find(workspaceRegistry.current().id(), documentId);
        if (document == null) {
            throw new KnowledgeException(KnowledgeException.Code.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        return document;
    }

    private static DocumentFormat formatOf(String name) {
        try {
            return DocumentFormat.fromFileName(name);
        } catch (IllegalArgumentException ex) {
            throw new KnowledgeException(KnowledgeException.Code.UNSUPPORTED_FORMAT, "仅支持 TXT、MD、PDF、DOCX");
        }
    }

    private String copyAndHash(InputStream input, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maxObjectBytes) {
                    throw new KnowledgeException(KnowledgeException.Code.FILE_TOO_LARGE, "文件超过 50 MiB 限制");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String safeFileName(String input) {
        String normalized = input.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.replaceAll("[\\p{Cntrl}]", "_").trim();
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "文件名无效");
        }
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    private static String normalizeMediaType(String declared, String detected) {
        if (declared == null || declared.isBlank() || declared.equalsIgnoreCase("application/octet-stream")) {
            return detected;
        }
        return declared.split(";", 2)[0].trim().toLowerCase();
    }

    private static void validateMediaType(DocumentFormat format, String declared, String detected) {
        if (!format.acceptsMediaType(detected)
                || (declared != null && !declared.isBlank()
                && !format.compatibleMediaTypes(declared, detected))) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "文档扩展名、声明类型与实际内容不一致");
        }
    }

    private static DocumentSummary toSummary(KnowledgeMetadataPort.StoredDocument document) {
        KnowledgeMetadataPort.StoredRevision revision = document.revision();
        KnowledgeMetadataPort.StoredJob job = document.latestJob();
        return new DocumentSummary(
                document.sourceId(), document.workspaceId(), revision.id(), job == null ? null : job.id(),
                document.name(), revision.format().name(), revision.mediaType(), revision.sizeBytes(), revision.sha256(),
                document.lifecycle() == KnowledgeSourceLifecycle.DELETING
                        ? KnowledgeSourceLifecycle.DELETING.name()
                        : job == null ? IngestionJobState.PENDING_DISPATCH.name() : job.state().name(),
                document.lifecycle() == KnowledgeSourceLifecycle.ACTIVE
                        && job != null && job.retryable(),
                document.evidenceCount(), document.createdAt(), document.updatedAt());
    }

    private static IngestionJobView toJobView(KnowledgeMetadataPort.StoredJob job) {
        return new IngestionJobView(job.id(), job.attemptNumber(), job.state().name(), job.retryable(),
                job.errorCode(), job.errorMessage(), job.createdAt(), job.updatedAt(), job.startedAt(), job.endedAt());
    }
}
