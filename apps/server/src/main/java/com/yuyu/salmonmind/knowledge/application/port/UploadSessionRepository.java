package com.yuyu.salmonmind.knowledge.application.port;

import com.yuyu.salmonmind.knowledge.domain.PartReceipt;
import com.yuyu.salmonmind.knowledge.domain.PartReservation;
import com.yuyu.salmonmind.knowledge.domain.UploadSession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Upload Session 的 Redis 投影边界。实现必须把状态、reservation 和 Receipt 变更做成短原子操作，
 * 不得在持锁期间执行 RustFS I/O，也不得保存文件字节。
 */
public interface UploadSessionRepository {

    /** 创建 Session；相同 ID 重放只返回同一 Workspace 的原记录，跨 Workspace 不泄露存在性。 */
    UploadSession create(UploadSession session);

    /** 按当前 Workspace 读取 Session；不存在或跨 Workspace 时返回 null。 */
    UploadSession find(UUID workspaceId, UUID sessionId);

    /**
     * 原子预留一个 part slot。只做 Redis 元数据变更，不持锁等待对象存储。
     * 相同 part 的相同长度/SHA 返回幂等命中，不同内容或并发超过上限必须冲突。
     */
    PartReservationResult reservePart(UUID workspaceId, UUID sessionId, int partNumber, long sizeBytes,
                                      String sha256, Instant now, Instant reservationExpiry);

    /** RustFS Put 成功后原子提交 Receipt；只有提交成功的 Receipt 才能计入确认进度。 */
    UploadSession commitReceipt(UUID workspaceId, UUID sessionId, int partNumber, String token, PartReceipt receipt,
                                Instant now);

    /** 释放仍归调用方持有的 reservation；Redis 故障不得伪造 Receipt。 */
    UploadSession releaseReservation(UUID workspaceId, UUID sessionId, int partNumber, String token);

    /**
     * 在全部 Receipt 已确认时获取有界 completion lease；并发调用只能有一个获得 Fence。
     * lease 到期后可重入协调，但超过租期或 hard expiry 不得重新激活 Session。
     */
    CompletionFence fenceCompletion(UUID workspaceId, UUID sessionId, Instant now, Instant leaseUntil);

    /** 只允许从 COMPLETING 投影到 COMPLETED；重复调用必须返回同一 documentId。 */
    UploadSession markCompleted(UUID workspaceId, UUID sessionId, UUID documentId);

    /** 将当前未完成 Session 标记失败；不得覆盖已完成或其他终态。 */
    UploadSession markFailed(UUID workspaceId, UUID sessionId, String errorCode);

    /** 将 UPLOADING Session 标记取消；COMPLETING/COMPLETED 必须拒绝。 */
    UploadSession markAborted(UUID workspaceId, UUID sessionId);

    /** 将已过期且没有有效 completion lease 的 Session 写成墓碑。 */
    UploadSession markExpired(UUID workspaceId, UUID sessionId);

    /** 按逻辑过期索引返回有界 Session 列表，供 Janitor 逐个协调。 */
    List<UploadSession> list(int limit);

    /** 预留结果；alreadyConfirmed 表示相同 Receipt 的幂等重试。 */
    record PartReservationResult(UploadSession session, String token, boolean alreadyConfirmed) {
    }

    /** completion Fence 结果；acquired 与 alreadyCompleted 互斥。 */
    record CompletionFence(UploadSession session, boolean acquired, boolean alreadyCompleted) {
    }
}
