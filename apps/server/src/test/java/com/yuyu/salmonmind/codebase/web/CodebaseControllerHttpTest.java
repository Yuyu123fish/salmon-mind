package com.yuyu.salmonmind.codebase.web;

import com.yuyu.salmonmind.codebase.api.CodebaseCatalogView;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.PlatformView;
import com.yuyu.salmonmind.codebase.api.RepositoryView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证顶部 catalog HTTP 合同和安全错误映射，不启动 Git 或本地 catalog 文件系统。 */
@WebMvcTest(CodebaseController.class)
@Import(CodebaseExceptionHandler.class)
class CodebaseControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CodebaseService codebaseService;

    @Test
    void catalogReturnsPlatformAndActiveRepository() throws Exception {
        RepositoryView repository = repository();
        when(codebaseService.catalog()).thenReturn(new CodebaseCatalogView(
                new PlatformView("Windows 11", "\\\\", true, "D:\\repo"), true,
                repository.id(), List.of(repository), List.of()));

        mockMvc.perform(get("/api/codebase"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRepositoryId").value(repository.id().toString()))
                .andExpect(jsonPath("$.repositories[0].path").value(repository.path()))
                .andExpect(jsonPath("$.platform.windows").value(true));
    }

    @Test
    void registerPreservesInputContractAndReturnsCreated() throws Exception {
        RepositoryView repository = repository();
        when(codebaseService.registerRepository(eq("D:/repo"), eq("demo"), eq(List.of("演示"))))
                .thenReturn(repository);

        mockMvc.perform(post("/api/codebase/repositories")
                        .contentType("application/json")
                        .content("{\"path\":\"D:/repo\",\"name\":\"demo\",\"aliases\":[\"演示\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(repository.id().toString()));

        verify(codebaseService).registerRepository("D:/repo", "demo", List.of("演示"));
    }

    @Test
    void invalidAbsolutePathReturnsStableSafeError() throws Exception {
        doThrow(new CodebaseException(CodebaseErrorCode.INVALID_ABSOLUTE_PATH, "必须提供绝对路径"))
                .when(codebaseService).registerRepository(any(), any(), any());

        mockMvc.perform(post("/api/codebase/repositories")
                        .contentType("application/json")
                        .content("{\"path\":\"relative/repo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ABSOLUTE_PATH"))
                .andExpect(jsonPath("$.message").value("必须提供 Server 所在机器上的绝对路径"));
    }

    private RepositoryView repository() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        return new RepositoryView(id, "D:\\repo", "demo", List.of("演示"), true,
                "AVAILABLE", "main", "0123456789012345678901234567890123456789", false,
                false, false, false, 0, 0, 0, 0, null, now, now);
    }
}
