package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次处理动作的执行元数据，从 trigger 用户 Entry 开始，以完整回答、可继续的长度中断、
 * 明确失败或中断结束。
 * Run 状态属于执行元数据，不替代 Conversation Entry：
 * FAILED 表示没有 durable Assistant 的模型调用失败（用户 Entry 保留、可重试），
 * INTERRUPTED 表示进程中断遗留的 RUNNING Run 被恢复为可重试的中断失败；两者都不会
 * 新增重复的用户 Entry。SUCCEEDED 通过 resultStatus 区分自然完成与可继续的长度中断。
 */
public record Run(
        UUID id,
        UUID conversationId,
        UUID triggerEntryId,
        RunStatus status,
        String errorCode,
        Instant startedAt,
        Instant endedAt,
        RunResultStatus resultStatus
) {

    public Run {
        if (status == RunStatus.SUCCEEDED && resultStatus == null) {
            resultStatus = RunResultStatus.COMPLETE;
        }
        if (status != RunStatus.SUCCEEDED && resultStatus != null) {
            throw new IllegalArgumentException("非成功 Run 不能携带 resultStatus");
        }
    }

    public Run(
            UUID id, UUID conversationId, UUID triggerEntryId, RunStatus status,
            String errorCode, Instant startedAt, Instant endedAt
    ) {
        this(id, conversationId, triggerEntryId, status, errorCode, startedAt, endedAt,
                status == RunStatus.SUCCEEDED ? RunResultStatus.COMPLETE : null);
    }

    public enum RunStatus {
        RUNNING,
        SUCCEEDED,
        FAILED,
        INTERRUPTED
    }
}
