package com.yuyu.salmonmind.workspace.web;

import com.yuyu.salmonmind.workspace.api.Workspace;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 的 HTTP 转换入口：只依赖 workspace::api，把当前 Workspace 结果原样返回给前端。
 * 未找到唯一 Workspace 时由 Spring 统一映射为 500，不在此层做业务判断。
 */
@RestController
@RequestMapping("/api/workspace")
class WorkspaceController {

    private final WorkspaceRegistry workspaceRegistry;

    WorkspaceController(WorkspaceRegistry workspaceRegistry) {
        this.workspaceRegistry = workspaceRegistry;
    }

    @GetMapping
    Workspace current() {
        return workspaceRegistry.current();
    }
}
