package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentRunTraceItem;
import com.yuyu.salmonmind.agent.api.AgentRunTraceItem.Kind;
import com.yuyu.salmonmind.agent.api.AgentRunTraceItem.ToolStatus;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentToolCompleted;
import com.yuyu.salmonmind.agent.api.AgentToolFailed;
import com.yuyu.salmonmind.agent.api.AgentToolStarted;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 单次 ReactAgent stream 的有界 Trace 收集器，同时把安全事件按原顺序转发给调用方。
 * reasoning 连续到达时合并；工具开始确定位置，完成/失败只原位更新同一 Tool Call。
 * 达到 reasoning 上限后停止继续转发和保存该类内容，但工具生命周期仍继续收集。
 */
final class RunTraceCollector implements AgentStreamListener {

    private final AgentStreamListener delegate;
    private final int maxItems;
    private final int maxReasoningChars;
    private final int maxToolSummaryChars;
    private final List<AgentRunTraceItem> items = new ArrayList<>();
    private final Map<String, Integer> toolIndexes = new HashMap<>();
    private final Set<String> startedToolIds = new HashSet<>();
    private final Set<String> terminalToolIds = new HashSet<>();
    private final Set<String> pendingTerminalToolIds = new HashSet<>();
    private final Map<Long, TerminalEvent> pendingTerminalEvents = new TreeMap<>();
    private final Map<String, Long> toolStartedAtNanos = new HashMap<>();
    private final Map<String, String> toolNames = new HashMap<>();

    private int reasoningChars;
    private boolean reasoningLimitReached;
    private boolean closed;
    private long nextTerminalSequence;
    private long nextTerminalSequenceToEmit = 1L;

    RunTraceCollector(
            AgentStreamListener delegate,
            int maxItems,
            int maxReasoningChars,
            int maxToolSummaryChars
    ) {
        this.delegate = delegate;
        this.maxItems = Math.max(1, maxItems);
        this.maxReasoningChars = Math.max(0, maxReasoningChars);
        this.maxToolSummaryChars = Math.max(0, maxToolSummaryChars);
    }

    @Override
    public synchronized void onReasoningDelta(String delta) {
        if (closed) {
            return;
        }
        String cleaned = displayText(delta);
        if (cleaned.isEmpty() || reasoningLimitReached) {
            return;
        }
        int remaining = Math.max(0, maxReasoningChars - reasoningChars);
        String accepted = takeChars(cleaned, remaining);
        boolean truncated = accepted.length() < cleaned.length();
        if (!accepted.isEmpty()) {
            appendReasoning(accepted, truncated);
            reasoningChars += accepted.length();
            delegate.onReasoningDelta(accepted);
        } else {
            markLastReasoningTruncated();
        }
        if (truncated || reasoningChars >= maxReasoningChars) {
            reasoningLimitReached = true;
            markLastReasoningTruncated();
        }
    }

    @Override
    public synchronized void onDelta(String delta) {
        if (!closed) {
            delegate.onDelta(delta);
        }
    }

    @Override
    public synchronized void onComplete(AgentResult result) {
        if (closed) {
            return;
        }
        closed = true;
        delegate.onComplete(result);
    }

    @Override
    public synchronized void onError(AgentExecutionException error) {
        if (closed) {
            return;
        }
        closed = true;
        delegate.onError(error);
    }

    @Override
    public synchronized void onToolStarted(AgentToolStarted event) {
        if (closed || event == null || !startedToolIds.add(event.toolCallId())) {
            return;
        }
        toolStartedAtNanos.put(event.toolCallId(), System.nanoTime());
        toolNames.put(event.toolCallId(), event.toolName());
        TextBound summary = boundSummary(event.safeQuerySummary(), "工具执行中");
        int index = indexOfTool(event.toolCallId());
        if (index < 0) {
            index = addItem(AgentRunTraceItem.tool(
                    event.toolCallId(), event.toolName(), ToolStatus.RUNNING,
                    summary.text(), null, summary.truncated()));
            if (index >= 0) {
                toolIndexes.put(event.toolCallId(), index);
            }
        }
        delegate.onToolStarted(event);
    }

    @Override
    public synchronized void onToolCompleted(AgentToolCompleted event) {
        if (event == null) {
            return;
        }
        onToolCompletedOrdered(event, reserveTerminalSequence(event.toolCallId()));
    }

    @Override
    public synchronized void onToolFailed(AgentToolFailed event) {
        if (event == null) {
            return;
        }
        onToolFailedOrdered(event, reserveTerminalSequence(event.toolCallId()));
    }

    /** 拦截器在 handler 返回的瞬间预留序号，避免并行线程随后被调度时颠倒 UI 事件。 */
    synchronized long reserveTerminalSequence(String toolCallId) {
        if (closed || toolCallId == null || terminalToolIds.contains(toolCallId)
                || pendingTerminalToolIds.contains(toolCallId)) {
            return -1L;
        }
        pendingTerminalToolIds.add(toolCallId);
        return ++nextTerminalSequence;
    }

    synchronized void onToolCompletedOrdered(AgentToolCompleted event, long sequence) {
        if (event == null || sequence <= 0) {
            return;
        }
        pendingTerminalEvents.put(sequence, TerminalEvent.completed(event));
        drainTerminalEvents();
    }

    synchronized void onToolFailedOrdered(AgentToolFailed event, long sequence) {
        if (event == null || sequence <= 0) {
            return;
        }
        pendingTerminalEvents.put(sequence, TerminalEvent.failed(event));
        drainTerminalEvents();
    }

    synchronized List<AgentRunTraceItem> snapshot() {
        return List.copyOf(items);
    }

    /**
     * ReactAgent 超时可能由框架直接合成 ToolResponse，绕过同步 ToolCallback 的拦截器。
     * 在公开流事件确认该结果后补齐同一 Tool Call 的失败终态；迟到的真实回调仍受 closed
     * 与 terminalToolIds 双重保护。
     */
    synchronized void failFrameworkTimeout(String toolCallId, String toolName) {
        if (closed || toolCallId == null || terminalToolIds.contains(toolCallId)) {
            return;
        }
        long started = toolStartedAtNanos.getOrDefault(toolCallId, System.nanoTime());
        long durationMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        onToolFailed(new AgentToolFailed(
                toolCallId, toolName, durationMillis,
                ToolLifecycleInterceptor.TOOL_EXECUTION_TIMEOUT, "工具执行超时"));
    }

    /** 流已结束但框架没有发出 ToolResponse 时，收束仍运行的工具。 */
    synchronized void failUnterminatedTools() {
        for (String toolCallId : List.copyOf(startedToolIds)) {
            if (!terminalToolIds.contains(toolCallId)) {
                failFrameworkTimeout(toolCallId, toolNames.get(toolCallId));
            }
        }
    }

    synchronized boolean isToolTerminal(String toolCallId) {
        return terminalToolIds.contains(toolCallId);
    }

    /** 终态前保持开启；超时/完成后关闭，迟到的工具线程只允许自行结束而不能再写业务状态。 */
    synchronized boolean isOpen() {
        return !closed;
    }

    private void drainTerminalEvents() {
        while (true) {
            TerminalEvent event = pendingTerminalEvents.remove(nextTerminalSequenceToEmit);
            if (event == null) {
                return;
            }
            nextTerminalSequenceToEmit++;
            String toolCallId = event.toolCallId();
            pendingTerminalToolIds.remove(toolCallId);
            if (closed || !terminalToolIds.add(toolCallId)) {
                continue;
            }
            if (event.completed() != null) {
                AgentToolCompleted completed = event.completed();
                TextBound summary = boundSummary(completed.safeSummary(), "工具执行完成");
                upsertTool(completed.toolCallId(), completed.toolName(), ToolStatus.COMPLETED,
                        summary.text(), null, completed.truncated() || summary.truncated());
                delegate.onToolCompleted(completed);
            } else {
                AgentToolFailed failed = event.failed();
                TextBound summary = boundSummary(failed.safeMessage(), "工具执行失败");
                upsertTool(failed.toolCallId(), failed.toolName(), ToolStatus.FAILED,
                        summary.text(), failed.stableErrorCode(), summary.truncated());
                delegate.onToolFailed(failed);
            }
        }
    }

    private void appendReasoning(String delta, boolean truncated) {
        int lastIndex = items.size() - 1;
        if (lastIndex >= 0 && items.get(lastIndex).kind() == Kind.REASONING) {
            AgentRunTraceItem previous = items.get(lastIndex);
            items.set(lastIndex, AgentRunTraceItem.reasoning(
                    previous.text() + delta, previous.truncated() || truncated));
            return;
        }
        addItem(AgentRunTraceItem.reasoning(delta, truncated));
    }

    private void upsertTool(
            String toolCallId,
            String toolName,
            ToolStatus status,
            String summary,
            String errorCode,
            boolean truncated
    ) {
        int index = indexOfTool(toolCallId);
        AgentRunTraceItem next = AgentRunTraceItem.tool(
                toolCallId, toolName, status, summary, errorCode, truncated);
        if (index >= 0) {
            items.set(index, next);
            return;
        }
        int added = addItem(next);
        if (added >= 0) {
            toolIndexes.put(toolCallId, added);
        }
    }

    private int indexOfTool(String toolCallId) {
        Integer index = toolIndexes.get(toolCallId);
        return index == null ? -1 : index;
    }

    private int addItem(AgentRunTraceItem item) {
        if (items.size() >= maxItems) {
            markLastItemTruncated();
            return -1;
        }
        items.add(item);
        return items.size() - 1;
    }

    private void markLastReasoningTruncated() {
        for (int i = items.size() - 1; i >= 0; i--) {
            AgentRunTraceItem item = items.get(i);
            if (item.kind() == Kind.REASONING) {
                items.set(i, AgentRunTraceItem.reasoning(item.text(), true));
                return;
            }
        }
    }

    private void markLastItemTruncated() {
        if (items.isEmpty()) {
            return;
        }
        int index = items.size() - 1;
        AgentRunTraceItem item = items.get(index);
        if (item.kind() == Kind.REASONING) {
            items.set(index, AgentRunTraceItem.reasoning(item.text(), true));
        } else {
            items.set(index, AgentRunTraceItem.tool(
                    item.toolCallId(), item.toolName(), item.toolStatus(), item.safeSummary(),
                    item.stableErrorCode(), true));
        }
    }

    private TextBound boundSummary(String raw, String fallback) {
        String cleaned = displayText(raw).trim();
        if (cleaned.isEmpty()) {
            cleaned = fallback;
        }
        String accepted = takeChars(cleaned, maxToolSummaryChars);
        return new TextBound(accepted, accepted.length() < cleaned.length());
    }

    private static String displayText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        raw.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint) || codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                cleaned.appendCodePoint(codePoint);
            }
        });
        return cleaned.toString();
    }

    private static String takeChars(String value, int maximum) {
        if (maximum <= 0) {
            return "";
        }
        if (value.length() <= maximum) {
            return value;
        }
        int end = maximum;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private record TextBound(String text, boolean truncated) {
    }

    private record TerminalEvent(
            AgentToolCompleted completed,
            AgentToolFailed failed
    ) {
        static TerminalEvent completed(AgentToolCompleted event) {
            return new TerminalEvent(event, null);
        }

        static TerminalEvent failed(AgentToolFailed event) {
            return new TerminalEvent(null, event);
        }

        String toolCallId() {
            return completed != null ? completed.toolCallId() : failed.toolCallId();
        }
    }
}
