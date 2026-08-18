package com.yuyu.salmonmind.knowledge.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Knowledge 文档列表与上传响应中可见的稳定摘要。state 通常是最新 Job 的公开状态，
 * 但 Source 进入删除流程后固定投影为 DELETING；
 * latestJobId 可能在双写缝隙中暂时为 PENDING_DISPATCH；sizeBytes 与 sha256 对应
 * 不可变原件，evidenceCount 只统计已发布的 PostgreSQL Evidence 元数据。
 */
public record DocumentSummary(
        UUID id,
        UUID workspaceId,
        UUID revisionId,
        UUID latestJobId,
        String name,
        String format,
        String mediaType,
        long sizeBytes,
        String sha256,
        String state,
        boolean retryable,
        int evidenceCount,
        Instant createdAt,
        Instant updatedAt
) {
}
