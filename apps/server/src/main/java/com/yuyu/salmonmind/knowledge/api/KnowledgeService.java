package com.yuyu.salmonmind.knowledge.api;

import java.util.List;
import java.util.UUID;

/**
 * Knowledge Stage 02 的唯一跨模块业务入口：上传、状态、详情、Evidence 预览和重试。
 * 查询召回、RRF、Rerank 与 Agent Tool 不属于本接口。
 */
public interface KnowledgeService {

    /**
     * 接收单个白名单文档并快速返回；返回时只保证原件与提交 Job 已落库，
     * READY 需要后台 Worker 后续完成解析、Embedding 和索引。
     */
    DocumentSummary upload(DocumentUpload upload);

    /** 返回当前 Workspace 的文档，按最近更新时间倒序。 */
    List<DocumentSummary> list();

    /** 返回单个文档的当前 Job 和历史 Job。 */
    DocumentDetail detail(UUID documentId);

    /** 只允许 READY 文档读取已发布的 Evidence 正文。 */
    EvidencePage evidence(UUID documentId, int page, int size);

    /** 为可重试失败复用原 Revision/原件，创建下一次处理 Job。 */
    DocumentSummary retry(UUID documentId);
}
