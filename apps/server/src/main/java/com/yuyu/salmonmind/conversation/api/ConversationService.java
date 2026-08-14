package com.yuyu.salmonmind.conversation.api;

import java.util.List;
import java.util.UUID;

/**
 * Conversation 用例入口。Workspace 由 application 通过 workspace::api 自行取得，
 * 调用方不传单例 Workspace ID；发送与重试在第 3 步接入 Agent 后补充。
 */
public interface ConversationService {

    ConversationSummary create();

    List<ConversationSummary> list();

    ConversationDetail open(UUID conversationId);
}
