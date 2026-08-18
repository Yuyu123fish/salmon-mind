package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.domain.DocumentFormat;
import com.yuyu.salmonmind.knowledge.domain.IngestionJobState;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knowledge PostgreSQL Adapter：封装 Source/Revision/Job/Generation/Evidence 的 MyBatis
 * Entity 和状态更新。业务层只能通过 {@link KnowledgeMetadataPort} 观察状态，
 * READY 的发布由本类在一个事务内完成。
 */
@Repository
class PostgresKnowledgeMetadataRepository implements KnowledgeMetadataPort {

    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeRevisionMapper revisionMapper;
    private final KnowledgeIngestionJobMapper jobMapper;
    private final KnowledgeGenerationMapper generationMapper;
    private final KnowledgeEvidenceMapper evidenceMapper;

    PostgresKnowledgeMetadataRepository(
            KnowledgeSourceMapper sourceMapper,
            KnowledgeRevisionMapper revisionMapper,
            KnowledgeIngestionJobMapper jobMapper,
            KnowledgeGenerationMapper generationMapper,
            KnowledgeEvidenceMapper evidenceMapper
    ) {
        this.sourceMapper = sourceMapper;
        this.revisionMapper = revisionMapper;
        this.jobMapper = jobMapper;
        this.generationMapper = generationMapper;
        this.evidenceMapper = evidenceMapper;
    }

    @Override
    @Transactional
    public Submission createSubmission(
            UUID workspaceId,
            String name,
            DocumentFormat format,
            String declaredMediaType,
            String detectedMediaType,
            long sizeBytes,
            String sha256,
            String objectKey
    ) {
        Instant now = Instant.now();
        UUID sourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        KnowledgeSourceEntity source = new KnowledgeSourceEntity();
        source.setId(sourceId);
        source.setWorkspaceId(workspaceId);
        source.setName(name);
        source.setKind("DOCUMENT");
        source.setCreatedAt(now);
        sourceMapper.insert(source);

        KnowledgeRevisionEntity revision = new KnowledgeRevisionEntity();
        revision.setId(revisionId);
        revision.setSourceId(sourceId);
        revision.setRevisionNumber(1);
        revision.setName(name);
        revision.setFormat(format.name());
        revision.setMediaType(declaredMediaType);
        revision.setContentObjectKey(objectKey);
        revision.setContentSha256(sha256);
        revision.setSizeBytes(sizeBytes);
        revision.setDetectedMediaType(detectedMediaType);
        revision.setPageCount(0);
        revision.setTextCharCount(0);
        revision.setCreatedAt(now);
        revisionMapper.insert(revision);

        KnowledgeIngestionJobEntity job = new KnowledgeIngestionJobEntity();
        job.setId(jobId);
        job.setSourceRevisionId(revisionId);
        job.setAttemptNumber(1);
        job.setState(IngestionJobState.PENDING_DISPATCH.name());
        job.setRetryable(false);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);
        return new Submission(sourceId, revisionId, jobId, 1);
    }

    @Override
    @Transactional
    public void markQueued(UUID jobId, String messageId) {
        // XADD 后 Worker 可能已经抢到消息；同一事务内先锁住首个投递身份，
        // 再推进状态，避免并发补投把较早消息 ID 覆盖掉。若 Worker 已经前进，
        // 只补写尚未记录的身份，不把它退回 QUEUED。
        KnowledgeIngestionJobEntity messageUpdate = new KnowledgeIngestionJobEntity();
        messageUpdate.setStreamMessageId(messageId);
        jobMapper.update(messageUpdate, Wrappers.<KnowledgeIngestionJobEntity>lambdaUpdate()
                .eq(KnowledgeIngestionJobEntity::getId, jobId)
                .isNull(KnowledgeIngestionJobEntity::getStreamMessageId));

        KnowledgeIngestionJobEntity stateUpdate = new KnowledgeIngestionJobEntity();
        stateUpdate.setState(IngestionJobState.QUEUED.name());
        stateUpdate.setUpdatedAt(Instant.now());
        jobMapper.update(stateUpdate, Wrappers.<KnowledgeIngestionJobEntity>lambdaUpdate()
                .eq(KnowledgeIngestionJobEntity::getId, jobId)
                .eq(KnowledgeIngestionJobEntity::getState, IngestionJobState.PENDING_DISPATCH.name())
                .isNotNull(KnowledgeIngestionJobEntity::getStreamMessageId));
    }

    @Override
    @Transactional
    public void markAutomaticRetryQueued(UUID jobId, String messageId) {
        KnowledgeIngestionJobEntity update = new KnowledgeIngestionJobEntity();
        update.setStreamMessageId(messageId);
        update.setState(IngestionJobState.QUEUED.name());
        update.setUpdatedAt(Instant.now());
        jobMapper.update(update, Wrappers.<KnowledgeIngestionJobEntity>lambdaUpdate()
                .eq(KnowledgeIngestionJobEntity::getId, jobId)
                .in(KnowledgeIngestionJobEntity::getState,
                        IngestionJobState.PENDING_DISPATCH.name(),
                        IngestionJobState.QUEUED.name()));
    }

    @Override
    public List<StoredDocument> list(UUID workspaceId) {
        return sourceMapper.selectList(Wrappers.<KnowledgeSourceEntity>lambdaQuery()
                        .eq(KnowledgeSourceEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(KnowledgeSourceEntity::getCreatedAt))
                .stream()
                .map(source -> toDocument(source, latestRevision(source.getId())))
                .toList();
    }

    @Override
    public StoredDocument find(UUID workspaceId, UUID sourceId) {
        KnowledgeSourceEntity source = sourceMapper.selectOne(Wrappers.<KnowledgeSourceEntity>lambdaQuery()
                .eq(KnowledgeSourceEntity::getId, sourceId)
                .eq(KnowledgeSourceEntity::getWorkspaceId, workspaceId));
        if (source == null) {
            return null;
        }
        KnowledgeRevisionEntity revision = latestRevision(source.getId());
        return revision == null ? null : toDocument(source, revision);
    }

    @Override
    public StoredJob findJob(UUID jobId) {
        KnowledgeIngestionJobEntity entity = jobMapper.selectById(jobId);
        return entity == null ? null : toJob(entity);
    }

    @Override
    public StoredRevision findRevision(UUID revisionId) {
        KnowledgeRevisionEntity entity = revisionMapper.selectById(revisionId);
        return entity == null ? null : toRevision(entity);
    }

    @Override
    public List<StoredJob> pendingDispatch(int limit) {
        return jobMapper.selectList(Wrappers.<KnowledgeIngestionJobEntity>lambdaQuery()
                        .eq(KnowledgeIngestionJobEntity::getState, IngestionJobState.PENDING_DISPATCH.name())
                        .orderByAsc(KnowledgeIngestionJobEntity::getUpdatedAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 100))))
                .stream().map(PostgresKnowledgeMetadataRepository::toJob).toList();
    }

    @Override
    @Transactional
    public StoredJob createRetry(UUID workspaceId, UUID sourceId) {
        KnowledgeSourceEntity source = sourceMapper.selectOne(Wrappers.<KnowledgeSourceEntity>lambdaQuery()
                .eq(KnowledgeSourceEntity::getId, sourceId)
                .eq(KnowledgeSourceEntity::getWorkspaceId, workspaceId));
        if (source == null) {
            throw new KnowledgeException(KnowledgeException.Code.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        KnowledgeRevisionEntity revision = latestRevision(sourceId);
        List<KnowledgeIngestionJobEntity> jobs = jobsFor(revision.getId());
        KnowledgeIngestionJobEntity latest = jobs.stream()
                .max(Comparator.comparing(KnowledgeIngestionJobEntity::getAttemptNumber)).orElseThrow();
        if (!IngestionJobState.FAILED.name().equals(latest.getState()) || !Boolean.TRUE.equals(latest.getRetryable())) {
            throw new KnowledgeException(KnowledgeException.Code.REVISION_NOT_RETRYABLE, "当前文档状态不可重试");
        }
        Instant now = Instant.now();
        KnowledgeIngestionJobEntity retry = new KnowledgeIngestionJobEntity();
        retry.setId(UUID.randomUUID());
        retry.setSourceRevisionId(revision.getId());
        retry.setAttemptNumber(latest.getAttemptNumber() + 1);
        retry.setState(IngestionJobState.PENDING_DISPATCH.name());
        retry.setRetryable(false);
        retry.setCreatedAt(now);
        retry.setUpdatedAt(now);
        jobMapper.insert(retry);
        return toJob(retry);
    }

    @Override
    public boolean prepareAutomaticRetry(UUID jobId) {
        KnowledgeIngestionJobEntity update = new KnowledgeIngestionJobEntity();
        update.setState(IngestionJobState.PENDING_DISPATCH.name());
        update.setRetryable(false);
        update.setErrorCode(null);
        update.setErrorMessage(null);
        update.setStreamMessageId(null);
        update.setStartedAt(null);
        update.setEndedAt(null);
        update.setUpdatedAt(Instant.now());
        return jobMapper.update(update, Wrappers.<KnowledgeIngestionJobEntity>lambdaUpdate()
                .eq(KnowledgeIngestionJobEntity::getId, jobId)
                .in(KnowledgeIngestionJobEntity::getState,
                        IngestionJobState.PARSING.name(),
                        IngestionJobState.EMBEDDING.name(),
                        IngestionJobState.INDEXING.name())) > 0;
    }

    @Override
    public boolean transition(UUID jobId, Collection<IngestionJobState> expected, IngestionJobState target) {
        Instant now = Instant.now();
        KnowledgeIngestionJobEntity update = new KnowledgeIngestionJobEntity();
        update.setState(target.name());
        update.setUpdatedAt(now);
        if (target == IngestionJobState.PARSING) {
            update.setStartedAt(now);
        }
        return jobMapper.update(update, Wrappers.<KnowledgeIngestionJobEntity>lambdaUpdate()
                .eq(KnowledgeIngestionJobEntity::getId, jobId)
                .in(KnowledgeIngestionJobEntity::getState, expected.stream().map(Enum::name).toList())) > 0;
    }

    @Override
    public void updateParseMetadata(UUID revisionId, int pageCount, int textCharCount) {
        KnowledgeRevisionEntity update = new KnowledgeRevisionEntity();
        update.setPageCount(pageCount);
        update.setTextCharCount(textCharCount);
        revisionMapper.update(update, Wrappers.<KnowledgeRevisionEntity>lambdaUpdate()
                .eq(KnowledgeRevisionEntity::getId, revisionId));
    }

    @Override
    public void markOcrRequired(UUID jobId, String errorCode, String message) {
        finishFailure(jobId, IngestionJobState.OCR_REQUIRED, errorCode, message, false);
    }

    @Override
    public void markFailed(UUID jobId, String errorCode, String message, boolean retryable) {
        KnowledgeIngestionJobEntity update = new KnowledgeIngestionJobEntity();
        update.setState(IngestionJobState.FAILED.name());
        update.setRetryable(retryable);
        update.setErrorCode(errorCode);
        update.setErrorMessage(safeError(message));
        update.setUpdatedAt(Instant.now());
        update.setEndedAt(Instant.now());
        jobMapper.update(update, Wrappers.<KnowledgeIngestionJobEntity>lambdaUpdate()
                .eq(KnowledgeIngestionJobEntity::getId, jobId)
                .in(KnowledgeIngestionJobEntity::getState,
                        IngestionJobState.PARSING.name(),
                        IngestionJobState.EMBEDDING.name(),
                        IngestionJobState.INDEXING.name()));
    }

    @Override
    @Transactional
    public Generation ensureActiveGeneration(String provider, String model, String physicalIndex) {
        KnowledgeGenerationEntity active = generationMapper.selectOne(Wrappers.<KnowledgeGenerationEntity>lambdaQuery()
                .eq(KnowledgeGenerationEntity::getStatus, "ACTIVE"));
        if (active == null) {
            active = new KnowledgeGenerationEntity();
            active.setId(UUID.randomUUID());
            active.setPhysicalIndex(physicalIndex);
            active.setStatus("ACTIVE");
            active.setEmbeddingProvider(provider);
            active.setEmbeddingModel(model);
            active.setEmbeddingDimensions(com.yuyu.salmonmind.model.embedding.EmbeddingService.DIMENSIONS);
            active.setChunkVersion("chunk-v1");
            active.setMappingVersion("mapping-v1");
            active.setRevisionCount(0);
            active.setEvidenceCount(0);
            active.setCreatedAt(Instant.now());
            active.setActivatedAt(Instant.now());
            generationMapper.insert(active);
        }
        return toGeneration(active);
    }

    @Override
    @Transactional
    public void publishReady(
            UUID jobId,
            UUID revisionId,
            Generation generation,
            List<PublishedEvidence> evidence,
            int pageCount,
            int textCharCount
    ) {
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("空 Evidence 不得发布 READY");
        }
        KnowledgeIngestionJobEntity job = jobMapper.selectById(jobId);
        if (job == null || !IngestionJobState.INDEXING.name().equals(job.getState())) {
            return;
        }
        updateParseMetadata(revisionId, pageCount, textCharCount);
        evidenceMapper.delete(Wrappers.<KnowledgeEvidenceEntity>lambdaQuery()
                .eq(KnowledgeEvidenceEntity::getGenerationId, generation.id())
                .eq(KnowledgeEvidenceEntity::getSourceRevisionId, revisionId));
        for (PublishedEvidence item : evidence) {
            KnowledgeEvidenceEntity row = new KnowledgeEvidenceEntity();
            row.setId(item.id());
            row.setGenerationId(generation.id());
            row.setSourceRevisionId(revisionId);
            row.setOrdinal(item.ordinal());
            row.setLocation(item.location());
            row.setContentSha256(item.contentSha256());
            row.setCharCount(item.charCount());
            evidenceMapper.insert(row);
        }
        List<KnowledgeEvidenceEntity> allEvidence = evidenceMapper.selectList(Wrappers.<KnowledgeEvidenceEntity>lambdaQuery()
                .eq(KnowledgeEvidenceEntity::getGenerationId, generation.id()));
        KnowledgeGenerationEntity generationUpdate = new KnowledgeGenerationEntity();
        generationUpdate.setRevisionCount((int) allEvidence.stream()
                .map(KnowledgeEvidenceEntity::getSourceRevisionId).distinct().count());
        generationUpdate.setEvidenceCount(allEvidence.size());
        generationMapper.update(generationUpdate, Wrappers.<KnowledgeGenerationEntity>lambdaUpdate()
                .eq(KnowledgeGenerationEntity::getId, generation.id()));

        KnowledgeIngestionJobEntity update = new KnowledgeIngestionJobEntity();
        update.setState(IngestionJobState.READY.name());
        update.setRetryable(false);
        update.setErrorCode(null);
        update.setErrorMessage(null);
        update.setUpdatedAt(Instant.now());
        update.setEndedAt(Instant.now());
        jobMapper.update(update, Wrappers.<KnowledgeIngestionJobEntity>lambdaUpdate()
                .eq(KnowledgeIngestionJobEntity::getId, jobId)
                .eq(KnowledgeIngestionJobEntity::getState, IngestionJobState.INDEXING.name()));
    }

    @Override
    public RetrievalScope currentRetrievalScope(UUID workspaceId, int maxRevisionCount) {
        KnowledgeGenerationEntity active = generationMapper.selectOne(Wrappers.<KnowledgeGenerationEntity>lambdaQuery()
                .eq(KnowledgeGenerationEntity::getStatus, "ACTIVE"));
        if (active == null) {
            return null;
        }

        List<KnowledgeSourceEntity> sources = sourceMapper.selectList(Wrappers.<KnowledgeSourceEntity>lambdaQuery()
                .eq(KnowledgeSourceEntity::getWorkspaceId, workspaceId));
        if (sources.isEmpty()) {
            return new RetrievalScope(workspaceId, active.getId(), active.getPhysicalIndex(), List.of(), true);
        }
        List<UUID> sourceIds = sources.stream().map(KnowledgeSourceEntity::getId).toList();
        List<KnowledgeRevisionEntity> revisions = revisionMapper.selectList(Wrappers.<KnowledgeRevisionEntity>lambdaQuery()
                .in(KnowledgeRevisionEntity::getSourceId, sourceIds));
        if (revisions.isEmpty()) {
            return new RetrievalScope(workspaceId, active.getId(), active.getPhysicalIndex(), List.of(), true);
        }
        List<UUID> revisionIds = revisions.stream().map(KnowledgeRevisionEntity::getId).toList();
        List<KnowledgeEvidenceEntity> evidence = evidenceMapper.selectList(
                Wrappers.<KnowledgeEvidenceEntity>lambdaQuery()
                        .eq(KnowledgeEvidenceEntity::getGenerationId, active.getId())
                        .in(KnowledgeEvidenceEntity::getSourceRevisionId, revisionIds));
        if (evidence.isEmpty()) {
            return new RetrievalScope(workspaceId, active.getId(), active.getPhysicalIndex(), List.of(), true);
        }

        // 同一 Revision 可能保留一组旧 Evidence；只有最新 Job 仍为 READY 时才是当前可检索版本。
        List<KnowledgeIngestionJobEntity> jobs = jobMapper.selectList(
                Wrappers.<KnowledgeIngestionJobEntity>lambdaQuery()
                        .in(KnowledgeIngestionJobEntity::getSourceRevisionId, revisionIds));
        Map<UUID, KnowledgeIngestionJobEntity> latestJobs = new HashMap<>();
        for (KnowledgeIngestionJobEntity job : jobs) {
            KnowledgeIngestionJobEntity current = latestJobs.get(job.getSourceRevisionId());
            if (current == null || job.getAttemptNumber() > current.getAttemptNumber()) {
                latestJobs.put(job.getSourceRevisionId(), job);
            }
        }
        List<UUID> readyRevisionIds = evidence.stream()
                .map(KnowledgeEvidenceEntity::getSourceRevisionId)
                .distinct()
                .filter(revisionId -> {
                    KnowledgeIngestionJobEntity job = latestJobs.get(revisionId);
                    return job != null && IngestionJobState.READY.name().equals(job.getState());
                })
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        int safeLimit = Math.max(1, maxRevisionCount);
        return new RetrievalScope(
                workspaceId,
                active.getId(),
                active.getPhysicalIndex(),
                readyRevisionIds,
                readyRevisionIds.size() <= safeLimit);
    }

    @Override
    public Map<UUID, ReadyEvidence> findReadyEvidence(RetrievalScope scope, Collection<UUID> evidenceIds) {
        if (scope == null || evidenceIds == null || evidenceIds.isEmpty() || !scope.hasReadyRevision()) {
            return Map.of();
        }
        List<UUID> requested = evidenceIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeEvidenceEntity> rows = evidenceMapper.selectList(
                Wrappers.<KnowledgeEvidenceEntity>lambdaQuery()
                        .eq(KnowledgeEvidenceEntity::getGenerationId, scope.generationId())
                        .in(KnowledgeEvidenceEntity::getId, requested)
                        .in(KnowledgeEvidenceEntity::getSourceRevisionId, scope.readyRevisionIds()));
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<UUID> revisionIds = rows.stream().map(KnowledgeEvidenceEntity::getSourceRevisionId).distinct().toList();
        List<KnowledgeRevisionEntity> revisions = revisionMapper.selectList(
                Wrappers.<KnowledgeRevisionEntity>lambdaQuery().in(KnowledgeRevisionEntity::getId, revisionIds));
        Map<UUID, KnowledgeRevisionEntity> revisionById = revisions.stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeRevisionEntity::getId, value -> value));
        List<UUID> sourceIds = revisions.stream().map(KnowledgeRevisionEntity::getSourceId).distinct().toList();
        List<KnowledgeSourceEntity> sources = sourceMapper.selectList(
                Wrappers.<KnowledgeSourceEntity>lambdaQuery()
                        .eq(KnowledgeSourceEntity::getWorkspaceId, scope.workspaceId())
                        .in(KnowledgeSourceEntity::getId, sourceIds));
        Map<UUID, KnowledgeSourceEntity> sourceById = sources.stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeSourceEntity::getId, value -> value));

        Map<UUID, ReadyEvidence> result = new LinkedHashMap<>();
        for (KnowledgeEvidenceEntity row : rows) {
            KnowledgeRevisionEntity revision = revisionById.get(row.getSourceRevisionId());
            if (revision == null || !scope.readyRevisionIds().contains(revision.getId())) {
                continue;
            }
            KnowledgeSourceEntity source = sourceById.get(revision.getSourceId());
            if (source == null) {
                continue;
            }
            result.put(row.getId(), new ReadyEvidence(
                    row.getId(), source.getId(), revision.getId(), source.getName(), row.getLocation(),
                    row.getContentSha256(), row.getOrdinal(), nullToZero(row.getCharCount())));
        }
        return result;
    }

    private void finishFailure(UUID jobId, IngestionJobState state, String errorCode, String message, boolean retryable) {
        KnowledgeIngestionJobEntity update = new KnowledgeIngestionJobEntity();
        update.setState(state.name());
        update.setRetryable(retryable);
        update.setErrorCode(errorCode);
        update.setErrorMessage(safeError(message));
        update.setUpdatedAt(Instant.now());
        update.setEndedAt(Instant.now());
        jobMapper.update(update, Wrappers.<KnowledgeIngestionJobEntity>lambdaUpdate()
                .eq(KnowledgeIngestionJobEntity::getId, jobId)
                .in(KnowledgeIngestionJobEntity::getState,
                        IngestionJobState.PARSING.name(),
                        IngestionJobState.EMBEDDING.name(),
                        IngestionJobState.INDEXING.name()));
    }

    private StoredDocument toDocument(KnowledgeSourceEntity source, KnowledgeRevisionEntity revision) {
        List<KnowledgeIngestionJobEntity> jobs = jobsFor(revision.getId());
        StoredJob latest = jobs.stream()
                .max(Comparator.comparing(KnowledgeIngestionJobEntity::getAttemptNumber))
                .map(PostgresKnowledgeMetadataRepository::toJob).orElse(null);
        int evidenceCount = Math.toIntExact(evidenceMapper.selectCount(Wrappers.<KnowledgeEvidenceEntity>lambdaQuery()
                .eq(KnowledgeEvidenceEntity::getSourceRevisionId, revision.getId())));
        Instant updatedAt = latest == null ? revision.getCreatedAt() : latest.updatedAt();
        return new StoredDocument(
                source.getId(), source.getWorkspaceId(), source.getName(), toRevision(revision), latest,
                jobs.stream().map(PostgresKnowledgeMetadataRepository::toJob).toList(), evidenceCount,
                source.getCreatedAt(), updatedAt);
    }

    private KnowledgeRevisionEntity latestRevision(UUID sourceId) {
        return revisionMapper.selectList(Wrappers.<KnowledgeRevisionEntity>lambdaQuery()
                        .eq(KnowledgeRevisionEntity::getSourceId, sourceId)
                        .orderByDesc(KnowledgeRevisionEntity::getRevisionNumber)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private List<KnowledgeIngestionJobEntity> jobsFor(UUID revisionId) {
        return jobMapper.selectList(Wrappers.<KnowledgeIngestionJobEntity>lambdaQuery()
                .eq(KnowledgeIngestionJobEntity::getSourceRevisionId, revisionId)
                .orderByDesc(KnowledgeIngestionJobEntity::getAttemptNumber));
    }

    private static StoredRevision toRevision(KnowledgeRevisionEntity entity) {
        return new StoredRevision(
                entity.getId(), entity.getSourceId(), entity.getName(), DocumentFormat.valueOf(entity.getFormat()),
                entity.getMediaType(), entity.getDetectedMediaType(), nullToZero(entity.getSizeBytes()),
                entity.getContentSha256(), entity.getContentObjectKey(), nullToZero(entity.getPageCount()),
                nullToZero(entity.getTextCharCount()), entity.getCreatedAt());
    }

    private static StoredJob toJob(KnowledgeIngestionJobEntity entity) {
        return new StoredJob(
                entity.getId(), entity.getSourceRevisionId(), entity.getAttemptNumber(),
                IngestionJobState.valueOf(entity.getState()), Boolean.TRUE.equals(entity.getRetryable()),
                entity.getErrorCode(), entity.getErrorMessage(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getStartedAt(), entity.getEndedAt(), entity.getStreamMessageId());
    }

    private static Generation toGeneration(KnowledgeGenerationEntity entity) {
        return new Generation(entity.getId(), entity.getPhysicalIndex(), entity.getEmbeddingProvider(),
                entity.getEmbeddingModel(), entity.getEmbeddingDimensions());
    }

    private static int nullToZero(Integer value) { return value == null ? 0 : value; }
    private static long nullToZero(Long value) { return value == null ? 0L : value; }
    private static String safeError(String message) {
        if (message == null || message.isBlank()) return "文档处理失败";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
