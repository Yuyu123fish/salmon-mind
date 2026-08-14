package com.yuyu.salmonmind.conversation.infrastructure.postgres;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

// autoResultMap 让 PostgreSQL UUID 的 TypeHandler 在查询结果中生效
@TableName(value = "conversations", autoResultMap = true)
public class ConversationEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID workspaceId;
    private String title;
    private Integer historyFormatVersion;
    private UUID activeLeafEntryId;
    private Long lastConfirmedSeq;
    private UUID latestCompactionEntryId;
    private Long latestCompactionSeq;
    private Long latestCompactionByteOffset;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getHistoryFormatVersion() {
        return historyFormatVersion;
    }

    public void setHistoryFormatVersion(Integer historyFormatVersion) {
        this.historyFormatVersion = historyFormatVersion;
    }

    public UUID getActiveLeafEntryId() {
        return activeLeafEntryId;
    }

    public void setActiveLeafEntryId(UUID activeLeafEntryId) {
        this.activeLeafEntryId = activeLeafEntryId;
    }

    public Long getLastConfirmedSeq() {
        return lastConfirmedSeq;
    }

    public void setLastConfirmedSeq(Long lastConfirmedSeq) {
        this.lastConfirmedSeq = lastConfirmedSeq;
    }

    public UUID getLatestCompactionEntryId() {
        return latestCompactionEntryId;
    }

    public void setLatestCompactionEntryId(UUID latestCompactionEntryId) {
        this.latestCompactionEntryId = latestCompactionEntryId;
    }

    public Long getLatestCompactionSeq() {
        return latestCompactionSeq;
    }

    public void setLatestCompactionSeq(Long latestCompactionSeq) {
        this.latestCompactionSeq = latestCompactionSeq;
    }

    public Long getLatestCompactionByteOffset() {
        return latestCompactionByteOffset;
    }

    public void setLatestCompactionByteOffset(Long latestCompactionByteOffset) {
        this.latestCompactionByteOffset = latestCompactionByteOffset;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
