package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.RepositoryResolution;

import java.util.UUID;

/**
 * 一个 Agent Run 内的临时代码库绑定。
 *
 * <p>实例只通过 RunnableConfig metadata 传递，生命周期与主 Run 相同，不写入 Conversation、
 * Redis 或全局缓存。选择操作在同一把锁下完成解析与首次绑定，因而并发选择不会让一次
 * Run 同时拥有两个 Repository。</p>
 */
final class CodebaseRunContext {

    static final String METADATA_KEY = "salmon:agent:codebase-context";

    private final CodebaseService service;
    private Binding binding;

    CodebaseRunContext(CodebaseService service) {
        this.service = service;
    }

    synchronized Selection select(String reference) {
        if (service == null) {
            return Selection.resolution(RepositoryResolution.notFound("CODEBASE_UNAVAILABLE"));
        }
        RepositoryResolution resolution = service.resolveRepository(reference);
        if (resolution.status() != RepositoryResolution.Status.RESOLVED) {
            return Selection.resolution(resolution);
        }
        RepositoryResolution.ResolvedRepository resolved = resolution.repository();
        if (binding != null && !binding.repositoryId().equals(resolved.id())) {
            return Selection.conflict();
        }
        if (binding == null) {
            String selectionSource = reference == null || reference.isBlank()
                    ? "ACTIVE_REPOSITORY" : "EXPLICIT_REFERENCE";
            binding = new Binding(resolved.id(), resolved.name(), selectionSource);
        }
        return Selection.bound(resolution, binding);
    }

    synchronized Binding binding() {
        return binding;
    }

    record Binding(UUID repositoryId, String repositoryName, String selectionSource) {
    }

    record Selection(
            RepositoryResolution resolution,
            Binding binding,
            boolean multipleRepositories
    ) {
        static Selection resolution(RepositoryResolution resolution) {
            return new Selection(resolution, null, false);
        }

        static Selection bound(RepositoryResolution resolution, Binding binding) {
            return new Selection(resolution, binding, false);
        }

        static Selection conflict() {
            return new Selection(RepositoryResolution.notFound(
                    "MULTIPLE_REPOSITORIES_NOT_SUPPORTED"), null, true);
        }
    }
}
