package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/** 一次 Source Revision 处理尝试；Job 状态是 PostgreSQL 的业务权威。 */
@TableName(value = "knowledge_ingestion_jobs", autoResultMap = true)
public class KnowledgeIngestionJobEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID sourceRevisionId;
    private Integer attemptNumber;
    private String state;
    private Boolean retryable;
    private String errorCode;
    private String errorMessage;
    private String streamMessageId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant startedAt;
    private Instant endedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSourceRevisionId() { return sourceRevisionId; }
    public void setSourceRevisionId(UUID sourceRevisionId) { this.sourceRevisionId = sourceRevisionId; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Boolean getRetryable() { return retryable; }
    public void setRetryable(Boolean retryable) { this.retryable = retryable; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getStreamMessageId() { return streamMessageId; }
    public void setStreamMessageId(String streamMessageId) { this.streamMessageId = streamMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
