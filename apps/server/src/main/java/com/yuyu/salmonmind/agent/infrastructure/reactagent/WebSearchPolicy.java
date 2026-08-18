package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.yuyu.salmonmind.agent.api.AgentMessage;

import java.util.List;
import java.util.Locale;

/** 从当前用户消息提取最小联网边界；默认允许，明确禁止词才关闭网页 Tool。 */
final class WebSearchPolicy {

    private WebSearchPolicy() {
    }

    static boolean allows(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return true;
        }
        AgentMessage latestUser = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == AgentMessage.Role.USER) {
                latestUser = messages.get(i);
                break;
            }
        }
        if (latestUser == null || latestUser.text() == null) {
            return true;
        }
        String text = latestUser.text().toLowerCase(Locale.ROOT);
        return !(text.contains("禁止联网") || text.contains("不要联网") || text.contains("不联网")
                || text.contains("禁止网页") || text.contains("禁止使用网络")
                || text.contains("请勿联网") || text.contains("请勿上网")
                || text.contains("请勿访问互联网") || text.contains("请勿访问网页")
                || text.contains("不要使用网络") || text.contains("不要用网络")
                || text.contains("不要访问互联网") || text.contains("不要访问网页")
                || text.contains("离线回答") || text.contains("仅使用本地")
                || text.contains("只用本地") || text.contains("不要上网")
                || text.contains("do not browse") || text.contains("don't browse")
                || text.contains("no web") || text.contains("offline only"));
    }
}
