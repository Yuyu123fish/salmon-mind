package com.yuyu.salmonmind.conversation.api;

import java.util.UUID;

/**
 * Conversation 标题元数据事件。Title Entry 是 Conversation 级元数据，不是模型上下文节点：
 * 它推进 JSONL seq 与 PostgreSQL 最后确认序号，但<b>不推进 Active Path</b>，也不改变
 * ReactAgent Checkpoint 的上下文叶子。PostgreSQL 的 title 列是可由最新有效 Title Entry
 * 修复的列表索引；本 payload 不保存凭据或私有推理内容。
 *
 * @param title                  规范化标题（单行、去除首尾空白，满足既有标题长度约束）
 * @param sourceRunId            产生标题的 Run
 * @param sourceAssistantEntryId 产生标题的首次成功 Assistant Entry
 * @param provider               生成标题的模型提供方标识
 * @param model                  实际使用的模型名
 */
public record TitlePayload(
        String title,
        UUID sourceRunId,
        UUID sourceAssistantEntryId,
        String provider,
        String model
) implements EntryPayload {
}
