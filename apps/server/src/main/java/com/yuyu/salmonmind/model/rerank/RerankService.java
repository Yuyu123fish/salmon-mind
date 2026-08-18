package com.yuyu.salmonmind.model.rerank;

import java.util.List;

/**
 * 文档精排的稳定模型边界。Knowledge 只提交查询和有序候选正文，
 * 不接触 SiliconFlow HTTP、鉴权或提供方响应结构。
 */
public interface RerankService {

    String MODEL = "Qwen/Qwen3-Reranker-4B";
    String INSTRUCTION = "rerank-v1";

    /**
     * 按候选输入顺序提交精排请求；返回值中的 index 指向本次输入列表，不能指向外部身份。
     *
     * @param query 已规范化且非空的查询
     * @param documents 按 RRF 顺序排列的候选正文
     * @param topN 最多返回的候选数
     * @return 提供方排序后的输入 index、诊断分数和模型身份
     */
    RerankResult rerank(String query, List<String> documents, int topN);
}
