package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.yuyu.salmonmind.agent.api.AgentMessage;

import java.util.List;
import java.util.Locale;

/**
 * 从当前 Run 最新用户消息提取证据访问边界。
 *
 * <p>它只执行用户明确表达的负向限制，不猜测问题是否需要检索；正向证据决策仍由
 * Agent 系统策略完成。真正的外部访问还会在 Tool 拦截器处再次硬阻断。
 */
final class EvidenceAccessPolicy {

    private EvidenceAccessPolicy() {
    }

    static Decision decide(List<AgentMessage> messages) {
        String text = latestUserText(messages);
        if (text == null) {
            return new Decision(true, true);
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        boolean allDisabled = containsAnyRetrievalRestriction(normalized);
        boolean localDisabled = allDisabled || containsLocalRestriction(normalized);
        boolean webDisabled = allDisabled || containsWebRestriction(normalized);
        return new Decision(!localDisabled, !webDisabled);
    }

    private static String latestUserText(List<AgentMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage message = messages.get(i);
            if (message != null && message.role() == AgentMessage.Role.USER
                    && message.text() != null) {
                return message.text();
            }
        }
        return null;
    }

    private static boolean containsAnyRetrievalRestriction(String text) {
        return contains(text,
                "只根据当前对话", "只基于当前对话", "仅根据当前对话", "仅基于当前对话",
                "只用当前对话", "不要查询任何资料", "不要查任何资料", "不要检索任何资料",
                "不要查询资料", "不要查资料", "不要检索资料", "禁止检索",
                "不要使用任何工具", "不要调用任何工具", "only use this conversation",
                "only use the current conversation", "do not search any sources", "no retrieval");
    }

    private static boolean containsLocalRestriction(String text) {
        return contains(text,
                "不要查询本地资料", "不要查本地资料", "不要检索本地资料",
                "不要使用本地资料", "不要使用本地知识库", "禁止本地检索",
                "no local search", "do not search local");
    }

    private static boolean containsWebRestriction(String text) {
        return contains(text,
                "禁止联网", "不要联网", "不联网", "请勿联网", "请勿上网",
                "请勿访问互联网", "请勿访问网页", "不要使用网络", "不要用网络",
                "不要访问互联网", "不要访问网页", "不要上网", "离线回答",
                "仅使用本地", "只使用本地", "仅根据本地", "只根据本地",
                "仅用本地", "只用本地", "do not browse", "don't browse",
                "no web", "offline only");
    }

    private static boolean contains(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    record Decision(boolean allowLocal, boolean allowWeb) {
    }
}
