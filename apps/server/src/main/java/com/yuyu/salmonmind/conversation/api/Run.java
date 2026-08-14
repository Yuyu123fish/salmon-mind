package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次处理动作的执行元数据，从 trigger 用户 Entry 开始，以回答、明确失败或中断结束。
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
