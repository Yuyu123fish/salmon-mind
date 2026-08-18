package com.yuyu.salmonmind.knowledge.web;

import com.yuyu.salmonmind.knowledge.api.DocumentDetail;
import com.yuyu.salmonmind.knowledge.api.DocumentSummary;
import com.yuyu.salmonmind.knowledge.api.DocumentUpload;
import com.yuyu.salmonmind.knowledge.api.EvidencePage;
import com.yuyu.salmonmind.knowledge.api.KnowledgeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Knowledge HTTP 转换层；不把 Object Key、Stream ID、物理索引或向量暴露给前端。 */
@RestController
@RequestMapping("/api/knowledge/documents")
class KnowledgeController {

    private final KnowledgeService knowledgeService;

    KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentSummary> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            // 空文本文件仍允许进入异步失败态；Multipart 没有文件则是同步输入错误。
            if (file == null) {
                throw new IllegalArgumentException("缺少上传文件");
            }
        }
        try (var input = file.getInputStream()) {
            DocumentSummary result = knowledgeService.upload(
                    new DocumentUpload(file.getOriginalFilename(), file.getContentType(), input));
            return ResponseEntity.accepted().body(result);
        }
    }

    @GetMapping
    List<DocumentSummary> list() {
        return knowledgeService.list();
    }

    @GetMapping("/{documentId}")
    DocumentDetail detail(@PathVariable UUID documentId) {
        return knowledgeService.detail(documentId);
    }

    @GetMapping("/{documentId}/evidence")
    EvidencePage evidence(
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return knowledgeService.evidence(documentId, page, size);
    }

    @PostMapping("/{documentId}/retry")
    DocumentSummary retry(@PathVariable UUID documentId) {
        return knowledgeService.retry(documentId);
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        knowledgeService.delete(documentId);
        return ResponseEntity.noContent().build();
    }

}
