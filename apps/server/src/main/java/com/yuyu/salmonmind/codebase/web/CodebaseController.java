package com.yuyu.salmonmind.codebase.web;

import com.yuyu.salmonmind.codebase.api.CodebaseCatalogView;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.RepositoryView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 顶部仓库入口使用的最小 HTTP 转换层。
 *
 * <p>这里只暴露 catalog 管理，不提供任意文件或 Git 查询 HTTP 端点；证据接口留给后续
 * Server 内部 Agent 接入，避免把本机仓库能力扩大成远程任意路径读取。</p>
 */
@RestController
@RequestMapping("/api/codebase")
class CodebaseController {

    private final CodebaseService codebase;

    CodebaseController(CodebaseService codebase) {
        this.codebase = codebase;
    }

    @GetMapping
    CodebaseCatalogView catalog() {
        return codebase.catalog();
    }

    @PostMapping("/repositories")
    ResponseEntity<RepositoryView> register(@RequestBody RegisterRepositoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                codebase.registerRepository(request.path(), request.name(), request.aliases()));
    }

    @PatchMapping("/repositories/{repositoryId}")
    RepositoryView update(
            @PathVariable UUID repositoryId,
            @RequestBody UpdateRepositoryRequest request
    ) {
        return codebase.updateRepository(repositoryId, request.name(), request.aliases());
    }

    @DeleteMapping("/repositories/{repositoryId}")
    CodebaseCatalogView unregister(@PathVariable UUID repositoryId) {
        return codebase.unregisterRepository(repositoryId);
    }

    @PutMapping("/active-repository")
    CodebaseCatalogView active(@RequestBody ActiveRepositoryRequest request) {
        return codebase.setActiveRepository(request.repositoryId());
    }


    record RegisterRepositoryRequest(String path, String name, List<String> aliases) {
    }

    record UpdateRepositoryRequest(String name, List<String> aliases) {
    }

    record ActiveRepositoryRequest(UUID repositoryId) {
    }

}
