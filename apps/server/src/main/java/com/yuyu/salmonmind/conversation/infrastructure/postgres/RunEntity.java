package com.yuyu.salmonmind.conversation.infrastructure.postgres;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

// autoResultMap 让 PostgreSQL UUID 的 TypeHandler 在查询结果中生效
@TableName(value = "conversation_runs", autoResultMap = true)
public class RunEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID conversationId;
    private UUID triggerEntryId;
    private String status;
    private String errorCode;
    private Instant startedAt;
    private Instant endedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public UUID getTriggerEntryId() {
        return triggerEntryId;
    }

    public void setTriggerEntryId(UUID triggerEntryId) {
        this.triggerEntryId = triggerEntryId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }
}
