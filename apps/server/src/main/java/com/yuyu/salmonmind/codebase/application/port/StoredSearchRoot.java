package com.yuyu.salmonmind.codebase.application.port;

import java.time.Instant;
import java.util.UUID;

/** Server-owned catalog 中的一条 Search Root 授权记录。 */
public record StoredSearchRoot(UUID id, String path, Instant createdAt) {
}
