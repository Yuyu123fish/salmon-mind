package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.RepositoryResolution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Run-local 调用链边界测试：只接受实际返回的连续 ReadFile 行，不保存源码到外部状态。 */
class CodebaseRunContextTest {

    @Test
    void stagesOnlyNodesCoveredByFinalReadFileResults() {
        UUID repositoryId = UUID.randomUUID();
        CodebaseService service = mock(CodebaseService.class);
        when(service.resolveRepository(null)).thenReturn(RepositoryResolution.resolved(
                new RepositoryResolution.ResolvedRepository(
                        repositoryId, "demo", "D:/demo", true, "AVAILABLE", "main",
                        "0123456789012345678901234567890123456789", true)));
        CodebaseRunContext context = new CodebaseRunContext(service, new ObjectMapper());
        context.select(null);
        context.registerReadFileResult("""
                {"status":"SUCCESS","sourceKind":"CODEBASE","operation":"read_repository_file","path":"src/A.java","startLine":1,"endLine":2,"truncated":false,"items":[{"path":"src/A.java","line":1,"text":"void enter() {"},{"path":"src/A.java","line":2,"text":"  service.run();"}]}
                """);
        context.registerReadFileResult("""
                {"status":"SUCCESS","sourceKind":"CODEBASE","operation":"read_repository_file","path":"src/B.java","startLine":4,"endLine":5,"truncated":false,"items":[{"path":"src/B.java","line":4,"text":"void run() {"},{"path":"src/B.java","line":5,"text":"  return;"}]}
                """);

        context.stage("入口到服务", List.of(
                new CodebaseRunContext.DraftNode("A", "java", "Demo.enter", "void enter()", "src/A.java", 1, 2, "入口"),
                new CodebaseRunContext.DraftNode("B", "java", "Demo.run", "void run()", "src/B.java", 4, 5, "服务")),
                List.of(new CodebaseRunContext.DraftEdge("A", "B")));

        var request = context.prepareRequest(repositoryId, UUID.randomUUID());
        assertThat(request).isNotNull();
        assertThat(request.nodes()).extracting("sourceHash")
                .containsExactly(
                        "45c4c013a74137a907e65196853ed436035aa82f2dfe921299bca9027a4c06a5",
                        "bf186aa2b8585c674ecbbf7153b83133f0ee92915347d8801ffa072a07573341");
    }

    @Test
    void acceptsReturnedLinesFromTruncatedReadFileAsEvidence() {
        UUID repositoryId = UUID.randomUUID();
        CodebaseService service = mock(CodebaseService.class);
        when(service.resolveRepository(null)).thenReturn(RepositoryResolution.resolved(
                new RepositoryResolution.ResolvedRepository(repositoryId, "demo", "D:/demo", true,
                        "AVAILABLE", "main", "head", false)));
        CodebaseRunContext context = new CodebaseRunContext(service);
        context.select(null);
        context.registerReadFileResult("""
                {"status":"DEGRADED","sourceKind":"CODEBASE","operation":"read_repository_file","path":"A.java","startLine":1,"endLine":2,"truncated":true,"items":[{"path":"A.java","line":1,"text":"one"}]}
                """);

        context.stage("链", List.of(
                new CodebaseRunContext.DraftNode("A", "java", "A.enter", "void enter()", "A.java", 1, 1, "入口"),
                new CodebaseRunContext.DraftNode("B", "java", "B.run", "void run()", "A.java", 1, 1, "服务")),
                List.of(new CodebaseRunContext.DraftEdge("A", "B")));
        assertThat(context.prepareRequest(repositoryId, UUID.randomUUID())).isNotNull();
    }

    @Test
    void reportsMissingNodeRangeWhenCoverageIsIncomplete() {
        UUID repositoryId = UUID.randomUUID();
        CodebaseService service = mock(CodebaseService.class);
        when(service.resolveRepository(null)).thenReturn(RepositoryResolution.resolved(
                new RepositoryResolution.ResolvedRepository(repositoryId, "demo", "D:/demo", true,
                        "AVAILABLE", "main", "head", false)));
        CodebaseRunContext context = new CodebaseRunContext(service);
        context.select(null);
        context.registerReadFileResult("""
                {"status":"DEGRADED","sourceKind":"CODEBASE","operation":"read_repository_file","path":"A.java","startLine":1,"endLine":2,"truncated":true,"items":[{"path":"A.java","line":1,"text":"one"}]}
                """);

        assertThatThrownBy(() -> context.stage("链", List.of(
                new CodebaseRunContext.DraftNode("A", "java", "A.enter", "void enter()", "A.java", 1, 2, "入口"),
                new CodebaseRunContext.DraftNode("B", "java", "B.run", "void run()", "A.java", 1, 1, "服务")),
                List.of(new CodebaseRunContext.DraftEdge("A", "B"))))
                .isInstanceOf(CodebaseException.class)
                .satisfies(error -> assertThat(((CodebaseException) error).details())
                        .containsKey("missing"));
    }
}
