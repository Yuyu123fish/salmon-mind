package com.yuyu.salmonmind.knowledge.web;

import com.yuyu.salmonmind.knowledge.api.UploadPolicy;
import com.yuyu.salmonmind.knowledge.api.UploadSessionView;
import com.yuyu.salmonmind.knowledge.application.KnowledgeUploadApplicationService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 可恢复上传的 HTTP 状态码、错误码和原始 part 请求体合同。 */
@WebMvcTest(KnowledgeUploadController.class)
@Import(KnowledgeExceptionHandler.class)
class KnowledgeUploadControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowledgeUploadApplicationService uploads;

    @Test
    void policyAndInitUseStableContracts() throws Exception {
        when(uploads.policy()).thenReturn(new UploadPolicy(true, 1000, 500, 256, 2));
        when(uploads.init(any())).thenReturn(view("UPLOADING"));

        mockMvc.perform(get("/api/knowledge/uploads/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxObjectBytes").value(1000));
        mockMvc.perform(post("/api/knowledge/uploads")
                        .contentType("application/json")
                        .content("{\"fileName\":\"a.txt\",\"declaredMediaType\":\"text/plain\",\"sizeBytes\":3,\"fileFingerprint\":\"f\",\"lastModifiedMillis\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADING"));
    }

    @Test
    void partUsesRawBodyAndMapsCrossWorkspaceSessionTo404() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(uploads.putPart(eq(sessionId), eq(1), eq(3L), eq("a".repeat(64)), any()))
                .thenReturn(view("UPLOADING"));
        mockMvc.perform(put("/api/knowledge/uploads/{id}/parts/1", sessionId)
                        .contentType("application/octet-stream")
                        .header("X-Upload-Part-SHA256", "a".repeat(64))
                        .content("abc".getBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedBytes").value(0));

        doThrow(new com.yuyu.salmonmind.knowledge.api.KnowledgeException(
                com.yuyu.salmonmind.knowledge.api.KnowledgeException.Code.UPLOAD_SESSION_NOT_FOUND, "上传会话不存在"))
                .when(uploads).cancel(sessionId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/knowledge/uploads/{id}", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_NOT_FOUND"));
    }

    private static UploadSessionView view(String status) {
        return new UploadSessionView(UUID.randomUUID(), status, "a.txt", "text/plain", 3,
                3, 1, List.of(), List.of(), 0, Instant.now(), Instant.now().plusSeconds(60), null, null);
    }
}
