package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.UUID;

/** PostgreSQL 中已发布 Evidence 的可追溯元数据；正文和向量留在 Elasticsearch。 */
@TableName(value = "knowledge_evidence", autoResultMap = true)
public class KnowledgeEvidenceEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID generationId;
    private UUID sourceRevisionId;
    private Integer ordinal;
    private String location;
    private String contentSha256;
    private String sourceRevisionSha256;
    private Integer charCount;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getGenerationId() { return generationId; }
    public void setGenerationId(UUID generationId) { this.generationId = generationId; }
    public UUID getSourceRevisionId() { return sourceRevisionId; }
    public void setSourceRevisionId(UUID sourceRevisionId) { this.sourceRevisionId = sourceRevisionId; }
    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getSourceRevisionSha256() { return sourceRevisionSha256; }
    public void setSourceRevisionSha256(String sourceRevisionSha256) { this.sourceRevisionSha256 = sourceRevisionSha256; }
    public Integer getCharCount() { return charCount; }
    public void setCharCount(Integer charCount) { this.charCount = charCount; }
}
