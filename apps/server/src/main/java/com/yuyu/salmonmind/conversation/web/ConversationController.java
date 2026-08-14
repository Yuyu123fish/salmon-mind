package com.yuyu.salmonmind.conversation.web;

import com.yuyu.salmonmind.conversation.api.ConversationDetail;
import com.yuyu.salmonmind.conversation.api.ConversationRunResult;
import com.yuyu.salmonmind.conversation.api.ConversationService;
import com.yuyu.salmonmind.conversation.api.ConversationSummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Conversation 的 HTTP 转换入口：只依赖 conversation::api，把五个用例暴露为稳定 JSON。
 * 发送请求的 body 只有 text；错误由 {@link ConversationExceptionHandler} 统一映射为稳定状态码。
 */
@RestController
@RequestMapping("/api/conversations")
class ConversationController {

    private final ConversationService conversationService;

    ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
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

    @PostMapping("/{conversationId}/messages")
    ConversationRunResult send(
            @PathVariable UUID conversationId,
            @RequestBody SendMessageRequest request
    ) {
        return conversationService.send(conversationId, request.text());
    }

    @PostMapping("/{conversationId}/runs/{runId}/retry")
    ConversationRunResult retry(
            @PathVariable UUID conversationId,
            @PathVariable UUID runId
    ) {
        return conversationService.retry(conversationId, runId);
    }

    /** 发送请求体；目前只有 text，保持包私有嵌套，出现第二个真实消费者前不单独成文件。 */
    record SendMessageRequest(String text) {
    }
}
