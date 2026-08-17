package com.yuyu.salmonmind.knowledge.api;

import java.time.Instant;
import java.util.UUID;

/**
 * 文档处理尝试的诊断视图；不暴露 Redis Stream 身份。attemptNumber 是同一
 * Source Revision 的用户可见处理尝试，retryable 只表示 FAILED 是否允许显式重试。
 */
public record IngestionJobView(
        UUID id,
        int attemptNumber,
        String state,
        boolean retryable,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant endedAt
) {
}
