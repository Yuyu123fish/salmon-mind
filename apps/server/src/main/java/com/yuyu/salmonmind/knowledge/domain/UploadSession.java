package com.yuyu.salmonmind.knowledge.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis 中的可恢复上传投影。
 * PostgreSQL/RustFS 才是已完成文档/字节的权威；本记录只保存会话元数据、Receipt 和协调 Fence。
 */
public record UploadSession(
        UUID id,
        UUID workspaceId,
        String fileName,
        String declaredMediaType,
        long sizeBytes,
        String fileFingerprint,
        long lastModifiedMillis,
        int partSizeBytes,
        int totalParts,
        int maxConcurrentParts,
        Instant createdAt,
        Instant expiresAt,
        Instant hardExpiresAt,
        UploadSessionStatus status,
        String partPrefix,
        String finalObjectKey,
        Map<Integer, PartReceipt> receipts,
        Map<Integer, PartReservation> reservations,
        UUID documentId,
        Instant completionLeaseUntil,
        String failureCode
) {

    public UploadSession {
        if (id == null || workspaceId == null || fileName == null || fileName.isBlank()
                || sizeBytes <= 0 || partSizeBytes <= 0 || totalParts <= 0
                || createdAt == null || expiresAt == null || hardExpiresAt == null || status == null
                || partPrefix == null || finalObjectKey == null) {
            throw new IllegalArgumentException("Upload Session 元数据不完整");
        }
        receipts = receipts == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(receipts));
        reservations = reservations == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(reservations));
    }

    /** 返回 Redis 已提交 Receipt 的字节总和；未提交或仅写入 RustFS 的 part 不计入。 */
    public long confirmedBytes() {
        return receipts.values().stream().mapToLong(PartReceipt::sizeBytes).sum();
    }

    /** 判断 part number 集合是否完整且没有越界项。 */
    public boolean allPartsConfirmed() {
        return receipts.size() == totalParts
                && receipts.keySet().stream().allMatch(number -> number >= 1 && number <= totalParts);
    }

    /**
     * 计算指定 part 的唯一合法长度；最后一个 part 可以短于策略 partSize。
     *
     * @param partNumber 从 1 开始的 part number
     * @return 服务端期望的字节长度
     * @throws IllegalArgumentException part number 越界
     */
    public int expectedPartLength(int partNumber) {
        if (partNumber < 1 || partNumber > totalParts) {
            throw new IllegalArgumentException("part number 无效");
        }
        long start = (long) (partNumber - 1) * partSizeBytes;
        return (int) Math.min(partSizeBytes, sizeBytes - start);
    }

    /** 返回按 part number 排序的不可变 Receipt 快照，供 complete 顺序归并。 */
    public List<PartReceipt> orderedReceipts() {
        return receipts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    /** 创建状态投影副本；不会修改既有 Redis 载荷中的 Map。 */
    public UploadSession withState(UploadSessionStatus next, UUID nextDocumentId, String nextFailureCode,
                                   Instant leaseUntil) {
        return new UploadSession(id, workspaceId, fileName, declaredMediaType, sizeBytes, fileFingerprint,
                lastModifiedMillis, partSizeBytes, totalParts, maxConcurrentParts, createdAt, expiresAt,
                hardExpiresAt, next, partPrefix, finalObjectKey, receipts, reservations, nextDocumentId,
                leaseUntil, nextFailureCode);
    }

    /** 合并一个已原子确认的 Receipt，并移除其 reservation。 */
    public UploadSession withReceipt(PartReceipt receipt, Instant nextExpiresAt) {
        Map<Integer, PartReceipt> next = new LinkedHashMap<>(receipts);
        next.put(receipt.partNumber(), receipt);
        Map<Integer, PartReservation> remaining = new LinkedHashMap<>(reservations);
        remaining.remove(receipt.partNumber());
        return new UploadSession(id, workspaceId, fileName, declaredMediaType, sizeBytes, fileFingerprint,
                lastModifiedMillis, partSizeBytes, totalParts, maxConcurrentParts, createdAt,
                nextExpiresAt, hardExpiresAt, status, partPrefix, finalObjectKey, next, remaining,
                documentId, completionLeaseUntil, failureCode);
    }

    /** 移除指定 part 的 reservation，供 I/O 失败后的安全重试使用。 */
    public UploadSession withoutReservation(int partNumber) {
        Map<Integer, PartReservation> remaining = new LinkedHashMap<>(reservations);
        remaining.remove(partNumber);
        return new UploadSession(id, workspaceId, fileName, declaredMediaType, sizeBytes, fileFingerprint,
                lastModifiedMillis, partSizeBytes, totalParts, maxConcurrentParts, createdAt, expiresAt,
                hardExpiresAt, status, partPrefix, finalObjectKey, receipts, remaining, documentId,
                completionLeaseUntil, failureCode);
    }

    /** 按 idle TTL 续租 expiresAt，但永不越过初始化固定的 hardExpiresAt。 */
    public UploadSession renewed(Instant now, java.time.Duration idleTtl) {
        Instant next = now.plus(idleTtl).isBefore(hardExpiresAt) ? now.plus(idleTtl) : hardExpiresAt;
        return new UploadSession(id, workspaceId, fileName, declaredMediaType, sizeBytes, fileFingerprint,
                lastModifiedMillis, partSizeBytes, totalParts, maxConcurrentParts, createdAt, next,
                hardExpiresAt, status, partPrefix, finalObjectKey, receipts, reservations, documentId,
                completionLeaseUntil, failureCode);
    }
}
