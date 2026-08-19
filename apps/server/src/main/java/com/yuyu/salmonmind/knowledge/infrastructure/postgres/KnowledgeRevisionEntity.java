package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yuyu.salmonmind.knowledge.domain.ParsedDocumentMetadata;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

/** 不可变原件版本及其解析/索引统计；重试不覆盖该记录。 */
@TableName(value = "knowledge_source_revisions", autoResultMap = true)
public class KnowledgeRevisionEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID sourceId;
    private Integer revisionNumber;
    private String name;
    private String format;
    private String mediaType;
    private String contentObjectKey;
    private String contentSha256;
    private Long sizeBytes;
    private String detectedMediaType;
    private Integer pageCount;
    private Integer textCharCount;
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = ParsedDocumentMetadataJsonTypeHandler.class)
    // 更新解析统计的局部实体必须保持 null，否则 MyBatis 会把空对象写回并覆盖 Worker 已保存的元信息。
    private ParsedDocumentMetadata parsedMetadata;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSourceId() { return sourceId; }
    public void setSourceId(UUID sourceId) { this.sourceId = sourceId; }
    public Integer getRevisionNumber() { return revisionNumber; }
    public void setRevisionNumber(Integer revisionNumber) { this.revisionNumber = revisionNumber; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getContentObjectKey() { return contentObjectKey; }
    public void setContentObjectKey(String contentObjectKey) { this.contentObjectKey = contentObjectKey; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getDetectedMediaType() { return detectedMediaType; }
    public void setDetectedMediaType(String detectedMediaType) { this.detectedMediaType = detectedMediaType; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public Integer getTextCharCount() { return textCharCount; }
    public void setTextCharCount(Integer textCharCount) { this.textCharCount = textCharCount; }
    public ParsedDocumentMetadata getParsedMetadata() { return parsedMetadata; }
    public void setParsedMetadata(ParsedDocumentMetadata parsedMetadata) {
        this.parsedMetadata = parsedMetadata == null ? ParsedDocumentMetadata.empty() : parsedMetadata;
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
