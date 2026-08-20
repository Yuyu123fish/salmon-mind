package com.yuyu.salmonmind.codebase.web;

import com.yuyu.salmonmind.codebase.api.CallChainDetail;
import com.yuyu.salmonmind.codebase.api.CallChainNodeDetail;
import com.yuyu.salmonmind.codebase.api.CallChainQueryService;
import com.yuyu.salmonmind.codebase.api.CallChainSummary;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证调用链四个 HTTP 端点只暴露正式存储投影，并映射稳定错误码。 */
@WebMvcTest(CallChainController.class)
@Import(CodebaseExceptionHandler.class)
class CallChainControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CallChainQueryService callChains;

    @Test
    void listAndDetailReturnRepositoryBoundProjection() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID callChainId = UUID.randomUUID();
        CallChainSummary summary = summary(repositoryId, callChainId, "入口调用链");
        when(callChains.list(repositoryId)).thenReturn(List.of(summary));
        when(callChains.detail(repositoryId, callChainId)).thenReturn(detail(repositoryId, callChainId, "入口调用链"));

        mockMvc.perform(get("/api/codebase/repositories/{repositoryId}/call-chains", repositoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(callChainId.toString()))
                .andExpect(jsonPath("$[0].repositoryId").value(repositoryId.toString()))
                .andExpect(jsonPath("$[0].nodeCount").value(2));
        mockMvc.perform(get("/api/codebase/repositories/{repositoryId}/call-chains/{callChainId}",
                        repositoryId, callChainId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("入口调用链"))
                .andExpect(jsonPath("$.nodes").isArray());
    }

    @Test
    void historicalRevisionUsesTheRepositoryChainNodeRevisionRoute() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID callChainId = UUID.randomUUID();
        String nodeId = "a".repeat(64);
        UUID revisionId = UUID.randomUUID();
        when(callChains.revisionDetail(repositoryId, callChainId, nodeId, revisionId))
                .thenReturn(new CallChainNodeDetail(
                        nodeId, revisionId, "java", "Demo.entry", "void entry()", "入口",
                        "b".repeat(64), "src/Demo.java", 1, 2, "void entry() {}", observation(), List.of()));

        mockMvc.perform(get("/api/codebase/repositories/{repositoryId}/call-chains/{callChainId}"
                        + "/nodes/{nodeId}/revisions/{revisionId}", repositoryId, callChainId, nodeId, revisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value(nodeId))
                .andExpect(jsonPath("$.revisionId").value(revisionId.toString()))
                .andExpect(jsonPath("$.source").value("void entry() {}"));

        verify(callChains).revisionDetail(repositoryId, callChainId, nodeId, revisionId);
    }

    @Test
    void renameAndDeleteUseTheSameRepositoryAndChainIdentity() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID callChainId = UUID.randomUUID();
        when(callChains.rename(repositoryId, callChainId, "新名称"))
                .thenReturn(detail(repositoryId, callChainId, "新名称"));
        when(callChains.delete(repositoryId, callChainId))
                .thenReturn(detail(repositoryId, callChainId, "新名称"));

        mockMvc.perform(patch("/api/codebase/repositories/{repositoryId}/call-chains/{callChainId}",
                        repositoryId, callChainId)
                        .contentType("application/json")
                        .content("{\"name\":\"新名称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名称"));
        mockMvc.perform(delete("/api/codebase/repositories/{repositoryId}/call-chains/{callChainId}",
                        repositoryId, callChainId))
                .andExpect(status().isOk());

        verify(callChains).rename(eq(repositoryId), eq(callChainId), eq("新名称"));
        verify(callChains).delete(repositoryId, callChainId);
    }

    @Test
    void deletedChainReturnsGoneWithStableCode() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID callChainId = UUID.randomUUID();
        doThrow(new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DELETED, "调用链已删除"))
                .when(callChains).detail(repositoryId, callChainId);

        mockMvc.perform(get("/api/codebase/repositories/{repositoryId}/call-chains/{callChainId}",
                        repositoryId, callChainId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CALL_CHAIN_DELETED"));
    }

    private static CallChainSummary summary(UUID repositoryId, UUID callChainId, String name) {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        return new CallChainSummary(callChainId, repositoryId, "demo", name, 2, 1, now, now);
    }

    private static CallChainDetail detail(UUID repositoryId, UUID callChainId, String name) {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        return new CallChainDetail(callChainId, repositoryId, "demo", name, 2, 1,
                UUID.randomUUID(), UUID.randomUUID(), now, now, List.of(), List.of());
    }

    private static com.yuyu.salmonmind.codebase.api.RepositoryObservation observation() {
        return new com.yuyu.salmonmind.codebase.api.RepositoryObservation(
                "main", "0123456789012345678901234567890123456789", true,
                false, false, false, 0, 0, 0, 0, Instant.parse("2026-08-20T00:00:00Z"));
    }
}
