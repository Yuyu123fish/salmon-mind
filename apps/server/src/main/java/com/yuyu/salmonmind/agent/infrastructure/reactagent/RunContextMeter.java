package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次 Run 的真实输入计量器。
 *
 * <p>它不按工具配置的最大结果总量预留空间，而是在每次模型调用前读取当前实际
 * Tool Response、Tool Call 参数、工具描述和消息封装进行估算。普通工具结果不能占用
 * Run Closure Reserve，只有调用链暂存等收尾工具可以使用这段空间。超过物理输入硬边界
 * 时只清理较旧的 Tool Response，保留最近的工具闭环；清理后仍超限则抛出稳定的上下文
 * 限制异常。旧 JSONL 不包含该对象，它只存在于当前 RunnableConfig metadata。</p>
 */
final class RunContextMeter {

    static final String METADATA_KEY = "salmon:agent:run-context-meter";
    private static final long MESSAGE_OVERHEAD = 8L;
    private static final long TOOL_RESPONSE_OVERHEAD = 8L;
    private static final String CLEANED_RESULT_PREFIX = "[tool-result-cleaned:";

    private final long physicalContextWindow;
    private final long cleanupTriggerInputTokens;
    private final long outputReserve;
    private final long retainedTailTarget;
    private final long closureReserve;
    private long estimatedInputTokens;
    private long pendingResultTokens;
    private long remainingInputTokens;
    private boolean cleaned;
    private Snapshot latestSnapshot;
    private final Map<String, Snapshot> resultSnapshots = new HashMap<>();

    RunContextMeter(long physicalContextWindow, long outputReserve, long retainedTailTarget) {
        this(physicalContextWindow, physicalContextWindow - outputReserve,
                outputReserve, retainedTailTarget, 0L);
    }

    RunContextMeter(
            long physicalContextWindow,
            long cleanupTriggerInputTokens,
            long outputReserve,
            long retainedTailTarget
    ) {
        this(physicalContextWindow, cleanupTriggerInputTokens, outputReserve,
                retainedTailTarget, 0L);
    }

    RunContextMeter(
            long physicalContextWindow,
            long cleanupTriggerInputTokens,
            long outputReserve,
            long retainedTailTarget,
            long closureReserve
    ) {
        if (physicalContextWindow <= 0 || cleanupTriggerInputTokens <= 0 || outputReserve < 0
                || retainedTailTarget < 0 || closureReserve < 0
                || outputReserve > physicalContextWindow
                || closureReserve > physicalContextWindow - outputReserve
                || cleanupTriggerInputTokens > physicalContextWindow - outputReserve) {
            throw new IllegalArgumentException("Run 输入计量预算不合法");
        }
        this.physicalContextWindow = physicalContextWindow;
        this.cleanupTriggerInputTokens = cleanupTriggerInputTokens;
        this.outputReserve = outputReserve;
        this.retainedTailTarget = retainedTailTarget;
        this.closureReserve = closureReserve;
        this.remainingInputTokens = hardInputCeiling();
        this.latestSnapshot = new Snapshot(0L, remainingInputTokens, false, false);
    }

    synchronized Prepared prepare(ModelRequest request) {
        // 下一次模型请求会把此前的 Tool Response 纳入实际输入；随后开始的新一批
        // Tool Call 结果要重新累计，避免把多个模型请求的结果 token 相加。
        pendingResultTokens = 0L;
        long initial = estimateRequest(request);
        if (initial < cleanupTriggerInputTokens) {
            update(initial, false);
            return new Prepared(request, initial, false);
        }
        List<Message> cleanedMessages = cleanOldToolResponses(request.getMessages());
        ModelRequest candidate = cleanedMessages.equals(request.getMessages())
                ? request : ModelRequest.builder(request).messages(cleanedMessages).build();
        long afterCleanup = estimateRequest(candidate);
        update(afterCleanup, candidate != request);
        if (afterCleanup > hardInputCeiling()) {
            throw new IllegalStateException(
                    "context input limit exceeded: " + afterCleanup + " > " + hardInputCeiling());
        }
        return new Prepared(candidate, afterCleanup, candidate != request);
    }

    synchronized void recordToolResult(long estimatedTokens, boolean truncated) {
        recordToolResult(null, estimatedTokens, truncated, true);
    }

    /**
     * 结算当前模型响应产生的一个 Tool Result。
     *
     * @param closureTool 是否为允许使用 Run Closure Reserve 的收尾工具
     * @return 结果是否仍能落在本轮可用输入空间内
     */
    synchronized boolean recordToolResult(
            long estimatedTokens, boolean truncated, boolean closureTool
    ) {
        return recordToolResult(null, estimatedTokens, truncated, closureTool);
    }

    synchronized boolean recordToolResult(
            String toolCallId, long estimatedTokens, boolean truncated, boolean closureTool
    ) {
        long normalized = Math.max(0L, estimatedTokens);
        if (normalized > remainingToolResultTokens(closureTool)) {
            return false;
        }
        pendingResultTokens = saturatingAdd(pendingResultTokens, normalized);
        remainingInputTokens = Math.max(
                0L, hardInputCeiling() - estimatedInputTokens - pendingResultTokens);
        latestSnapshot = new Snapshot(
                normalized, remainingInputTokens, cleaned, truncated);
        if (toolCallId != null) {
            resultSnapshots.put(toolCallId, latestSnapshot);
        }
        return true;
    }

    /** 当前结果若不是收尾工具，必须保留下来的闭环空间之外还能使用多少 token。 */
    synchronized long remainingToolResultTokens(boolean closureTool) {
        long remaining = hardInputCeiling() - estimatedInputTokens - pendingResultTokens;
        if (!closureTool) {
            remaining -= closureReserve;
        }
        return Math.max(0L, remaining);
    }

    synchronized Snapshot snapshot() {
        return latestSnapshot;
    }

    synchronized Snapshot snapshot(String toolCallId) {
        return toolCallId == null ? latestSnapshot
                : resultSnapshots.getOrDefault(toolCallId, latestSnapshot);
    }

    private void update(long estimated, boolean cleanedNow) {
        estimatedInputTokens = estimated;
        remainingInputTokens = Math.max(0L, hardInputCeiling() - estimated);
        cleaned = cleaned || cleanedNow;
    }

    private long hardInputCeiling() {
        return physicalContextWindow - outputReserve;
    }

    private List<Message> cleanOldToolResponses(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Set<Integer> keep = new HashSet<>();
        long retained = 0L;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (!(message instanceof ToolResponseMessage toolResponse)) {
                continue;
            }
            // 至少保留最近一组 Tool Response；继续向前保留到 retained-tail 目标。
            if (retained == 0L || retained < retainedTailTarget) {
                keep.add(index);
                retained += estimateMessage(toolResponse);
            }
        }
        List<Message> result = new ArrayList<>(messages.size());
        boolean changed = false;
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message instanceof ToolResponseMessage && !keep.contains(index)) {
                result.add(cleanedToolResponse((ToolResponseMessage) message));
                changed = true;
                continue;
            }
            result.add(message);
        }
        return changed ? List.copyOf(result) : messages;
    }

    /** 清理正文但保留 response id/name，使 Provider 仍能看到完整的 call/result 配对。 */
    private static ToolResponseMessage cleanedToolResponse(ToolResponseMessage original) {
        List<ToolResponseMessage.ToolResponse> responses = original.getResponses().stream()
                .map(response -> new ToolResponseMessage.ToolResponse(
                        response.id(), response.name(), CLEANED_RESULT_PREFIX + response.id() + "]"))
                .toList();
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private long estimateRequest(ModelRequest request) {
        long total = 0L;
        if (request.getSystemMessage() != null) {
            total += estimateText(request.getSystemMessage().getText()) + MESSAGE_OVERHEAD;
        }
        total += estimateMessages(request.getMessages());
        Set<String> countedToolNames = new HashSet<>();
        if (request.getOptions() != null && request.getOptions().getToolCallbacks() != null) {
            for (ToolCallback callback : request.getOptions().getToolCallbacks()) {
                total += estimateToolCallback(callback, countedToolNames);
            }
        }
        if (request.getDynamicToolCallbacks() != null) {
            for (ToolCallback callback : request.getDynamicToolCallbacks()) {
                total += estimateToolCallback(callback, countedToolNames);
            }
        }
        if (request.getToolDescriptions() != null) {
            for (var entry : request.getToolDescriptions().entrySet()) {
                if (countedToolNames.add(entry.getKey())) {
                    total += estimateText(entry.getKey()) + estimateText(entry.getValue()) + MESSAGE_OVERHEAD;
                }
            }
        }
        if (request.getTools() != null) {
            for (String toolName : request.getTools()) {
                if (toolName != null && countedToolNames.add(toolName)) {
                    total += estimateText(toolName) + MESSAGE_OVERHEAD;
                }
            }
        }
        return total;
    }

    private static long estimateToolCallback(ToolCallback callback, Set<String> countedToolNames) {
        if (callback == null || callback.getToolDefinition() == null) {
            return 0L;
        }
        var definition = callback.getToolDefinition();
        if (!countedToolNames.add(definition.name())) {
            return 0L;
        }
        return estimateText(definition.name()) + estimateText(definition.description())
                + estimateText(definition.inputSchema()) + MESSAGE_OVERHEAD;
    }

    private static long estimateMessages(List<Message> messages) {
        if (messages == null) {
            return 0L;
        }
        long total = 0L;
        for (Message message : messages) {
            total += estimateMessage(message);
        }
        return total;
    }

    private static long estimateMessage(Message message) {
        if (message instanceof ToolResponseMessage toolResponse) {
            long total = MESSAGE_OVERHEAD;
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                total += estimateText(response.id()) + estimateText(response.name())
                        + estimateText(response.responseData()) + TOOL_RESPONSE_OVERHEAD;
            }
            return total;
        }
        if (message instanceof AssistantMessage assistant) {
            long total = estimateText(assistant.getText()) + MESSAGE_OVERHEAD;
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                total += estimateText(call.id()) + estimateText(call.name())
                        + estimateText(call.arguments()) + TOOL_RESPONSE_OVERHEAD;
            }
            return total;
        }
        return estimateText(message == null ? null : message.getText()) + MESSAGE_OVERHEAD;
    }

    private static long estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 1L;
        }
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1L, (bytes + 1L) / 2L);
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    record Prepared(ModelRequest request, long estimatedInputTokens, boolean cleaned) {
    }

    record Snapshot(
            long estimatedResultTokens,
            long remainingInputTokens,
            boolean cleaned,
            boolean resultTruncated
    ) {
    }
}
