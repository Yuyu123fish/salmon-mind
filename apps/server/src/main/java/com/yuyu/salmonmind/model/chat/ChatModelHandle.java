package com.yuyu.salmonmind.model.chat;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 已创建的 ChatModel 及其提供方与模型名。
 * 受控技术结果：只允许 agent 内部使用，不得进入业务 api 或 HTTP。
 */
public record ChatModelHandle(ChatModel chatModel, String provider, String modelName) {
}
