package com.yuyu.salmonmind.codebase.application.port;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Server-owned catalog 的持久化合同。
 *
 * <p>实现负责校验、原子发布和重启恢复；应用层只操作一致性快照与明确的 mutation。</p>
 */
public interface CatalogStorePort {

    /** 返回不可变一致性快照；调用方不得修改或持有可变内部状态。 */
    CatalogState snapshot();

    /** 原子发布一条 repository.json，并使其进入后续快照。 */
    void saveRepository(StoredRepository repository);

    /** 原子发布 settings.json；active 必须为空或指向已注册仓库。 */
    void saveSettings(UUID activeRepositoryId, List<StoredSearchRoot> searchRoots);

    /** 返回 Server-owned catalog 数据根，仅用于配置状态检查，不是目标仓库路径。 */
    Path dataDir();
}
