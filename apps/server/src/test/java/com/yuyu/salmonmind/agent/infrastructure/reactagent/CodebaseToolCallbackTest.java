package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.DirectoryEntry;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.EvidenceMetadata;
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
    void exposesTenStrictToolsAndDoesNotCreateLwSources() throws Exception {
        CodebaseService codebase = mock(CodebaseService.class);
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        List<ToolCallback> tools = CodebaseToolCallback.productionTools(mapper, codebase, evidence);

        assertThat(tools).hasSize(10);
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
    void rejectsUnknownFieldsBeforeCallingRepositoryEvidence() throws Exception {
        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        CodebaseToolCallback callback = new CodebaseToolCallback(
                mapper, mock(CodebaseService.class), evidence, CodebaseToolCallback.Operation.GREP);

        JsonNode result = mapper.readTree(callback.call("{\"pattern\":\"needle\",\"extra\":true}"));

        assertThat(result.path("reason").asText()).isEqualTo("INVALID_QUERY");
        verifyNoEvidenceCalls(evidence);
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
