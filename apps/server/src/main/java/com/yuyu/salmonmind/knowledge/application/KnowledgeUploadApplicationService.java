package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.api.DocumentSummary;
import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.api.UploadInitRequest;
import com.yuyu.salmonmind.knowledge.api.UploadPartReceiptView;
import com.yuyu.salmonmind.knowledge.api.UploadPolicy;
import com.yuyu.salmonmind.knowledge.api.UploadSessionView;
import com.yuyu.salmonmind.knowledge.application.port.DocumentParserPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeUploadConfigurationPort;
import com.yuyu.salmonmind.knowledge.application.port.ResumableUploadStoragePort;
import com.yuyu.salmonmind.knowledge.application.port.UploadSessionRepository;
import com.yuyu.salmonmind.knowledge.domain.DocumentFormat;
import com.yuyu.salmonmind.knowledge.domain.PartReceipt;
import com.yuyu.salmonmind.knowledge.domain.UploadSession;
import com.yuyu.salmonmind.knowledge.domain.UploadSessionStatus;
import com.yuyu.salmonmind.persistence.redis.RedisClientUnavailableException;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 可恢复上传用例：负责策略、Session、part Receipt 和 complete 编排。
 * Redis 锁只覆盖元数据原子变更；RustFS I/O、临时归并和 PostgreSQL 提交均在锁外按固定顺序执行。
 */
@Service
public class KnowledgeUploadApplicationService {

    private static final int MAX_PARTS = 10_000;
    private static final String PART_MEDIA_TYPE = "application/octet-stream";

    private final WorkspaceRegistry workspaceRegistry;
    private final KnowledgeUploadPropertiesFacade properties;
    private final UploadSessionRepository sessions;
    private final ResumableUploadStoragePort storage;
    private final DocumentParserPort parser;
    private final KnowledgeMetadataPort metadata;
    private final KnowledgeQueuePort queue;

    public KnowledgeUploadApplicationService(
            WorkspaceRegistry workspaceRegistry,
            KnowledgeUploadConfigurationPort properties,
            UploadSessionRepository sessions,
            ResumableUploadStoragePort storage,
            DocumentParserPort parser,
            KnowledgeMetadataPort metadata,
            KnowledgeQueuePort queue,
            @Value("${salmon.knowledge.max-object-bytes:52428800}") long maxObjectBytes
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.properties = new KnowledgeUploadPropertiesFacade(properties, maxObjectBytes);
        this.sessions = sessions;
        this.storage = storage;
        this.parser = parser;
        this.metadata = metadata;
        this.queue = queue;
    }

    public UploadPolicy policy() {
        return new UploadPolicy(properties.resumableEnabled(), properties.maxObjectBytes(),
                properties.resumableThresholdBytes(), properties.partSizeBytes(), properties.maxConcurrentParts());
    }

    public UploadSessionView init(UploadInitRequest request) {
        requireEnabled();
        if (request == null || request.fileName() == null || request.declaredMediaType() == null
                || request.sizeBytes() <= 0 || request.fileFingerprint() == null
                || request.fileFingerprint().isBlank() || request.fileFingerprint().length() > 256
                || request.lastModifiedMillis() < 0) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "上传初始化参数无效");
        }
        String fileName = safeFileName(request.fileName());
        DocumentFormat format = formatOf(fileName);
        String declaredMediaType = normalizeDeclaredMediaType(request.declaredMediaType());
        if (!"application/octet-stream".equals(declaredMediaType)
                && !format.acceptsMediaType(declaredMediaType)) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "声明的文件类型与扩展名不匹配");
        }
        if (request.sizeBytes() > properties.maxObjectBytes()) {
            throw new KnowledgeException(KnowledgeException.Code.FILE_TOO_LARGE, "文件超过大小限制");
        }
        int totalParts = (int) ((request.sizeBytes() + properties.partSizeBytes() - 1L)
                / properties.partSizeBytes());
        if (totalParts < 1 || totalParts > MAX_PARTS) {
            throw new KnowledgeException(KnowledgeException.Code.FILE_TOO_LARGE, "文件分片数量超过限制");
        }
        UUID workspaceId = workspaceRegistry.current().id();
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant hardExpiresAt = now.plus(properties.maxSessionLifetime());
        Instant expiresAt = now.plus(properties.sessionIdleTtl()).isBefore(hardExpiresAt)
                ? now.plus(properties.sessionIdleTtl()) : hardExpiresAt;
        String bucket = LocalDate.now(ZoneOffset.UTC).toString().replace("-", "");
        String partPrefix = KnowledgeUploadObjectKeys.partPrefix(bucket, workspaceId, sessionId);
        String finalKey = KnowledgeUploadObjectKeys.finalKey(bucket, workspaceId, sessionId);
        UploadSession session = new UploadSession(sessionId, workspaceId, fileName,
                declaredMediaType, request.sizeBytes(), request.fileFingerprint(),
                request.lastModifiedMillis(), properties.partSizeBytes(), totalParts, properties.maxConcurrentParts(),
                now, expiresAt, hardExpiresAt, UploadSessionStatus.UPLOADING, partPrefix, finalKey,
                null, null, null, null, null);
        return view(sessions.create(session));
    }

    public UploadSessionView get(UUID sessionId) {
        return get(sessionId, null);
    }

    /**
     * 读取会话；重新选择文件时可带快速指纹，让服务端在任何已确认 part 之前也能阻止错误续传。
     * 指纹只用于比较，不会进入对外 Session View。
     */
    public UploadSessionView get(UUID sessionId, String fileFingerprint) {
        UploadSession session = requireSession(sessionId);
        if (fileFingerprint != null && !fileFingerprint.isBlank()
                && !fileFingerprint.equals(session.fileFingerprint())) {
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT,
                    "所选文件与已有上传会话不一致");
        }
        Instant now = Instant.now();
        if ((session.status() == UploadSessionStatus.UPLOADING || session.status() == UploadSessionStatus.COMPLETING)
                && (!now.isBefore(session.expiresAt()) || !now.isBefore(session.hardExpiresAt()))) {
            session = sessions.markExpired(workspaceId(), sessionId);
            cleanupSession(session);
        }
        if (session.status() == UploadSessionStatus.COMPLETING) {
            session = repairDatabaseCompletion(session);
        }
        return view(session);
    }

    public UploadSessionView putPart(UUID sessionId, int partNumber, long contentLength, String checksum,
                              InputStream input) {
        requireEnabled();
        UploadSession session = requireSession(sessionId);
        if (contentLength < 0 || checksum == null || !checksum.matches("[0-9a-fA-F]{64}")) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD_PART, "上传分片参数无效");
        }
        int expectedLength;
        try {
            expectedLength = session.expectedPartLength(partNumber);
        } catch (IllegalArgumentException ex) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD_PART, "上传分片参数无效");
        }
        if (contentLength != expectedLength) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD_PART, "上传分片长度不符合策略");
        }
        Instant now = Instant.now();
        UploadSessionRepository.PartReservationResult reservation = sessions.reservePart(
                workspaceId(), sessionId, partNumber, contentLength, checksum.toLowerCase(Locale.ROOT), now,
                now.plus(Duration.ofMinutes(5)));
        if (reservation.alreadyConfirmed()) return view(reservation.session());
        Path temp = null;
        String objectKey = reservation.session().partPrefix() + partNumber + "-" + checksum.toLowerCase(Locale.ROOT) + ".part";
        try {
            temp = Files.createTempFile("salmon-upload-part-", ".tmp");
            String actual = copyAndHash(input, temp, expectedLength);
            if (!actual.equalsIgnoreCase(checksum)) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_CHECKSUM_MISMATCH, "上传分片校验失败");
            }
            storage.putObject(temp, objectKey, PART_MEDIA_TYPE);
            PartReceipt receipt = new PartReceipt(partNumber, objectKey, contentLength, actual, Instant.now());
            UploadSession committed = sessions.commitReceipt(workspaceId(), sessionId, partNumber,
                    reservation.token(), receipt, Instant.now());
            return view(committed);
        } catch (KnowledgeException ex) {
            sessions.releaseReservation(workspaceId(), sessionId, partNumber, reservation.token());
            throw ex;
        } catch (IOException ex) {
            sessions.releaseReservation(workspaceId(), sessionId, partNumber, reservation.token());
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD_PART, "上传分片读取失败", ex);
        } catch (RuntimeException ex) {
            sessions.releaseReservation(workspaceId(), sessionId, partNumber, reservation.token());
            throw ex;
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }

    public DocumentSummary complete(UUID sessionId) {
        requireEnabled();
        UploadSession session = requireSession(sessionId);
        if (session.status() == UploadSessionStatus.COMPLETED) return summaryForCompleted(session);
        if (session.status() == UploadSessionStatus.COMPLETING) {
            UploadSession repaired = repairDatabaseCompletion(session);
            if (repaired.status() == UploadSessionStatus.COMPLETED) return summaryForCompleted(repaired);
        }
        Instant now = Instant.now();
        UploadSessionRepository.CompletionFence fence = sessions.fenceCompletion(
                workspaceId(), sessionId, now, now.plus(Duration.ofMinutes(5)));
        if (fence.alreadyCompleted()) {
            return summaryForCompleted(fence.session());
        }
        if (!fence.acquired()) {
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传会话正在完成，请稍后重试");
        }
        session = fence.session();
        Path merged = null;
        try {
            merged = mergeReceipts(session);
            String detected = parser.detect(merged);
            DocumentFormat format = formatOf(session.fileName());
            validateMediaType(format, session.declaredMediaType(), detected);
            String sha256 = hash(merged);
            String finalMediaType = normalizeMediaType(session.declaredMediaType(), detected);
            putAndVerifyFinal(merged, session.finalObjectKey(), finalMediaType, sha256, session.sizeBytes());

            KnowledgeMetadataPort.Submission submission = findOrCreateSubmission(session, format, detected, sha256);
            dispatch(submission);
            try {
                session = sessions.markCompleted(workspaceId(), session.id(), submission.sourceId());
            } catch (KnowledgeException ex) {
                if (ex.code() != KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE) throw ex;
                // PostgreSQL + 固定 final key 已是完成权威，Redis 投影可由 GET/下次 complete 修复。
            }
            cleanupParts(session);
            return summaryForSubmission(submission);
        } catch (KnowledgeException ex) {
            if (ex.code() == KnowledgeException.Code.UPLOAD_FINAL_VALIDATION_FAILED
                    || ex.code() == KnowledgeException.Code.UPLOAD_CHECKSUM_MISMATCH
                    || ex.code() == KnowledgeException.Code.INVALID_UPLOAD) {
                failAndCleanup(session, ex.code().name());
            }
            throw ex;
        } catch (IOException ex) {
            // 临时文件/归并 I/O 中断不等于内容不合法；保留 COMPLETING，让下一次 complete
            // 复用冻结 Receipt 重试，避免在本地资源抖动时误删可继续协调的对象。
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE, "上传归并暂时不可用", ex);
        } catch (DataIntegrityViolationException ex) {
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE, "上传提交暂时不可用", ex);
        } finally {
            if (merged != null) {
                try { Files.deleteIfExists(merged); } catch (IOException ignored) { }
            }
        }
    }

    public void cancel(UUID sessionId) {
        UploadSession session = requireSession(sessionId);
        if (session.status() == UploadSessionStatus.COMPLETED
                || session.status() == UploadSessionStatus.COMPLETING) {
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传已完成，不能取消");
        }
        session = sessions.markAborted(workspaceId(), sessionId);
        cleanupSession(session);
    }

    void cleanupSession(UploadSession session) {
        cleanupPrefix(session.partPrefix());
        try { storage.deleteObject(session.finalObjectKey()); } catch (RuntimeException ignored) { }
    }

    private UploadSession repairDatabaseCompletion(UploadSession session) {
        KnowledgeMetadataPort.Submission existing = metadata.findSubmissionByObjectKey(session.workspaceId(), session.finalObjectKey());
        if (existing == null) return session;
        try { return sessions.markCompleted(session.workspaceId(), session.id(), existing.sourceId()); }
        catch (KnowledgeException ex) {
            if (ex.code() == KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE) return session;
            throw ex;
        }
    }

    private Path mergeReceipts(UploadSession session) throws IOException {
        Path merged = Files.createTempFile("salmon-upload-merged-", ".tmp");
        try (OutputStream output = Files.newOutputStream(merged, StandardOpenOption.TRUNCATE_EXISTING)) {
            long total = 0;
            for (PartReceipt receipt : session.orderedReceipts()) {
                Path part = Files.createTempFile("salmon-upload-receipt-", ".tmp");
                try {
                    storage.downloadObject(receipt.objectKey(), part);
                    if (Files.size(part) != receipt.sizeBytes()) {
                        throw new KnowledgeException(KnowledgeException.Code.UPLOAD_FINAL_VALIDATION_FAILED, "上传内容校验失败");
                    }
                    String actual = hash(part);
                    if (!actual.equalsIgnoreCase(receipt.sha256())) {
                        throw new KnowledgeException(KnowledgeException.Code.UPLOAD_FINAL_VALIDATION_FAILED, "上传内容校验失败");
                    }
                    total += receipt.sizeBytes();
                    if (total > session.sizeBytes()) {
                        throw new KnowledgeException(KnowledgeException.Code.UPLOAD_FINAL_VALIDATION_FAILED, "上传内容校验失败");
                    }
                    Files.copy(part, output);
                } finally {
                    Files.deleteIfExists(part);
                }
            }
            if (total != session.sizeBytes()) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_INCOMPLETE, "上传分片尚未全部确认");
            }
        } catch (RuntimeException | IOException ex) {
            try { Files.deleteIfExists(merged); } catch (IOException ignored) { }
            throw ex;
        }
        return merged;
    }

    /**
     * 写入固定 final key 后再次读取并校验，覆盖 PutObject 响应不确定或代理重试造成的协调窗口。
     * 只有同一 key 的对象通过大小和整文件 SHA-256 校验，complete 才会继续提交 PostgreSQL。
     */
    private void putAndVerifyFinal(Path merged, String objectKey, String mediaType, String sha256, long expectedSize) {
        try {
            storage.putObject(merged, objectKey, mediaType);
        } catch (RuntimeException putFailure) {
            if (!finalObjectMatches(objectKey, mediaType, sha256, expectedSize)) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE,
                        "最终对象写入状态暂时不可确认", putFailure);
            }
        }
        if (!finalObjectMatches(objectKey, mediaType, sha256, expectedSize)) {
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_FINAL_VALIDATION_FAILED, "最终对象校验失败");
        }
    }

    private boolean finalObjectMatches(String objectKey, String mediaType, String sha256, long expectedSize) {
        try {
            ResumableUploadStoragePort.ObjectHead head = storage.headObject(objectKey);
            if (head.sizeBytes() != expectedSize) return false;
            if (head.mediaType() != null && !head.mediaType().isBlank()
                    && !normalizeDeclaredMediaType(head.mediaType()).equalsIgnoreCase(mediaType)) return false;
            Path downloaded = Files.createTempFile("salmon-upload-final-verify-", ".tmp");
            try {
                storage.downloadObject(objectKey, downloaded);
                return Files.size(downloaded) == expectedSize && hash(downloaded).equalsIgnoreCase(sha256);
            } finally {
                Files.deleteIfExists(downloaded);
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private KnowledgeMetadataPort.Submission findOrCreateSubmission(UploadSession session, DocumentFormat format,
                                                                      String detected, String sha256) {
        KnowledgeMetadataPort.Submission existing = metadata.findSubmissionByObjectKey(session.workspaceId(), session.finalObjectKey());
        if (existing != null) return validateExisting(existing, session, format, detected, sha256);
        try {
            return metadata.createSubmission(session.workspaceId(), session.fileName(), format,
                    normalizeMediaType(session.declaredMediaType(), detected), detected, session.sizeBytes(), sha256,
                    session.finalObjectKey());
        } catch (DataIntegrityViolationException ex) {
            // createSubmission 的事务已经由 Spring 回滚；竞争回查发生在新的调用事务中。
            KnowledgeMetadataPort.Submission winner = metadata.findSubmissionByObjectKey(
                    session.workspaceId(), session.finalObjectKey());
            if (winner == null) throw ex;
            return validateExisting(winner, session, format, detected, sha256);
        }
    }

    private KnowledgeMetadataPort.Submission validateExisting(KnowledgeMetadataPort.Submission submission,
                                                                UploadSession session, DocumentFormat format,
                                                                String detected, String sha256) {
        KnowledgeMetadataPort.StoredDocument document = metadata.find(session.workspaceId(), submission.sourceId());
        if (document == null || !document.revision().objectKey().equals(session.finalObjectKey())
                || document.revision().sizeBytes() != session.sizeBytes()
                || !document.revision().sha256().equalsIgnoreCase(sha256)
                || document.revision().format() != format
                || !document.revision().detectedMediaType().equalsIgnoreCase(detected)) {
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传提交结果不一致");
        }
        return submission;
    }

    private void dispatch(KnowledgeMetadataPort.Submission submission) {
        try {
            String messageId = queue.dispatch(submission.jobId(), submission.attemptNumber(), 1);
            metadata.markQueued(submission.jobId(), messageId);
        } catch (KnowledgeException ex) {
            if (ex.code() != KnowledgeException.Code.KNOWLEDGE_QUEUE_UNAVAILABLE) throw ex;
        }
    }

    private DocumentSummary summaryForCompleted(UploadSession session) {
        if (session.documentId() == null) {
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE, "上传完成状态暂时不可用");
        }
        KnowledgeMetadataPort.StoredDocument document = metadata.find(session.workspaceId(), session.documentId());
        if (document == null) throw new KnowledgeException(KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE, "上传完成状态暂时不可用");
        return toSummary(document);
    }

    private DocumentSummary summaryForSubmission(KnowledgeMetadataPort.Submission submission) {
        KnowledgeMetadataPort.StoredDocument document = metadata.find(workspaceId(), submission.sourceId());
        if (document == null) throw new KnowledgeException(KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE, "上传提交状态暂时不可用");
        return toSummary(document);
    }

    private UploadSession requireSession(UUID sessionId) {
        if (sessionId == null) throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_NOT_FOUND, "上传会话不存在");
        UploadSession session = sessions.find(workspaceId(), sessionId);
        if (session == null) throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_NOT_FOUND, "上传会话不存在");
        return session;
    }

    private UUID workspaceId() { return workspaceRegistry.current().id(); }

    private void requireEnabled() {
        if (!properties.resumableEnabled()) {
            throw new KnowledgeException(KnowledgeException.Code.RESUMABLE_UPLOAD_DISABLED, "可恢复上传当前未启用");
        }
    }

    private void cleanupPrefix(String prefix) {
        String token = null;
        try {
            do {
                ResumableUploadStoragePort.ObjectPage page = storage.listObjects(prefix, token, 100);
                for (ResumableUploadStoragePort.ObjectHead object : page.objects()) storage.deleteObject(object.objectKey());
                token = page.truncated() ? page.nextContinuationToken() : null;
            } while (token != null);
        } catch (RuntimeException ignored) {
            // Janitor 会在下轮按同一精确 prefix 重试；不扩大删除边界。
        }
    }

    private void cleanupParts(UploadSession session) { cleanupPrefix(session.partPrefix()); }

    private void failAndCleanup(UploadSession session, String code) {
        try { sessions.markFailed(session.workspaceId(), session.id(), code); } catch (RuntimeException ignored) { }
        cleanupSession(session);
    }

    private static String copyAndHash(InputStream input, Path target, long expected) throws IOException {
        MessageDigest digest = sha256Digest();
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > expected) throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD_PART, "上传分片长度不符合策略");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (total != expected) throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD_PART, "上传分片长度不符合策略");
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static DocumentFormat formatOf(String name) {
        try { return DocumentFormat.fromFileName(name); }
        catch (IllegalArgumentException ex) { throw new KnowledgeException(KnowledgeException.Code.UNSUPPORTED_FORMAT, "仅支持 TXT、MD、PDF、DOCX"); }
    }

    private static String safeFileName(String input) {
        String normalized = input.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.replaceAll("[\\p{Cntrl}]", "_").trim();
        if (name.isBlank() || name.equals(".") || name.equals("..")) throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "文件名无效");
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    private static String normalizeDeclaredMediaType(String value) {
        return value == null || value.isBlank() ? "application/octet-stream" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeMediaType(String declared, String detected) {
        String value = normalizeDeclaredMediaType(declared);
        return "application/octet-stream".equals(value) ? detected : value;
    }

    private static void validateMediaType(DocumentFormat format, String declared, String detected) {
        if (!format.acceptsMediaType(detected) || (declared != null && !declared.isBlank()
                && !format.compatibleMediaTypes(declared, detected))) {
            throw new KnowledgeException(KnowledgeException.Code.UPLOAD_FINAL_VALIDATION_FAILED, "上传内容类型校验失败");
        }
    }

    private static UploadSessionView view(UploadSession session) {
        List<UploadPartReceiptView> receipts = session.orderedReceipts().stream()
                .map(receipt -> new UploadPartReceiptView(receipt.partNumber(), receipt.sizeBytes(), receipt.sha256(), receipt.confirmedAt()))
                .toList();
        return new UploadSessionView(session.id(), session.status().name(), session.fileName(), session.declaredMediaType(),
                session.sizeBytes(), session.partSizeBytes(), session.totalParts(),
                receipts.stream().map(UploadPartReceiptView::partNumber).toList(), receipts,
                session.confirmedBytes(), session.expiresAt(), session.hardExpiresAt(), session.documentId(), session.failureCode());
    }

    private static DocumentSummary toSummary(KnowledgeMetadataPort.StoredDocument document) {
        KnowledgeMetadataPort.StoredRevision revision = document.revision();
        KnowledgeMetadataPort.StoredJob job = document.latestJob();
        String state = document.lifecycle().name();
        if (!"DELETING".equals(state)) state = job == null ? "PENDING_DISPATCH" : job.state().name();
        return new DocumentSummary(document.sourceId(), document.workspaceId(), revision.id(), job == null ? null : job.id(),
                document.name(), revision.format().name(), revision.mediaType(), revision.sizeBytes(), revision.sha256(), state,
                job != null && job.retryable(), document.evidenceCount(), document.createdAt(), document.updatedAt());
    }

    private record KnowledgeUploadPropertiesFacade(
            boolean resumableEnabled, long maxObjectBytes, long resumableThresholdBytes, int partSizeBytes,
            int maxConcurrentParts, Duration sessionIdleTtl, Duration maxSessionLifetime,
            String keyPrefix
    ) {
        KnowledgeUploadPropertiesFacade(KnowledgeUploadConfigurationPort p,
                                        long maxObjectBytes) {
            this(p.resumableEnabled(), p.maxObjectBytes(maxObjectBytes), p.resumableThresholdBytes(),
                    p.partSizeBytes(), p.maxConcurrentParts(), p.sessionIdleTtl(), p.maxSessionLifetime(),
                    p.keyPrefix());
        }
    }
}
