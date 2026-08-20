package com.yuyu.salmonmind.codebase.api;

/** 一个简单的有向调用边；不携带类型、置信度或控制流语义。 */
public record CallChainEdgeInput(String from, String to) {
}
