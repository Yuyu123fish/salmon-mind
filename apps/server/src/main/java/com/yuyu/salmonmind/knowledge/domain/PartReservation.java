package com.yuyu.salmonmind.knowledge.domain;

import java.time.Instant;

/** 短租约 reservation 只保护 part 的并发额度，不跨 RustFS 网络调用持有。 */
public record PartReservation(String token, Instant reservedAt, Instant expiresAt) {

    public PartReservation {
        if (token == null || token.isBlank() || reservedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("Part reservation 不完整");
        }
    }
}
