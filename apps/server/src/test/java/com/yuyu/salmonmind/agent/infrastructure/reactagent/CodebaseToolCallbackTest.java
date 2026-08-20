package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.DirectoryEntry;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.EvidenceMetadata;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GrepResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ListDirectoryResult;
import com.yuyu.salmonmind.codebase.api.RepositoryResolution;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 代码库 Tool 的 schema、Run 绑定和结构化结果合同测试。 */
class CodebaseToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesElevenStrictToolsAndDoesNotCreateLwSources() throws Exception {
        CodebaseService codebase = mock(CodebaseService.class);
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        List<ToolCallback> tools = CodebaseToolCallback.productionTools(mapper, codebase, evidence);

        assertThat(tools).hasSize(11);
        for (ToolCallback tool : tools) {
            JsonNode schema = mapper.readTree(tool.getToolDefinition().inputSchema());
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        }

        CodebaseToolCallback callback = new CodebaseToolCallback(
                mapper, codebase, evidence, CodebaseToolCallback.Operation.LIST);
        JsonNode notSelected = mapper.readTree(callback.call("{}"));
        assertThat(notSelected.path("sourceKind").asText()).isEqualTo("CODEBASE");
        assertThat(notSelected.path("reason").asText()).isEqualTo("REPOSITORY_NOT_SELECTED");
        assertThat(notSelected.toString()).doesNotContain("referenceId", "[L", "[W");
        verifyNoEvidenceCalls(evidence);
    }

    @Test
    void bindsOneRepositoryBeforeQueryAndRejectsSwitchingWithinTheRun() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        CodebaseService codebase = mock(CodebaseService.class);
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        when(codebase.resolveRepository("first")).thenReturn(RepositoryResolution.resolved(repository(firstId, "first")));
        when(codebase.resolveRepository("second")).thenReturn(RepositoryResolution.resolved(repository(secondId, "second")));
        when(evidence.listDirectory(any())).thenReturn(new ListDirectoryResult(
                metadata(firstId, "first", "list"),
                List.of(new DirectoryEntry("src", "src", true, false))));

        CodebaseRunContext context = new CodebaseRunContext(codebase);
        ToolContext toolContext = new ToolContext(Map.of(CodebaseRunContext.METADATA_KEY, context));
        CodebaseToolCallback select = new CodebaseToolCallback(
                mapper, codebase, evidence, CodebaseToolCallback.Operation.SELECT);
        CodebaseToolCallback list = new CodebaseToolCallback(
                mapper, codebase, evidence, CodebaseToolCallback.Operation.LIST);

        JsonNode selected = mapper.readTree(select.call("{\"reference\":\"first\"}", toolContext));
        JsonNode listed = mapper.readTree(list.call("{}", toolContext));
        JsonNode conflict = mapper.readTree(select.call("{\"reference\":\"second\"}", toolContext));

        assertThat(selected.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(selected.path("repositoryName").asText()).isEqualTo("first");
        assertThat(listed.path("items").get(0).path("path").asText()).isEqualTo("src");
        assertThat(conflict.path("reason").asText()).isEqualTo("MULTIPLE_REPOSITORIES_NOT_SUPPORTED");
        verify(evidence).listDirectory(any());
    }

    @Test
    void bindsRunActiveSnapshotOnFirstEvidenceWithoutSelectionToolCall() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        CodebaseService codebase = mock(CodebaseService.class);
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        when(codebase.resolveRepository(null)).thenReturn(RepositoryResolution.resolved(repository(repositoryId, "active")));
        when(evidence.listDirectory(any())).thenReturn(new ListDirectoryResult(
                metadata(repositoryId, "active", "list"),
                List.of(new DirectoryEntry("src", "src", true, false))));

        CodebaseRunContext context = new CodebaseRunContext(codebase);
        ToolContext toolContext = new ToolContext(Map.of(CodebaseRunContext.METADATA_KEY, context));
        CodebaseToolCallback list = new CodebaseToolCallback(
                mapper, codebase, evidence, CodebaseToolCallback.Operation.LIST);

        JsonNode result = mapper.readTree(list.call("{}", toolContext));

        assertThat(result.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(context.binding()).isNotNull();
        assertThat(context.binding().selectionSource()).isEqualTo("ACTIVE_REPOSITORY");
        verify(codebase, times(1)).resolveRepository(null);
        verify(evidence).listDirectory(any());
    }

    @Test
    void freezesActiveSnapshotAndDoesNotFallBackAfterActiveChanges() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        CodebaseService codebase = mock(CodebaseService.class);
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        when(codebase.resolveRepository(null)).thenReturn(
                RepositoryResolution.resolved(repository(firstId, "first")),
                RepositoryResolution.resolved(repository(secondId, "second")));

        CodebaseRunContext context = new CodebaseRunContext(codebase);
        CodebaseRunContext.Selection first = context.bindDefault();

        assertThat(first.binding().repositoryId()).isEqualTo(firstId);
        verify(codebase, times(1)).resolveRepository(null);
    }

    @Test
    void explicitReferenceOverridesSnapshotBeforeFirstEvidence() throws Exception {
        UUID activeId = UUID.randomUUID();
        UUID explicitId = UUID.randomUUID();
        CodebaseService codebase = mock(CodebaseService.class);
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        when(codebase.resolveRepository(null)).thenReturn(RepositoryResolution.resolved(repository(activeId, "active")));
        when(codebase.resolveRepository("other"))
                .thenReturn(RepositoryResolution.resolved(repository(explicitId, "other")));
        when(evidence.listDirectory(any())).thenReturn(new ListDirectoryResult(
                metadata(explicitId, "other", "list"), List.of()));
        CodebaseRunContext context = new CodebaseRunContext(codebase);
        ToolContext toolContext = new ToolContext(Map.of(CodebaseRunContext.METADATA_KEY, context));
        CodebaseToolCallback select = new CodebaseToolCallback(
                mapper, codebase, evidence, CodebaseToolCallback.Operation.SELECT);
        CodebaseToolCallback list = new CodebaseToolCallback(
                mapper, codebase, evidence, CodebaseToolCallback.Operation.LIST);

        JsonNode selected = mapper.readTree(select.call("{\"reference\":\"other\"}", toolContext));
        JsonNode listed = mapper.readTree(list.call("{}", toolContext));

        assertThat(selected.path("repositoryName").asText()).isEqualTo("other");
        assertThat(listed.path("repositoryName").asText()).isEqualTo("other");
        assertThat(context.binding().selectionSource()).isEqualTo("EXPLICIT_REFERENCE");
        verify(codebase, times(1)).resolveRepository(null);
        verify(codebase, times(1)).resolveRepository("other");
    }

    @Test
    void reportsStableNoActiveErrorWhenEvidenceHasNoDefault() throws Exception {
        CodebaseService codebase = mock(CodebaseService.class);
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        when(codebase.resolveRepository(null)).thenReturn(
                RepositoryResolution.notFound("REPOSITORY_NOT_SELECTED"));
        CodebaseRunContext context = new CodebaseRunContext(codebase);
        ToolContext toolContext = new ToolContext(Map.of(CodebaseRunContext.METADATA_KEY, context));
        CodebaseToolCallback list = new CodebaseToolCallback(
                mapper, codebase, evidence, CodebaseToolCallback.Operation.LIST);

        JsonNode result = mapper.readTree(list.call("{}", toolContext));

        assertThat(result.path("reason").asText()).isEqualTo("REPOSITORY_NOT_SELECTED");
        verifyNoEvidenceCalls(evidence);
    }

    @Test
    void rejectsUnknownFieldsBeforeCallingRepositoryEvidence() throws Exception {
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        CodebaseToolCallback callback = new CodebaseToolCallback(
                mapper, mock(CodebaseService.class), evidence, CodebaseToolCallback.Operation.GREP);

        JsonNode result = mapper.readTree(callback.call("{\"pattern\":\"needle\",\"extra\":true}"));

        assertThat(result.path("reason").asText()).isEqualTo("INVALID_QUERY");
        verifyNoEvidenceCalls(evidence);
    }

    @Test
    void usesRegexByDefaultAndAllowsExplicitLiteralSearch() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        CodebaseService codebase = mock(CodebaseService.class);
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        when(codebase.resolveRepository(null)).thenReturn(
                RepositoryResolution.resolved(repository(repositoryId, "demo")));
        when(evidence.grep(any())).thenReturn(new GrepResult(
                metadata(repositoryId, "demo", "grep"), List.of()));
        CodebaseRunContext context = new CodebaseRunContext(codebase);
        ToolContext toolContext = new ToolContext(Map.of(CodebaseRunContext.METADATA_KEY, context));
        CodebaseToolCallback grep = new CodebaseToolCallback(
                mapper, codebase, evidence, CodebaseToolCallback.Operation.GREP);

        grep.call("{\"pattern\":\"rag|retrieval\"}", toolContext);
        grep.call("{\"pattern\":\"Map<String, Value>\",\"fixedString\":true}", toolContext);

        verify(evidence).grep(argThat(query -> !query.fixedString()
                && query.pattern().equals("rag|retrieval")));
        verify(evidence).grep(argThat(query -> query.fixedString()
                && query.pattern().equals("Map<String, Value>")));
    }

    @Test
    void stagesOnlyIdentityAndEdgesAndRejectsModelSuppliedSource() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        CodebaseService codebase = mock(CodebaseService.class);
        when(codebase.resolveRepository(null)).thenReturn(RepositoryResolution.resolved(
                repository(repositoryId, "demo")));
        CodebaseRunContext context = new CodebaseRunContext(codebase, mapper);
        context.select(null);
        context.registerReadFileResult("""
                {"status":"SUCCESS","sourceKind":"CODEBASE","operation":"read_repository_file","path":"A.java","startLine":1,"endLine":1,"truncated":false,"items":[{"path":"A.java","line":1,"text":"void enter() {}"}]}
                """);
        context.registerReadFileResult("""
                {"status":"SUCCESS","sourceKind":"CODEBASE","operation":"read_repository_file","path":"B.java","startLine":1,"endLine":1,"truncated":false,"items":[{"path":"B.java","line":1,"text":"void run() {}"}]}
                """);
        ToolContext toolContext = new ToolContext(Map.of(CodebaseRunContext.METADATA_KEY, context));
        CallChainToolCallback callback = new CallChainToolCallback(mapper);

        JsonNode staged = mapper.readTree(callback.call("""
                {"name":"入口链","nodes":[{"key":"a","language":"java","qualifiedSymbol":"Demo.enter","signature":"void enter() {}","path":"A.java","startLine":1,"endLine":1,"summary":"入口"},{"key":"b","language":"java","qualifiedSymbol":"Demo.run","signature":"void run() {}","path":"B.java","startLine":1,"endLine":1,"summary":"服务"}],"edges":[{"from":"a","to":"b"}]}
                """, toolContext));
        assertThat(staged.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(staged.path("operation").asText()).isEqualTo("stage_call_chain");

        JsonNode withSource = mapper.readTree(callback.call("""
                {"name":"入口链","nodes":[],"edges":[],"source":"不要接受"}
                """, toolContext));
        assertThat(withSource.path("reason").asText()).isEqualTo("INVALID_QUERY");
    }

    private RepositoryResolution.ResolvedRepository repository(UUID id, String name) {
        return new RepositoryResolution.ResolvedRepository(
                id, name, "C:/workspace/" + name, true, "CLEAN", "main", "abc123", false);
    }

    private EvidenceMetadata metadata(UUID id, String name, String summary) {
        return new EvidenceMetadata(id, name, summary, "main", "abc123", false,
                true, false, 1, 1, false, null, null);
    }

    private void verifyNoEvidenceCalls(RepositoryEvidenceService evidence) {
        verify(evidence, never()).listDirectory(any());
        verify(evidence, never()).glob(any());
        verify(evidence, never()).grep(any());
        verify(evidence, never()).readFile(any());
        verify(evidence, never()).gitStatus(any());
        verify(evidence, never()).gitDiff(any());
        verify(evidence, never()).gitLog(any());
        verify(evidence, never()).gitShow(any());
        verify(evidence, never()).gitBlame(any());
    }
}
