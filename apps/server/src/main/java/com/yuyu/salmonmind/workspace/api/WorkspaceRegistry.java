package com.yuyu.salmonmind.workspace.api;

/** 当前 Workspace 查询入口；实现保留在 workspace 内部，其他模块只依赖本接口。 */
public interface WorkspaceRegistry {

    Workspace current();
}
