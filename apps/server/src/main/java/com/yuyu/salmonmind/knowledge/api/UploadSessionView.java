package com.yuyu.salmonmind.knowledge.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 可恢复上传的安全投影。Session ID 是唯一内部身份；Redis key、Object Key、锁 token 和字节均不返回。
 */
public record UploadSessionView(
        UUID sessionId,
        String status,
        String fileName,
        String declaredMediaType,
        long sizeBytes,
        int partSizeBytes,
        int totalParts,
        List<Integer> confirmedPartNumbers,
        List<UploadPartReceiptView> receipts,
        long confirmedBytes,
        Instant expiresAt,
        Instant hardExpiresAt,
        UUID documentId,
        String failureCode
) {
    public UploadSessionView {
        confirmedPartNumbers = confirmedPartNumbers == null ? List.of() : List.copyOf(confirmedPartNumbers);
        receipts = receipts == null ? List.of() : List.copyOf(receipts);
    }
}
