package com.yuyu.salmonmind.codebase.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Catalog 适配器读写的一条仓库注册记录；branch、HEAD 与 dirty 是实时观察，不在此持久化。
 */
public record StoredRepository(
        UUID id,
        String path,
        String name,
        List<String> aliases,
        boolean registered,
        Instant createdAt,
        Instant updatedAt
) {
    public StoredRepository {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
