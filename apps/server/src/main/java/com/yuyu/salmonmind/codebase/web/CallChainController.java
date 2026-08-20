package com.yuyu.salmonmind.codebase.web;

import com.yuyu.salmonmind.codebase.api.CallChainDetail;
import com.yuyu.salmonmind.codebase.api.CallChainQueryService;
import com.yuyu.salmonmind.codebase.api.CallChainSummary;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 调用链的安全查询与管理端点。详情只由正式 JSONL 解析得到，控制器不接触目标仓库路径。
 */
@RestController
@RequestMapping("/api/codebase/repositories/{repositoryId}/call-chains")
class CallChainController {

    private final CallChainQueryService callChains;

    CallChainController(CallChainQueryService callChains) {
        this.callChains = callChains;
    }

    @GetMapping
    List<CallChainSummary> list(@PathVariable UUID repositoryId) {
        return callChains.list(repositoryId);
    }

    @GetMapping("/{callChainId}")
    CallChainDetail detail(@PathVariable UUID repositoryId, @PathVariable UUID callChainId) {
        return callChains.detail(repositoryId, callChainId);
    }

    @PatchMapping("/{callChainId}")
    CallChainDetail rename(
            @PathVariable UUID repositoryId,
            @PathVariable UUID callChainId,
            @RequestBody RenameRequest request
    ) {
        return callChains.rename(repositoryId, callChainId, request == null ? null : request.name());
    }

    @DeleteMapping("/{callChainId}")
    CallChainDetail delete(@PathVariable UUID repositoryId, @PathVariable UUID callChainId) {
        return callChains.delete(repositoryId, callChainId);
    }

    record RenameRequest(String name) {
    }
}
