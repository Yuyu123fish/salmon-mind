package com.yuyu.salmonmind.codebase.api;

import java.util.List;
import java.util.UUID;

/** 顶部仓库入口使用的完整 catalog 快照。 */
public record CodebaseCatalogView(
        PlatformView platform,
        boolean gitAvailable,
        UUID activeRepositoryId,
        List<RepositoryView> repositories
) {
    public CodebaseCatalogView {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }
}
