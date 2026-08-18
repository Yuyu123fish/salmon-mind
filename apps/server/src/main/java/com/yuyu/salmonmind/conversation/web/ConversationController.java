package com.yuyu.salmonmind.conversation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.conversation.api.ConversationDetail;
import com.yuyu.salmonmind.conversation.api.ConversationService;
import com.yuyu.salmonmind.conversation.api.ConversationSummary;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

/**
 * Conversation 的 HTTP 转换入口：只依赖 conversation::api，把列表、创建、打开、发送、
 * 重试与继续生成用例暴露为稳定接口。
 * 发送 / 重试返回 SSE：请求线程同步阻塞直到 Run 完成，事件经 {@link SseEventWriter}
 * 实时写出；run_started 之前的前置错误仍由 {@link ConversationExceptionHandler}
 * 映射为 JSON 错误（响应尚未开始写流，可以安全切换 content-type）。
 */
@RestController
@RequestMapping("/api/conversations")
class ConversationController {

    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    ConversationController(ConversationService conversationService, ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    List<ConversationSummary> list() {
        return conversationService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConversationSummary create() {
        return conversationService.create();
    }

    @GetMapping("/{conversationId}")
    ConversationDetail open(@PathVariable UUID conversationId) {
        return conversationService.open(conversationId);
    }

    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    void send(
            @PathVariable UUID conversationId,
            @RequestBody SendMessageRequest request,
            HttpServletResponse response
    ) {
        // content-type 由 SseEventWriter 在第一帧写入时设置：run_started 之前的前置
        // 错误仍由异常处理器返回 JSON，不会被预设的 SSE 媒体类型阻塞
        conversationService.send(conversationId, request.text(), new SseEventWriter(response, objectMapper));
    }

    @PostMapping(value = "/{conversationId}/runs/{runId}/retry", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    void retry(
            @PathVariable UUID conversationId,
            @PathVariable UUID runId,
            HttpServletResponse response
    ) {
        conversationService.retry(conversationId, runId, new SseEventWriter(response, objectMapper));
    }

    @PostMapping(value = "/{conversationId}/entries/{assistantEntryId}/continue",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    void continueGeneration(
            @PathVariable UUID conversationId,
            @PathVariable UUID assistantEntryId,
            HttpServletResponse response
    ) {
        conversationService.continueGeneration(
                conversationId, assistantEntryId, new SseEventWriter(response, objectMapper));
    }

    /** 发送请求体；目前只有 text，保持包私有嵌套，出现第二个真实消费者前不单独成文件。 */
    record SendMessageRequest(String text) {
    }
}
