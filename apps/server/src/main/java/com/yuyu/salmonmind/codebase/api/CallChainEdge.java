package com.yuyu.salmonmind.codebase.api;

/** 详情中的结构化有向边，UI 必须同时保留这份列表以正确表达循环与汇合。 */
public record CallChainEdge(String fromNodeId, String toNodeId) {
}
