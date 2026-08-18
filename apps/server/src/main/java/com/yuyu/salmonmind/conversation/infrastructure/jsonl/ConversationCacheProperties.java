package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.UUID;

/**
 * Conversation Snapshot Cache 的有界配置。缓存是可重建派生数据，关闭后所有读写都绕过 Redis。
 */
@ConfigurationProperties(prefix = "salmon.conversation.cache")
record ConversationCacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10m") Duration ttl,
        @DefaultValue("4194304") long maxEntryBytes,
        @DefaultValue("salmon:conversation:snapshot:v1:") String keyPrefix
) {

    static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    static final Duration MAX_TTL = Duration.ofDays(7);

    public ConversationCacheProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.toMillis() <= 0
                || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Conversation Cache TTL 必须大于 0 且不超过 7 天");
        }
        if (maxEntryBytes <= 0 || maxEntryBytes > MAX_ENTRY_BYTES) {
            throw new IllegalArgumentException("Conversation Cache 单条大小必须大于 0 且不超过 64 MiB");
        }
        if (keyPrefix == null || keyPrefix.isBlank()
                || !keyPrefix.endsWith(":") || keyPrefix.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Conversation Cache key 前缀必须非空、无空白并以冒号结尾");
        }
    }

    String key(UUID conversationId) {
        return keyPrefix + conversationId;
    }
}
