package com.yuyu.salmonmind.workspace.api;

/** 当前 Workspace 查询入口；实现保留在 workspace 内部，其他模块只依赖本接口。 */
public interface WorkspaceRegistry {

    /**
     * 返回本安装唯一的 Workspace。
     *
     * @return 当前 Workspace；单例行缺失时抛 {@link IllegalStateException}（本地安装数据被破坏）
     */
    Workspace current();
}
