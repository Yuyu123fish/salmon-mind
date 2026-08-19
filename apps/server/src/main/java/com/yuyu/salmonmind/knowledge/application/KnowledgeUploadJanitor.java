package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.ResumableUploadStoragePort;
import com.yuyu.salmonmind.knowledge.application.port.UploadSessionRepository;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeUploadConfigurationPort;
import com.yuyu.salmonmind.knowledge.domain.UploadSession;
import com.yuyu.salmonmind.knowledge.domain.UploadSessionStatus;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Upload 专属有限清理器。
 * Redis Session 存在时按精确 prefix 清理；Redis 全失时只扫描代码拥有的 part/final 前缀，
 * 以可信 lastModified、最长生命周期和 PostgreSQL final-reference Fence 收束孤儿。
 */
@Component
public class KnowledgeUploadJanitor implements SmartLifecycle {

    private final KnowledgeUploadConfigurationPort properties;
    private final UploadSessionRepository sessions;
    private final ResumableUploadStoragePort storage;
    private final KnowledgeMetadataPort metadata;
    private volatile ExecutorService executor;
    private volatile boolean running;
    /** 跨轮保存对象列表游标，避免固定页数预算每次都从根前缀重新扫描。 */
    private volatile String partOrphanContinuation;
    private volatile String finalOrphanContinuation;

    public KnowledgeUploadJanitor(KnowledgeUploadConfigurationPort properties, UploadSessionRepository sessions,
                                  ResumableUploadStoragePort storage, KnowledgeMetadataPort metadata) {
        this.properties = properties;
        this.sessions = sessions;
        this.storage = storage;
        this.metadata = metadata;
    }

    @Override
    public synchronized void start() {
        if (!properties.resumableEnabled() || running) return;
        running = true;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "salmon-knowledge-upload-janitor");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::runLoop);
    }

    @Override
    public synchronized void stop() {
        running = false;
        ExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
            try { current.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public boolean isAutoStartup() { return properties.resumableEnabled(); }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    /** 供集成测试和运维诊断执行一次有界 sweep；不会触碰共享 knowledge/documents 前缀。 */
    public void sweepOnce(Instant now) {
        sweepSessions(now);
        sweepOrphans(now);
    }

    private void runLoop() {
        while (running) {
            try {
                sweepOnce(Instant.now());
                Thread.sleep(properties.cleanupInterval().toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // 单轮 Redis/S3/DB 故障只延后下一轮，不终止清理线程。
            }
        }
    }

    private void sweepSessions(Instant now) {
        for (UploadSession session : sessions.list(properties.cleanupBatchSize())) {
            try {
                if (session.status() == UploadSessionStatus.COMPLETING) {
                    KnowledgeMetadataPort.Submission submission = metadata.findSubmissionByObjectKey(
                            session.workspaceId(), session.finalObjectKey());
                    if (submission != null) {
                        sessions.markCompleted(session.workspaceId(), session.id(), submission.sourceId());
                        continue;
                    }
                }
                if ((session.status() == UploadSessionStatus.UPLOADING
                        || session.status() == UploadSessionStatus.COMPLETING)
                        && !now.isBefore(session.expiresAt())
                        && (session.completionLeaseUntil() == null || !now.isBefore(session.completionLeaseUntil()))) {
                    session = sessions.markExpired(session.workspaceId(), session.id());
                }
                if (session.status() == UploadSessionStatus.ABORTED
                        || session.status() == UploadSessionStatus.EXPIRED
                        || session.status() == UploadSessionStatus.FAILED) {
                    deleteSessionObjects(session);
                }
            } catch (RuntimeException ignored) {
                // 下轮继续；未知状态不扩大删除范围。
            }
        }
    }

    private void deleteSessionObjects(UploadSession session) {
        listAndDelete(session.partPrefix());
        // final 可能已经完成 PostgreSQL 提交；数据库不可用或引用不确定时必须保留。
        if (!hasRevisionReference(session.finalObjectKey())) {
            try { storage.deleteObject(session.finalObjectKey()); } catch (RuntimeException ignored) { }
        }
    }

    private void sweepOrphans(Instant now) {
        Instant cutoff = now.minus(properties.maxSessionLifetime()).minus(properties.orphanGrace());
        listOrphans(KnowledgeUploadObjectKeys.PART_ROOT, cutoff, false);
        listOrphans(KnowledgeUploadObjectKeys.FINAL_ROOT, cutoff, true);
    }

    private void listOrphans(String prefix, Instant cutoff, boolean finals) {
        String token = finals ? finalOrphanContinuation : partOrphanContinuation;
        int pages = 0;
        int deleted = 0;
        Instant deadline = Instant.now().plusSeconds(20);
        do {
            if (++pages > 20 || deleted >= properties.cleanupBatchSize() || Instant.now().isAfter(deadline)) {
                setOrphanContinuation(finals, token);
                return;
            }
            ResumableUploadStoragePort.ObjectPage page;
            try { page = storage.listObjects(prefix, token, Math.min(100, properties.cleanupBatchSize())); }
            catch (RuntimeException ex) {
                setOrphanContinuation(finals, token);
                return;
            }
            for (ResumableUploadStoragePort.ObjectHead object : page.objects()) {
                if (deleted >= properties.cleanupBatchSize() || !object.lastModified().isBefore(cutoff)) continue;
                if (finals && hasRevisionReference(object.objectKey())) continue;
                try { storage.deleteObject(object.objectKey()); deleted++; } catch (RuntimeException ignored) { }
            }
            token = page.truncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        setOrphanContinuation(finals, token);
    }

    private void setOrphanContinuation(boolean finals, String token) {
        if (finals) finalOrphanContinuation = token;
        else partOrphanContinuation = token;
    }

    private boolean hasRevisionReference(String objectKey) {
        UUID workspaceId = workspaceFromFinalKey(objectKey);
        if (workspaceId == null) return true;
        try {
            return metadata.findSubmissionByObjectKey(workspaceId, objectKey) != null;
        } catch (RuntimeException ex) {
            // DB 引用状态不确定时宁可保留 final，不能误删已提交文档。
            return true;
        }
    }

    private UUID workspaceFromFinalKey(String key) {
        if (key == null || !key.startsWith(KnowledgeUploadObjectKeys.FINAL_ROOT)) return null;
        String[] parts = key.substring(KnowledgeUploadObjectKeys.FINAL_ROOT.length()).split("/");
        if (parts.length != 3 || !parts[2].endsWith(".bin")) return null;
        try { return UUID.fromString(parts[1]); } catch (IllegalArgumentException ex) { return null; }
    }

    private void listAndDelete(String prefix) {
        String token = null;
        int pages = 0;
        do {
            if (++pages > 20) return;
            ResumableUploadStoragePort.ObjectPage page;
            try { page = storage.listObjects(prefix, token, Math.min(100, properties.cleanupBatchSize())); }
            catch (RuntimeException ex) { return; }
            for (ResumableUploadStoragePort.ObjectHead object : page.objects()) {
                try { storage.deleteObject(object.objectKey()); } catch (RuntimeException ignored) { }
            }
            token = page.truncated() ? page.nextContinuationToken() : null;
        } while (token != null);
    }
}
