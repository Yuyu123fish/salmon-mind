package com.yuyu.salmonmind.knowledge.web;

import com.yuyu.salmonmind.knowledge.api.DocumentSummary;
import com.yuyu.salmonmind.knowledge.api.UploadInitRequest;
import com.yuyu.salmonmind.knowledge.api.UploadPolicy;
import com.yuyu.salmonmind.knowledge.api.UploadSessionView;
import com.yuyu.salmonmind.knowledge.application.KnowledgeUploadApplicationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.io.InputStream;
import java.util.UUID;

/** 可恢复上传 HTTP 转换层；请求体只经过 Server，不向浏览器暴露 RustFS/Redis 身份。 */
@RestController
@RequestMapping("/api/knowledge/uploads")
class KnowledgeUploadController {

    private final KnowledgeUploadApplicationService uploads;

    KnowledgeUploadController(KnowledgeUploadApplicationService uploads) {
        this.uploads = uploads;
    }

    @GetMapping("/policy")
    UploadPolicy policy() {
        return uploads.policy();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UploadSessionView> init(@RequestBody UploadInitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(uploads.init(request));
    }

    @GetMapping("/{sessionId}")
    UploadSessionView get(@PathVariable UUID sessionId,
                          @RequestHeader(value = "X-Upload-File-Fingerprint", required = false)
                          String fileFingerprint) {
        return uploads.get(sessionId, fileFingerprint);
    }

    @PutMapping(value = "/{sessionId}/parts/{partNumber}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    UploadSessionView part(
            @PathVariable UUID sessionId,
            @PathVariable int partNumber,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, defaultValue = "-1") long contentLength,
            @RequestHeader(value = "X-Upload-Part-SHA256", required = false) String checksum,
            HttpServletRequest request
    ) throws java.io.IOException {
        return uploads.putPart(sessionId, partNumber, contentLength, checksum, request.getInputStream());
    }

    @PostMapping("/{sessionId}/complete")
    ResponseEntity<DocumentSummary> complete(@PathVariable UUID sessionId) {
        return ResponseEntity.accepted().body(uploads.complete(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    ResponseEntity<Void> cancel(@PathVariable UUID sessionId) {
        uploads.cancel(sessionId);
        return ResponseEntity.noContent().build();
    }
}
