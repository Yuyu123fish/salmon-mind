package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次处理动作的执行元数据，从 trigger 用户 Entry 开始，以回答、明确失败或中断结束。
 * Run 状态属于执行元数据，不替代 Conversation Entry：
 * FAILED 表示模型调用失败（用户 Entry 保留、可重试），INTERRUPTED 表示进程中断遗留的
 * RUNNING Run 被恢复为可重试的中断失败；两者都不会新增重复的用户 Entry。
 */
public record Run(
        UUID id,
        UUID conversationId,
        UUID triggerEntryId,
        RunStatus status,
        String errorCode,
        Instant startedAt,
        Instant endedAt
) {

    public enum RunStatus {
        RUNNING,
        SUCCEEDED,
        FAILED,
        INTERRUPTED
    }
}
