package com.yuyu.salmonmind.knowledge.web;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.api.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证删除端点的 HTTP 稳定合同；不启动 Knowledge 基础设施，只检查控制器和错误 Advice 的边界。
 */
@WebMvcTest(KnowledgeController.class)
@Import(KnowledgeExceptionHandler.class)
class KnowledgeControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowledgeService knowledgeService;

    @Test
    void successfulDeletionReturnsNoContent() throws Exception {
        UUID documentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/knowledge/documents/{documentId}", documentId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(knowledgeService).delete(documentId);
    }

    @Test
    void missingDocumentReturnsStableNotFoundError() throws Exception {
        UUID documentId = UUID.randomUUID();
        doThrow(new KnowledgeException(KnowledgeException.Code.DOCUMENT_NOT_FOUND, "文档不存在"))
                .when(knowledgeService).delete(documentId);

        mockMvc.perform(delete("/api/knowledge/documents/{documentId}", documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("文档不存在"));
    }

    @Test
    void ineligibleDocumentReturnsConflict() throws Exception {
        UUID documentId = UUID.randomUUID();
        doThrow(new KnowledgeException(KnowledgeException.Code.DOCUMENT_DELETE_NOT_ALLOWED, "文档当前不可删除"))
                .when(knowledgeService).delete(documentId);

        mockMvc.perform(delete("/api/knowledge/documents/{documentId}", documentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_DELETE_NOT_ALLOWED"));
    }

    @Test
    void incompleteDeletionReturnsServiceUnavailable() throws Exception {
        UUID documentId = UUID.randomUUID();
        doThrow(new KnowledgeException(KnowledgeException.Code.DOCUMENT_DELETE_INCOMPLETE, "文档删除未完成，请重试"))
                .when(knowledgeService).delete(documentId);

        mockMvc.perform(delete("/api/knowledge/documents/{documentId}", documentId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOCUMENT_DELETE_INCOMPLETE"));
    }
}
