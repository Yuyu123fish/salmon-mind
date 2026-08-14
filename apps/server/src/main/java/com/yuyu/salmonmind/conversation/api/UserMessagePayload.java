package com.yuyu.salmonmind.conversation.api;

import java.util.UUID;

/**
 * 用户消息 payload；runId 使 JSONL 已写而数据库未写时仍可恢复 Run。
 */
public record UserMessagePayload(String text, UUID runId) implements EntryPayload {
}
