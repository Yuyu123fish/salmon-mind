package com.yuyu.salmonmind.codebase.application.port;

import java.util.Map;
import java.util.UUID;

/**
 * Catalog 持久化适配器发布给应用层的完整快照。
 *
 * <p>Active 只能指向已注册仓库或为空；应用层据此串行编排注册和选择操作。</p>
 */
public record CatalogState(Map<UUID, StoredRepository> repositories,
                           UUID activeRepositoryId) {
    public CatalogState {
        repositories = repositories == null ? Map.of() : Map.copyOf(repositories);
    }
}
