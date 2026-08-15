package com.yuyu.salmonmind.conversation.api;

/**
 * Entry 的类型化 payload。sealed 约束：新增 payload 类型必须同步修改本接口的
 * permits 列表与 JsonlCodec 的编码/解码分支，否则历史格式合同断裂。
 */
public sealed interface EntryPayload permits UserMessagePayload, AssistantMessagePayload, CompactionPayload, TitlePayload {
}
