package com.yuyu.salmonmind.knowledge.infrastructure.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeUploadConfigurationPort;

import java.time.Duration;

/**
 * 可恢复上传的有界运行配置。阈值始终不得超过既有 Knowledge 总对象上限，
 * 关闭 feature flag 时旧单请求上传完全不依赖 Upload Session Redis。
 */
@ConfigurationProperties(prefix = "salmon.knowledge.upload")
public record KnowledgeUploadProperties(
        @DefaultValue("true") boolean resumableEnabled,
        @DefaultValue("10485760") long resumableThresholdBytes,
        @DefaultValue("5242880") int partSizeBytes,
        @DefaultValue("3") int maxConcurrentParts,
        @DefaultValue("30m") Duration sessionIdleTtl,
        @DefaultValue("24h") Duration maxSessionLifetime,
        @DefaultValue("2h") Duration orphanGrace,
        @DefaultValue("1h") Duration terminalRetention,
        @DefaultValue("1m") Duration cleanupInterval,
        @DefaultValue("100") int cleanupBatchSize,
        @DefaultValue("salmon:knowledge:upload:v1:") String keyPrefix,
        @DefaultValue("15s") Duration apiCallTimeout,
        @DefaultValue("10s") Duration apiAttemptTimeout
) implements KnowledgeUploadConfigurationPort {

    private static final long MAX_DURATION_DAYS = 7;
    private static final Duration MAX_ORPHAN_GRACE = Duration.ofDays(7);
    private static final Duration MAX_TERMINAL_RETENTION = Duration.ofDays(7);
    private static final Duration MAX_API_CALL_TIMEOUT = Duration.ofMinutes(2);

    public KnowledgeUploadProperties {
        if (resumableThresholdBytes <= 0 || partSizeBytes < 64 * 1024 || partSizeBytes > 64 * 1024 * 1024
                || maxConcurrentParts < 1
                || maxConcurrentParts > 16 || cleanupBatchSize < 1 || cleanupBatchSize > 10_000
                || keyPrefix == null || keyPrefix.isBlank() || !keyPrefix.endsWith(":")
                || keyPrefix.chars().anyMatch(Character::isWhitespace)
                || sessionIdleTtl == null || maxSessionLifetime == null || orphanGrace == null
                || terminalRetention == null || cleanupInterval == null || apiCallTimeout == null
                || apiAttemptTimeout == null || sessionIdleTtl.isNegative() || sessionIdleTtl.isZero()
                || maxSessionLifetime.isNegative() || maxSessionLifetime.isZero()
                || orphanGrace.isNegative() || terminalRetention.isNegative()
                || cleanupInterval.compareTo(Duration.ofSeconds(1)) < 0
                || cleanupInterval.compareTo(Duration.ofHours(1)) > 0
                || orphanGrace.compareTo(MAX_ORPHAN_GRACE) > 0
                || terminalRetention.compareTo(MAX_TERMINAL_RETENTION) > 0
                || apiCallTimeout.isNegative() || apiCallTimeout.isZero()
                || apiAttemptTimeout.isNegative() || apiAttemptTimeout.isZero()
                || apiCallTimeout.compareTo(MAX_API_CALL_TIMEOUT) > 0
                || maxSessionLifetime.toDays() > MAX_DURATION_DAYS
                || sessionIdleTtl.compareTo(maxSessionLifetime) > 0
                || apiAttemptTimeout.compareTo(apiCallTimeout) > 0) {
            throw new IllegalArgumentException("Knowledge Upload 配置超出有界范围");
        }
    }

    public long maxObjectBytes(long configuredMax) {
        if (configuredMax <= 0) {
            throw new IllegalArgumentException("Knowledge 总对象上限必须大于 0");
        }
        if (resumableThresholdBytes > configuredMax) {
            throw new IllegalArgumentException("可恢复上传阈值不得超过 Knowledge 总对象上限");
        }
        if (partSizeBytes > configuredMax) {
            throw new IllegalArgumentException("Upload part 大小不得超过 Knowledge 总对象上限");
        }
        return configuredMax;
    }
}
