package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/** 一套可重建索引的模型、切片和 mapping 版本及其计数。 */
@TableName(value = "knowledge_index_generations", autoResultMap = true)
public class KnowledgeGenerationEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private String physicalIndex;
    private String status;
    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDimensions;
    private String chunkVersion;
    private String mappingVersion;
    private Integer revisionCount;
    private Integer evidenceCount;
    private Instant createdAt;
    private Instant activatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPhysicalIndex() { return physicalIndex; }
    public void setPhysicalIndex(String physicalIndex) { this.physicalIndex = physicalIndex; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Integer getEmbeddingDimensions() { return embeddingDimensions; }
    public void setEmbeddingDimensions(Integer embeddingDimensions) { this.embeddingDimensions = embeddingDimensions; }
    public String getChunkVersion() { return chunkVersion; }
    public void setChunkVersion(String chunkVersion) { this.chunkVersion = chunkVersion; }
    public String getMappingVersion() { return mappingVersion; }
    public void setMappingVersion(String mappingVersion) { this.mappingVersion = mappingVersion; }
    public Integer getRevisionCount() { return revisionCount; }
    public void setRevisionCount(Integer revisionCount) { this.revisionCount = revisionCount; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer evidenceCount) { this.evidenceCount = evidenceCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
}
