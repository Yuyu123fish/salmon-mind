package com.yuyu.salmonmind.codebase.api;

import java.util.List;
import java.util.UUID;

/**
 * 仓库 catalog 的唯一公开管理入口。
 *
 * <p>调用方只提交用户输入的绝对路径和稳定 Repository ID；路径解析、Git 根识别、
 * 原子持久化与 Active 选择的一致性由 codebase 模块负责。</p>
 */
public interface CodebaseService {

    /** 返回当前注册仓库、Active 和实时平台/Git 状态；读取失败不会修改 catalog。 */
    CodebaseCatalogView catalog();

    /** 使用绝对目录注册 Git 工作树；重复真实工作树复用既有 ID，失败时不留下半份记录。 */
    RepositoryView registerRepository(String absolutePath, String name, List<String> aliases);

    /** 更新用户维护的名称与别名；name 为 null 表示保留原名，仓库路径和 ID 不变。 */
    RepositoryView updateRepository(UUID repositoryId, String name, List<String> aliases);

    /** 取消注册；只改变 Server-owned catalog，若目标为 Active 会先清空 Active。 */
    CodebaseCatalogView unregisterRepository(UUID repositoryId);

    /** 选择已注册且当前可解析的仓库，传 null 显式清空 Active。 */
    CodebaseCatalogView setActiveRepository(UUID repositoryId);

    /** 按一次用户消息中的完整引用精确解析；空白引用使用当前 Active 快照，非空失败不回退 Active。 */
    RepositoryResolution resolveRepository(String reference);
}
