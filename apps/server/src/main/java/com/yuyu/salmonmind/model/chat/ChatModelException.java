package com.yuyu.salmonmind.model.chat;

/**
 * Chat 模型配置失败：base URL、API key 或 model name 缺失时抛出，
 * 由 agent 模块映射为稳定错误，不暴露到 HTTP。
 */
public class ChatModelException extends RuntimeException {

    public ChatModelException(String message) {
        super(message);
    }
}
