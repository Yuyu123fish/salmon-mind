package com.yuyu.salmonmind.knowledge.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.UploadSessionRepository;
import com.yuyu.salmonmind.knowledge.domain.PartReceipt;
import com.yuyu.salmonmind.knowledge.domain.PartReservation;
import com.yuyu.salmonmind.knowledge.domain.UploadSession;
import com.yuyu.salmonmind.knowledge.domain.UploadSessionStatus;
import com.yuyu.salmonmind.persistence.redis.RedisClientProvider;
import com.yuyu.salmonmind.persistence.redis.RedisClientUnavailableException;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScoredSortedSet;
import org.redisson.client.RedisException;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Versioned Redis Session Adapter。
 * 每个状态变更都在短锁内完成 JSON 整体替换；RustFS I/O 由应用服务在锁外执行。
 */
@Repository
class RedisUploadSessionRepository implements UploadSessionRepository {

    private final RedisClientProvider clientProvider;
    private final KnowledgeUploadProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final String sessionPrefix;
    private final String expiryIndexKey;

    RedisUploadSessionRepository(RedisClientProvider clientProvider, KnowledgeUploadProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
        this.sessionPrefix = properties.keyPrefix() + "session:";
        this.expiryIndexKey = properties.keyPrefix() + "expiry-index";
    }

    @Override
    public UploadSession create(UploadSession session) {
        return withLock(session.id(), () -> {
            UploadSession existing = read(session.id());
            if (existing != null) {
                if (!existing.workspaceId().equals(session.workspaceId())) {
                    throw notFound();
                }
                return existing;
            }
            write(session);
            return session;
        });
    }

    @Override
    public UploadSession find(UUID workspaceId, UUID sessionId) {
        try {
            UploadSession session = read(sessionId);
            return session == null || !session.workspaceId().equals(workspaceId) ? null : session;
        } catch (RedisClientUnavailableException | RedisException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public PartReservationResult reservePart(UUID workspaceId, UUID sessionId, int partNumber, long sizeBytes,
                                              String sha256, Instant now, Instant reservationExpiry) {
        return withLock(sessionId, () -> {
            UploadSession session = requireWorkspace(workspaceId, sessionId);
            if (session.status() != UploadSessionStatus.UPLOADING || !now.isBefore(session.expiresAt())
                    || !now.isBefore(session.hardExpiresAt())) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_EXPIRED, "上传会话已过期");
            }
            PartReceipt existing = session.receipts().get(partNumber);
            if (existing != null) {
                if (existing.sizeBytes() == sizeBytes && existing.sha256().equalsIgnoreCase(sha256)) {
                    return new PartReservationResult(session, null, true);
                }
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "该分片已确认但内容不一致");
            }
            Map<Integer, PartReservation> reservations = new LinkedHashMap<>(session.reservations());
            reservations.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            if (reservations.containsKey(partNumber) || reservations.size() >= session.maxConcurrentParts()) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传分片正在处理，请稍后重试");
            }
            String token = UUID.randomUUID().toString();
            reservations.put(partNumber, new PartReservation(token, now, reservationExpiry));
            UploadSession next = replace(session, session.receipts(), reservations, session.expiresAt());
            write(next);
            return new PartReservationResult(next, token, false);
        });
    }

    @Override
    public UploadSession commitReceipt(UUID workspaceId, UUID sessionId, int partNumber, String token,
                                       PartReceipt receipt, Instant now) {
        return withLock(sessionId, () -> {
            UploadSession session = requireWorkspace(workspaceId, sessionId);
            PartReceipt existing = session.receipts().get(partNumber);
            if (existing != null) {
                if (existing.sizeBytes() == receipt.sizeBytes() && existing.sha256().equalsIgnoreCase(receipt.sha256())) {
                    return session;
                }
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "该分片已确认但内容不一致");
            }
            PartReservation reservation = session.reservations().get(partNumber);
            if (reservation == null || !reservation.token().equals(token)
                    || !now.isBefore(reservation.expiresAt())
                    || !now.isBefore(session.expiresAt())
                    || !now.isBefore(session.hardExpiresAt())) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传分片 reservation 已失效");
            }
            if (session.status() != UploadSessionStatus.UPLOADING) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传会话已停止接收分片");
            }
            Instant nextExpiry = now.plus(properties.sessionIdleTtl()).isBefore(session.hardExpiresAt())
                    ? now.plus(properties.sessionIdleTtl()) : session.hardExpiresAt();
            UploadSession next = session.withReceipt(receipt, nextExpiry);
            write(next);
            return next;
        });
    }

    @Override
    public UploadSession releaseReservation(UUID workspaceId, UUID sessionId, int partNumber, String token) {
        return withLock(sessionId, () -> {
            UploadSession session = requireWorkspace(workspaceId, sessionId);
            PartReservation reservation = session.reservations().get(partNumber);
            if (reservation != null && (token == null || reservation.token().equals(token))) {
                UploadSession next = session.withoutReservation(partNumber);
                write(next);
                return next;
            }
            return session;
        });
    }

    @Override
    public CompletionFence fenceCompletion(UUID workspaceId, UUID sessionId, Instant now, Instant leaseUntil) {
        return withLock(sessionId, () -> {
            UploadSession session = requireWorkspace(workspaceId, sessionId);
            if (session.status() == UploadSessionStatus.COMPLETED) {
                return new CompletionFence(session, false, true);
            }
            if (session.status() == UploadSessionStatus.COMPLETING
                    && session.completionLeaseUntil() != null && now.isBefore(session.completionLeaseUntil())) {
                return new CompletionFence(session, false, false);
            }
            if (!now.isBefore(session.expiresAt()) || !now.isBefore(session.hardExpiresAt())) {
                UploadSession expired = session.status() == UploadSessionStatus.EXPIRED
                        ? session : session.withState(UploadSessionStatus.EXPIRED, session.documentId(),
                        "UPLOAD_SESSION_EXPIRED", null);
                write(expired);
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_EXPIRED, "上传会话已过期");
            }
            if (session.status() != UploadSessionStatus.UPLOADING
                    && session.status() != UploadSessionStatus.COMPLETING) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传会话当前不可完成");
            }
            if (!session.allPartsConfirmed() || !session.reservations().isEmpty()
                    || session.confirmedBytes() != session.sizeBytes()) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_INCOMPLETE, "上传分片尚未全部确认");
            }
            UploadSession next = session.withState(UploadSessionStatus.COMPLETING, session.documentId(),
                    session.failureCode(), leaseUntil);
            write(next);
            return new CompletionFence(next, true, false);
        });
    }

    @Override
    public UploadSession markCompleted(UUID workspaceId, UUID sessionId, UUID documentId) {
        return withLock(sessionId, () -> {
            UploadSession session = requireWorkspace(workspaceId, sessionId);
            if (session.status() == UploadSessionStatus.COMPLETED && session.documentId() != null
                    && !session.documentId().equals(documentId)) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传完成结果不一致");
            }
            if (session.status() == UploadSessionStatus.COMPLETED) return session;
            if (session.status() != UploadSessionStatus.COMPLETING) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传会话当前不可标记完成");
            }
            UploadSession next = session.withState(UploadSessionStatus.COMPLETED, documentId, null, null);
            write(next);
            return next;
        });
    }

    @Override
    public UploadSession markFailed(UUID workspaceId, UUID sessionId, String errorCode) {
        return withLock(sessionId, () -> {
            UploadSession session = requireWorkspace(workspaceId, sessionId);
            if (session.status() == UploadSessionStatus.COMPLETED
                    || session.status() == UploadSessionStatus.ABORTED
                    || session.status() == UploadSessionStatus.EXPIRED
                    || session.status() == UploadSessionStatus.FAILED) {
                return session;
            }
            UploadSession next = session.withState(UploadSessionStatus.FAILED, session.documentId(), errorCode, null);
            write(next);
            return next;
        });
    }

    @Override
    public UploadSession markAborted(UUID workspaceId, UUID sessionId) {
        return withLock(sessionId, () -> {
            UploadSession session = requireWorkspace(workspaceId, sessionId);
            if (session.status() == UploadSessionStatus.COMPLETED
                    || session.status() == UploadSessionStatus.COMPLETING) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_CONFLICT, "上传已完成，不能取消");
            }
            if (session.status() == UploadSessionStatus.ABORTED
                    || session.status() == UploadSessionStatus.EXPIRED
                    || session.status() == UploadSessionStatus.FAILED) return session;
            UploadSession next = session.withState(UploadSessionStatus.ABORTED, session.documentId(), null, null);
            write(next);
            return next;
        });
    }

    @Override
    public UploadSession markExpired(UUID workspaceId, UUID sessionId) {
        return withLock(sessionId, () -> {
            UploadSession session = requireWorkspace(workspaceId, sessionId);
            if (session.status() == UploadSessionStatus.COMPLETED
                    || session.status() == UploadSessionStatus.ABORTED
                    || session.status() == UploadSessionStatus.EXPIRED
                    || session.status() == UploadSessionStatus.FAILED) return session;
            if (session.status() == UploadSessionStatus.COMPLETING
                    && session.completionLeaseUntil() != null
                    && Instant.now().isBefore(session.completionLeaseUntil())) return session;
            UploadSession next = session.withState(UploadSessionStatus.EXPIRED, session.documentId(),
                    "UPLOAD_SESSION_EXPIRED", null);
            write(next);
            return next;
        });
    }

    @Override
    public List<UploadSession> list(int limit) {
        try {
            RedissonClient client = clientProvider.client();
            List<UploadSession> sessions = new ArrayList<>();
            int boundedLimit = Math.max(1, Math.min(limit, properties.cleanupBatchSize()));
            RScoredSortedSet<String> index = client.getScoredSortedSet(expiryIndexKey);
            for (String sessionId : index.valueRange(0, Math.max(0, boundedLimit * 2 - 1))) {
                UUID id;
                try {
                    id = UUID.fromString(sessionId);
                } catch (IllegalArgumentException ex) {
                    index.remove(sessionId);
                    continue;
                }
                String json = bucket(id).get();
                if (json == null) {
                    // 延后 TTL 后的墓碑到期时，把索引中的逻辑记录一并收束。
                    index.remove(sessionId);
                    continue;
                }
                sessions.add(mapper.readValue(json, UploadSession.class));
                if (sessions.size() >= boundedLimit) break;
            }
            return sessions;
        } catch (RedisClientUnavailableException | RedisException ex) {
            throw unavailable(ex);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    private UploadSession requireWorkspace(UUID workspaceId, UUID sessionId) {
        UploadSession session = read(sessionId);
        if (session == null || !session.workspaceId().equals(workspaceId)) throw notFound();
        return session;
    }

    private UploadSession read(UUID sessionId) {
        try {
            String json = bucket(sessionId).get();
            return json == null ? null : mapper.readValue(json, UploadSession.class);
        } catch (RedisClientUnavailableException | RedisException ex) {
            throw unavailable(ex);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    private void write(UploadSession session) {
        try {
            // 索引按物理 TTL 到期时间排序；状态读取不依赖 keyspace 全量扫描。
            expiryIndex().add(session.hardExpiresAt().plus(properties.terminalRetention()).toEpochMilli(),
                    session.id().toString());
            Duration ttl = Duration.between(Instant.now(), session.hardExpiresAt().plus(properties.terminalRetention()));
            long millis = Math.max(1_000L, ttl.toMillis());
            bucket(session.id()).set(mapper.writeValueAsString(session), millis, TimeUnit.MILLISECONDS);
        } catch (RedisClientUnavailableException | RedisException ex) {
            throw unavailable(ex);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    private RBucket<String> bucket(UUID id) {
        return clientProvider.client().getBucket(sessionPrefix + id);
    }

    private RScoredSortedSet<String> expiryIndex() {
        return clientProvider.client().getScoredSortedSet(expiryIndexKey);
    }

    private <T> T withLock(UUID sessionId, java.util.concurrent.Callable<T> action) {
        RLock lock;
        try {
            lock = clientProvider.client().getLock(sessionPrefix + sessionId + ":lock");
            if (!lock.tryLock(2, 10, TimeUnit.SECONDS)) {
                throw new KnowledgeException(KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE, "上传状态暂时不可用");
            }
            try {
                return action.call();
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (RedisClientUnavailableException | RedisException ex) {
            throw unavailable(ex);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    private UploadSession replace(UploadSession source, Map<Integer, PartReceipt> receipts,
                                  Map<Integer, PartReservation> reservations, Instant expiresAt) {
        return new UploadSession(source.id(), source.workspaceId(), source.fileName(), source.declaredMediaType(),
                source.sizeBytes(), source.fileFingerprint(), source.lastModifiedMillis(), source.partSizeBytes(),
                source.totalParts(), source.maxConcurrentParts(), source.createdAt(), expiresAt, source.hardExpiresAt(),
                source.status(), source.partPrefix(), source.finalObjectKey(), receipts, reservations,
                source.documentId(), source.completionLeaseUntil(), source.failureCode());
    }

    private static KnowledgeException notFound() {
        return new KnowledgeException(KnowledgeException.Code.UPLOAD_SESSION_NOT_FOUND, "上传会话不存在");
    }

    private static KnowledgeException unavailable(Throwable cause) {
        return new KnowledgeException(KnowledgeException.Code.UPLOAD_STATE_UNAVAILABLE, "上传状态暂时不可用", cause);
    }
}
