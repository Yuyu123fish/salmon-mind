package com.yuyu.salmonmind.knowledge.application.port;

import com.yuyu.salmonmind.knowledge.domain.DocumentFormat;
import com.yuyu.salmonmind.knowledge.domain.IngestionJobState;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knowledge PostgreSQL 权威边界。它只保存原件索引、处理状态和 Evidence 元数据；
 * 正文/向量仍由 Elasticsearch 持有，Redis 只保存投递状态。状态更新必须使用
 * Job ID 和期望状态做条件更新，避免旧消息覆盖新的处理尝试。
 */
public interface KnowledgeMetadataPort {

    /**
     * 在一个 PostgreSQL 事务内创建 Source、不可变 Revision 和首个 PENDING_DISPATCH Job。
     * objectKey 必须已经指向 RustFS 中成功写入的原件；返回的 Job 身份供事务提交后 XADD，
     * 任一数据库写入失败都不得留下可被 Worker 处理的半套元数据。
     */
    Submission createSubmission(
            UUID workspaceId,
            String name,
            DocumentFormat format,
            String declaredMediaType,
            String detectedMediaType,
            long sizeBytes,
            String sha256,
            String objectKey
    );

    /**
     * 记录 Stream 消息身份并尝试把 Job 从 PENDING_DISPATCH 推进到 QUEUED。
     * 调用方必须在 XADD 成功后调用；若 Worker 已经抢先推进状态，只补写首个消息身份，
     * 不得让并发补投覆盖它。数据库更新失败时由调用方保留待补投状态。
     */
    void markQueued(UUID jobId, String messageId);

    /**
     * 记录自动重试产生的新 Stream 消息，并把仍处于待投递或旧消息已登记状态的 Job 置为 QUEUED。
     * 它与首条上传消息的 {@link #markQueued(UUID, String)} 分开，是因为首条消息可能在上传线程
     * 回写前已被 Worker 消费；自动重试必须能够原子覆盖该竞态留下的旧消息身份，但不能覆盖
     * 已经进入 PARSING 或终态的并发处理结果。
     */
    void markAutomaticRetryQueued(UUID jobId, String messageId);

    /** 返回当前 Workspace 的文档摘要，按 Source 创建时间倒序排列。 */
    List<StoredDocument> list(UUID workspaceId);

    /** 按 Workspace 隔离读取文档；不存在时返回 {@code null}。 */
    StoredDocument find(UUID workspaceId, UUID sourceId);

    /** 读取单个 Job 的当前状态；不存在时返回 {@code null}。 */
    StoredJob findJob(UUID jobId);

    /** 读取不可变 Revision；不存在时返回 {@code null}。 */
    StoredRevision findRevision(UUID revisionId);

    /** 返回待投递 Job，供低频补投器限批修复数据库与 Stream 的双写缝隙。 */
    List<StoredJob> pendingDispatch(int limit);

    /** 为同一 Revision 创建下一次用户重试；前置条件是最新 Job 为可重试 FAILED。 */
    StoredJob createRetry(UUID workspaceId, UUID sourceId);

    /**
     * 原子地把 PARSING/EMBEDDING/INDEXING 置回 PENDING_DISPATCH。
     * 用于有限次消息层自动重试；不创建新 Revision 或 Job。返回 false 表示另一个
     * 消费者已改变状态，调用方不得覆盖其结果。
     */
    boolean prepareAutomaticRetry(UUID jobId);

    /**
     * 按 Job ID 和 expected 做条件状态更新；false 表示竞争失败或状态已变更。
     * PostgreSQL 的条件更新是旧消息不得覆盖新 attempt 的唯一权威判断。
     */
    boolean transition(UUID jobId, Collection<IngestionJobState> expected, IngestionJobState target);

    /** 更新解析得到的页数和正文字符数；只写 Revision 元数据，不改变 Job 状态。 */
    void updateParseMetadata(UUID revisionId, int pageCount, int textCharCount);

    /** 将当前处理 Job 收束为 OCR_REQUIRED；该状态不可由用户重试。 */
    void markOcrRequired(UUID jobId, String errorCode, String message);

    /** 将当前处理 Job 收束为 FAILED，并保存稳定错误码、诊断信息和可重试标记。 */
    void markFailed(UUID jobId, String errorCode, String message, boolean retryable);

    /** 返回当前 Active Generation；不存在时以给定模型和索引信息创建一次。 */
    Generation ensureActiveGeneration(String provider, String model, String physicalIndex);

    /**
     * 在 PostgreSQL 事务内写入 Evidence 元数据并将仍处于 INDEXING 的 Job 发布为 READY。
     * 调用方必须先完成 Elasticsearch 幂等写入和数量校验；不是 INDEXING 的旧消息会被忽略。
     */
    void publishReady(
            UUID jobId,
            UUID revisionId,
            Generation generation,
            List<PublishedEvidence> evidence,
            int pageCount,
            int textCharCount
    );

    /**
     * 读取当前 Workspace 可检索的 Active Generation 与 READY Revision 范围。
     * 返回的 revision 数量超过上限时仍保留完整集合，但标记为不可安全下推，
     * 调用方必须返回不可用而不能截断集合继续查询。
     */
    RetrievalScope currentRetrievalScope(UUID workspaceId, int maxRevisionCount);

    /** 按同一 Generation 二次校验候选 Evidence，并补全文档名、位置等 PostgreSQL 元数据。 */
    Map<UUID, ReadyEvidence> findReadyEvidence(RetrievalScope scope, Collection<UUID> evidenceIds);

    /** 上传事务提交后的稳定身份；后续 Stream 只携带 jobId 和 attemptNumber。 */
    record Submission(UUID sourceId, UUID revisionId, UUID jobId, int attemptNumber) {
    }

    /** 当前 Workspace 的文档、最新 Revision、最新 Job 和历史 Job。 */
    record StoredDocument(
            UUID sourceId,
            UUID workspaceId,
            String name,
            StoredRevision revision,
            StoredJob latestJob,
            List<StoredJob> jobs,
            int evidenceCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** 不可变原件版本；重试只复用 objectKey，不创建新 Revision。 */
    record StoredRevision(
            UUID id,
            UUID sourceId,
            String name,
            DocumentFormat format,
            String mediaType,
            String detectedMediaType,
            long sizeBytes,
            String sha256,
            String objectKey,
            int pageCount,
            int textCharCount,
            Instant createdAt
    ) {
    }

    /** 一次处理尝试；state/retryable/error 是 PostgreSQL 业务权威。 */
    record StoredJob(
            UUID id,
            UUID revisionId,
            int attemptNumber,
            IngestionJobState state,
            boolean retryable,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant endedAt,
            String streamMessageId
    ) {
    }

    /** Active Index Generation 的可追溯模型与物理索引信息。 */
    record Generation(UUID id, String physicalIndex, String provider, String model, int dimensions) {
    }

    /** 两路召回必须共同使用的 PostgreSQL READY 过滤范围。 */
    record RetrievalScope(
            UUID workspaceId,
            UUID generationId,
            String physicalIndex,
            List<UUID> readyRevisionIds,
            boolean revisionFilterBounded
    ) {
        public RetrievalScope {
            readyRevisionIds = readyRevisionIds == null ? List.of() : List.copyOf(readyRevisionIds);
        }

        public boolean hasReadyRevision() {
            return !readyRevisionIds.isEmpty();
        }
    }

    /** PostgreSQL 对 Evidence 身份和来源的权威补全；正文仍来自 Elasticsearch。 */
    record ReadyEvidence(
            UUID evidenceId,
            UUID sourceId,
            UUID revisionId,
            String documentName,
            String location,
            String contentSha256,
            int ordinal,
            int charCount
    ) {
    }

    /** READY 发布时写入 PostgreSQL 的 Evidence 元数据，不携带正文和向量。 */
    record PublishedEvidence(
            UUID id,
            int ordinal,
            String location,
            String contentSha256,
            int charCount
    ) {
    }
}
