package com.yuyu.salmonmind.conversation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.conversation.api.RunStreamListener;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 把 {@link RunStreamListener} 事件序列化为标准 SSE 帧写入 HTTP 响应。
 * content-type 在写入第一帧时才设置：run_started 之前的前置错误由异常处理器
 * 返回 JSON（响应未开始写流，可以安全切换媒体类型）。
 * 写帧失败（客户端断开）后停止发送但不再抛出：Run 的持久化语义不依赖传输，
 * 断线后刷新页面重新打开 Conversation 即可看到权威状态。
 */
class SseEventWriter implements RunStreamListener {

    private final HttpServletResponse response;
    private final ObjectMapper mapper;
    private volatile boolean disconnected;
    private volatile boolean contentTypeSet;

    SseEventWriter(HttpServletResponse response, ObjectMapper mapper) {
        this.response = response;
        this.mapper = mapper;
    }

    @Override
    public void onRunStarted(RunStarted event) {
        write("run_started", event);
    }

    @Override
    public void onCompactionCompleted(CompactionCompleted event) {
        write("compaction_completed", event);
    }

    @Override
    public void onAssistantDelta(AssistantDelta event) {
        write("assistant_delta", event);
    }

    @Override
    public void onToolStarted(ToolStarted event) {
        write("tool_started", event);
    }

    @Override
    public void onToolCompleted(ToolCompleted event) {
        write("tool_completed", event);
    }

    @Override
    public void onToolFailed(ToolFailed event) {
        write("tool_failed", event);
    }

    @Override
    public void onAssistantCompleted(AssistantCompleted event) {
        write("assistant_completed", event);
    }

    @Override
    public void onTitleUpdated(TitleUpdated event) {
        write("title_updated", event);
    }

    @Override
    public void onRunCompleted(RunCompleted event) {
        write("run_completed", event);
    }

    @Override
    public void onRunFailed(RunFailed event) {
        write("run_failed", event);
    }

    // 标准 SSE 帧：event 行 + 单行 JSON data + 空行分隔；每帧立即 flush 保证前端实时可见
    private void write(String event, Object data) {
        if (disconnected) {
            return;
        }
        try {
            if (!contentTypeSet) {
                // 第一帧前设置：保证流内中文按 UTF-8 解码，且不污染流开始前的 JSON 错误响应
                response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                contentTypeSet = true;
            }
            PrintWriter writer = response.getWriter();
            writer.write("event: " + event + "\n");
            writer.write("data: " + mapper.writeValueAsString(data) + "\n\n");
            writer.flush();
        } catch (IOException ex) {
            // 客户端已断开（含序列化失败）：后续事件不再发送；Run 仍在 application 侧继续完成持久化
            disconnected = true;
        }
    }
}
