package com.yuyu.salmonmind.knowledge.api;

/** Knowledge 页面使用的检索诊断入口；Agent 只依赖 knowledge::retrieval。 */
public interface KnowledgeSearchService {

    /** 执行一次有界本地混合检索并返回各阶段的诊断结果。 */
    KnowledgeSearchResult search(String query);
}
