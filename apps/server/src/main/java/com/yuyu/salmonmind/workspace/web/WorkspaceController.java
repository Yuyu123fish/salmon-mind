package com.yuyu.salmonmind.workspace.web;

import com.yuyu.salmonmind.workspace.api.Workspace;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
