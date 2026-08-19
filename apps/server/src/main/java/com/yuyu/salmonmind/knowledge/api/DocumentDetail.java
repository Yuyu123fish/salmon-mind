package com.yuyu.salmonmind.knowledge.api;

import com.yuyu.salmonmind.knowledge.domain.ParsedDocumentMetadata;

import java.util.List;

/**
 * Knowledge 详情：当前状态、处理历史和已发布 Evidence 数量。
 * jobs 按 attempt_number 倒序，document 始终代表当前 Workspace 的最新 Revision/Job；
 * Evidence 正文只有在当前 Job 为 READY 时才可通过分页接口读取。
 */
public record DocumentDetail(
        DocumentSummary document,
        List<IngestionJobView> jobs,
        int pageCount,
        int textCharCount,
        ParsedDocumentMetadata metadata
) {

    public DocumentDetail(DocumentSummary document, List<IngestionJobView> jobs,
                          int pageCount, int textCharCount) {
        this(document, jobs, pageCount, textCharCount, ParsedDocumentMetadata.empty());
    }

    public DocumentDetail {
        jobs = List.copyOf(jobs);
        metadata = metadata == null ? ParsedDocumentMetadata.empty() : metadata;
    }
}
