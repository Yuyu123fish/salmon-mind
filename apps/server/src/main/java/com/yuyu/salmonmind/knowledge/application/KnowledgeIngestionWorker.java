package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.DocumentParserPort;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.knowledge.application.port.ObjectStoragePort;
import com.yuyu.salmonmind.knowledge.domain.DocumentChunk;
import com.yuyu.salmonmind.knowledge.domain.DocumentChunker;
import com.yuyu.salmonmind.knowledge.domain.IngestionJobState;
import com.yuyu.salmonmind.knowledge.domain.ParsedDocument;
import com.yuyu.salmonmind.model.embedding.EmbeddingException;
import com.yuyu.salmonmind.model.embedding.EmbeddingResult;
import com.yuyu.salmonmind.model.embedding.EmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server 内有界文档 Worker：从 Redis Stream 取消息，按状态顺序完成解析、Embedding、
 * Elasticsearch 幂等写入，最后由 PostgreSQL 事务发布 READY。Stream 只在成功或终态
 * 失败后 ACK；处理中断会保留 Pending，供下一次 reclaim。
 */
@Component
class KnowledgeIngestionWorker implements SmartLifecycle {

    private final KnowledgeMetadataPort metadata;
    private final KnowledgeQueuePort queue;
    private final ObjectStoragePort objectStorage;
    private final DocumentParserPort parser;
    private final EvidenceIndexPort index;
    private final EmbeddingService embedding;
    private final DocumentChunker chunker;
    private final int batchSize;
    private final int embeddingBatchSize;
    private final int maxAutoRetries;
    private final boolean enabled;
    private final long reclaimIdleMillis;
    private final long repairIntervalMillis;
    private final String consumerName;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ExecutorService executor;
    private volatile long nextRepairAt;

    KnowledgeIngestionWorker(
            KnowledgeMetadataPort metadata,
            KnowledgeQueuePort queue,
            ObjectStoragePort objectStorage,
            DocumentParserPort parser,
            EvidenceIndexPort index,
            EmbeddingService embedding,
            @Value("${salmon.knowledge.chunk-max-chars:1200}") int chunkMaxChars,
            @Value("${salmon.knowledge.embedding-batch-size:32}") int embeddingBatchSize,
            @Value("${salmon.knowledge.worker.max-auto-retries:2}") int maxAutoRetries,
            @Value("${salmon.knowledge.worker.batch-size:4}") int batchSize,
            @Value("${salmon.knowledge.worker.enabled:true}") boolean enabled,
            @Value("${salmon.knowledge.worker.reclaim-idle:30s}") java.time.Duration reclaimIdle,
            @Value("${salmon.knowledge.worker.repair-interval:10s}") java.time.Duration repairInterval
    ) {
        this.metadata = metadata;
        this.queue = queue;
        this.objectStorage = objectStorage;
        this.parser = parser;
        this.index = index;
        this.embedding = embedding;
        this.chunker = new DocumentChunker(chunkMaxChars, DocumentChunker.DEFAULT_OVERLAP_CHARS);
        this.batchSize = Math.max(1, Math.min(batchSize, 32));
        this.embeddingBatchSize = Math.max(1, Math.min(embeddingBatchSize, 128));
        this.maxAutoRetries = Math.max(0, Math.min(maxAutoRetries, 5));
        this.enabled = enabled;
        this.reclaimIdleMillis = Math.max(1000, reclaimIdle.toMillis());
        this.repairIntervalMillis = Math.max(1000, repairInterval.toMillis());
        this.consumerName = "server-" + UUID.randomUUID();
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "salmon-knowledge-worker");
            thread.setDaemon(true);
            return thread;
        });
        nextRepairAt = 0;
        executor.submit(this::runLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        ExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void runLoop() {
        while (running.get()) {
            try {
                repairPendingDispatch();
                processMessages(queue.reclaim(consumerName, java.time.Duration.ofMillis(reclaimIdleMillis), batchSize), true);
                processMessages(queue.read(consumerName, batchSize, java.time.Duration.ofSeconds(1)), false);
            } catch (KnowledgeException ex) {
                if (ex.code() != KnowledgeException.Code.KNOWLEDGE_QUEUE_UNAVAILABLE) {
                    // 单轮意外不应结束 Worker；下轮仍会从队列/数据库恢复。
                    sleepQuietly(500);
                } else {
                    sleepQuietly(2000);
                }
            } catch (RuntimeException ex) {
                sleepQuietly(1000);
            }
        }
    }

    private void repairPendingDispatch() {
        long now = System.currentTimeMillis();
        if (now < nextRepairAt) {
            return;
        }
        nextRepairAt = now + repairIntervalMillis;
        for (KnowledgeMetadataPort.StoredJob job : metadata.pendingDispatch(batchSize)) {
            try {
                String messageId = queue.dispatch(job.id(), job.attemptNumber(), 1);
                metadata.markQueued(job.id(), messageId);
            } catch (KnowledgeException ex) {
                if (ex.code() == KnowledgeException.Code.KNOWLEDGE_QUEUE_UNAVAILABLE) {
                    return;
                }
                metadata.markFailed(job.id(), "QUEUE_DISPATCH_FAILED", "文档入队失败", true);
            }
        }
    }

    private void processMessages(List<KnowledgeQueuePort.QueueMessage> messages, boolean reclaimed) {
        for (KnowledgeQueuePort.QueueMessage message : messages) {
            if (message.attemptNumber() < 1 || message.deliveryAttempt() < 1
                    || message.jobId().equals(new UUID(0, 0))) {
                queue.acknowledge(message.messageId());
                continue;
            }
            processOne(message, reclaimed);
        }
    }

    private void processOne(KnowledgeQueuePort.QueueMessage message, boolean reclaimed) {
        KnowledgeMetadataPort.StoredJob job = metadata.findJob(message.jobId());
        if (job == null || job.attemptNumber() != message.attemptNumber()) {
            queue.acknowledge(message.messageId());
            return;
        }
        if (job.state().terminal()) {
            queue.acknowledge(message.messageId());
            return;
        }
        List<IngestionJobState> processableStates = reclaimed
                ? List.of(IngestionJobState.PENDING_DISPATCH, IngestionJobState.QUEUED,
                IngestionJobState.PARSING, IngestionJobState.EMBEDDING, IngestionJobState.INDEXING)
                : List.of(IngestionJobState.PENDING_DISPATCH, IngestionJobState.QUEUED);
        if (!metadata.transition(job.id(), processableStates, IngestionJobState.PARSING)) {
            // 另一 consumer 已取得同一 Job；当前消息可 ACK，真正的 Pending 由持有者负责。
            queue.acknowledge(message.messageId());
            return;
        }
        Path temp = null;
        try {
            KnowledgeMetadataPort.StoredRevision revision = metadata.findRevision(job.revisionId());
            if (revision == null) {
                throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_JOB_NOT_FOUND, "处理 Job 对应的 Revision 不存在");
            }
            temp = Files.createTempFile("salmon-knowledge-worker-", ".source");
            objectStorage.download(revision.objectKey(), temp);
            ParsedDocument parsed = parser.parse(temp, revision.detectedMediaType());
            metadata.updateParseMetadata(revision.id(), parsed.pageCount(), parsed.textCharCount());
            if (parsed.text().isBlank()) {
                if (revision.format() == com.yuyu.salmonmind.knowledge.domain.DocumentFormat.PDF) {
                    metadata.markOcrRequired(job.id(), "OCR_REQUIRED", "PDF 未提取到可检索正文");
                } else {
                    metadata.markFailed(job.id(), "EMPTY_DOCUMENT", "文档没有可索引正文", false);
                }
                queue.acknowledge(message.messageId());
                return;
            }
            List<DocumentChunk> chunks = chunker.chunk(parsed.text());
            if (chunks.isEmpty()) {
                metadata.markFailed(job.id(), "EMPTY_DOCUMENT", "文档没有可索引切片", false);
                queue.acknowledge(message.messageId());
                return;
            }
            if (!metadata.transition(job.id(), List.of(IngestionJobState.PARSING), IngestionJobState.EMBEDDING)) {
                queue.acknowledge(message.messageId());
                return;
            }
            List<EmbeddingResult> batches = embed(chunks);
            String indexName = index.ensureIndex();
            EmbeddingResult first = batches.get(0);
            KnowledgeMetadataPort.Generation generation = metadata.ensureActiveGeneration(
                    first.provider(), first.model(), indexName);
            if (!metadata.transition(job.id(), List.of(IngestionJobState.EMBEDDING), IngestionJobState.INDEXING)) {
                queue.acknowledge(message.messageId());
                return;
            }

            List<KnowledgeMetadataPort.PublishedEvidence> published = new ArrayList<>();
            int vectorOffset = 0;
            for (EmbeddingResult batch : batches) {
                for (List<Float> vector : batch.vectors()) {
                    DocumentChunk chunk = chunks.get(vectorOffset++);
                    UUID evidenceId = UUID.nameUUIDFromBytes(("salmon:evidence:" + generation.id()
                            + ":" + revision.id() + ":" + chunk.ordinal()).getBytes(StandardCharsets.UTF_8));
                    index.upsert(indexName, new EvidenceIndexPort.IndexedEvidence(
                            evidenceId, revision.id(), revision.sourceId(), chunk.ordinal(), chunk.location(),
                            chunk.text(), vector, revision.sha256()));
                    published.add(new KnowledgeMetadataPort.PublishedEvidence(
                            evidenceId, chunk.ordinal(), chunk.location(), revision.sha256(), chunk.charCount()));
                }
            }
            if (index.countForRevision(indexName, revision.id()) != chunks.size()) {
                throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                        "Evidence 数量校验未通过");
            }
            metadata.publishReady(job.id(), revision.id(), generation, published,
                    parsed.pageCount(), parsed.textCharCount());
            queue.acknowledge(message.messageId());
        } catch (KnowledgeException ex) {
            handleFailure(message, job, errorCode(ex), safeMessage(ex), retryable(ex));
        } catch (EmbeddingException ex) {
            handleFailure(message, job, embeddingErrorCode(ex), safeMessage(ex), true);
        } catch (RuntimeException ex) {
            handleFailure(message, job, "WORKER_FAILED", "文档处理暂时失败", true);
        } catch (IOException ex) {
            handleFailure(message, job, "WORKER_TEMP_FILE_FAILED", "文档临时文件处理失败", true);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 不删除用户原件；临时文件由系统清理机制兜底。
                }
            }
        }
    }

    private void handleFailure(KnowledgeQueuePort.QueueMessage message,
                               KnowledgeMetadataPort.StoredJob job,
                               String errorCode,
                               String errorMessage,
                               boolean retryable) {
        if (retryable && message.deliveryAttempt() <= maxAutoRetries
                && metadata.prepareAutomaticRetry(job.id())) {
            try {
                String nextMessageId = queue.dispatch(job.id(), job.attemptNumber(), message.deliveryAttempt() + 1);
                metadata.markQueued(job.id(), nextMessageId);
                queue.acknowledge(message.messageId());
                return;
            } catch (KnowledgeException ex) {
                // 新消息未可靠投递时保留旧 Pending；下一轮 reclaim 或补投器继续恢复。
                if (ex.code() == KnowledgeException.Code.KNOWLEDGE_QUEUE_UNAVAILABLE) {
                    return;
                }
            }
        }
        metadata.markFailed(job.id(), errorCode, errorMessage, retryable);
        queue.acknowledge(message.messageId());
    }

    private List<EmbeddingResult> embed(List<DocumentChunk> chunks) {
        List<EmbeddingResult> result = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += embeddingBatchSize) {
            int end = Math.min(chunks.size(), start + embeddingBatchSize);
            result.add(embedding.embed(chunks.subList(start, end).stream().map(DocumentChunk::text).toList()));
        }
        return result;
    }

    private static boolean retryable(KnowledgeException ex) {
        return switch (ex.code()) {
            case OBJECT_STORAGE_UNAVAILABLE, KNOWLEDGE_QUEUE_UNAVAILABLE,
                    EMBEDDING_MODEL_NOT_CONFIGURED, EMBEDDING_FAILED, KNOWLEDGE_INDEX_UNAVAILABLE -> true;
            default -> false;
        };
    }

    private static String errorCode(KnowledgeException ex) {
        return ex.code().name();
    }

    private static String embeddingErrorCode(EmbeddingException ex) {
        return switch (ex.code()) {
            case NOT_CONFIGURED -> KnowledgeException.Code.EMBEDDING_MODEL_NOT_CONFIGURED.name();
            case INVALID_RESPONSE -> KnowledgeException.Code.EMBEDDING_FAILED.name();
            case FAILED -> KnowledgeException.Code.EMBEDDING_FAILED.name();
        };
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "文档处理失败" : message;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis + ThreadLocalRandom.current().nextLong(100));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
