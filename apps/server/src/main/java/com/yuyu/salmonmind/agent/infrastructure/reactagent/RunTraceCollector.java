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
import java.util.List;
import java.util.Map;

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

    private int reasoningChars;
    private boolean reasoningLimitReached;

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
    public void onReasoningDelta(String delta) {
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
    public void onDelta(String delta) {
        delegate.onDelta(delta);
    }

    @Override
    public void onComplete(AgentResult result) {
        delegate.onComplete(result);
    }

    @Override
    public void onError(AgentExecutionException error) {
        delegate.onError(error);
    }

    @Override
    public void onToolStarted(AgentToolStarted event) {
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
    public void onToolCompleted(AgentToolCompleted event) {
        TextBound summary = boundSummary(event.safeSummary(), "工具执行完成");
        upsertTool(event.toolCallId(), event.toolName(), ToolStatus.COMPLETED,
                summary.text(), null, event.truncated() || summary.truncated());
        delegate.onToolCompleted(event);
    }

    @Override
    public void onToolFailed(AgentToolFailed event) {
        TextBound summary = boundSummary(event.safeMessage(), "工具执行失败");
        upsertTool(event.toolCallId(), event.toolName(), ToolStatus.FAILED,
                summary.text(), event.stableErrorCode(), summary.truncated());
        delegate.onToolFailed(event);
    }

    List<AgentRunTraceItem> snapshot() {
        return List.copyOf(items);
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
}
