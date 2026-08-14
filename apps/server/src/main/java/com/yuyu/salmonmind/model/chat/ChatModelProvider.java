package com.yuyu.salmonmind.model.chat;

/**
 * Chat 模型提供入口：延迟创建并返回模型句柄；配置缺失时抛 {@link ChatModelException}。
 */
public interface ChatModelProvider {

    ChatModelHandle get();
}
