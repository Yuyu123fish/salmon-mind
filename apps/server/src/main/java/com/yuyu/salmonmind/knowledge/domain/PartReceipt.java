package com.yuyu.salmonmind.knowledge.domain;

import java.time.Instant;

/** 只有 RustFS Put 成功并经 Redis 原子登记后的 part 才能成为 Receipt。 */
public record PartReceipt(int partNumber, String objectKey, long sizeBytes, String sha256, Instant confirmedAt) {

    public PartReceipt {
        if (partNumber < 1 || objectKey == null || objectKey.isBlank() || sizeBytes <= 0
                || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}") || confirmedAt == null) {
            throw new IllegalArgumentException("Part Receipt 不完整");
        }
    }
}
