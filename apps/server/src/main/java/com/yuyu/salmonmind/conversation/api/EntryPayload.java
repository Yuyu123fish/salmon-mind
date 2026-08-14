package com.yuyu.salmonmind.conversation.api;

/**
 * Entry 的类型化 payload。
 */
public sealed interface EntryPayload permits UserMessagePayload, AssistantMessagePayload, CompactionPayload {
}
