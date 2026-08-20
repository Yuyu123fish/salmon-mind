package com.yuyu.salmonmind.codebase.api;

/**
 * Agent 提交的一个调用链节点身份与位置。
 *
 * <p>故意没有 source 字段。{@code sourceHash} 由 Agent 从本 Run 最终交给模型的
 * ReadFile 证据计算后提交，Server 仍会在 prepare 阶段重新读取并校验。</p>
 */
public record CallChainNodeInput(
        String key,
        String language,
        String qualifiedSymbol,
        String signature,
        String path,
        int startLine,
        int endLine,
        String summary,
        String sourceHash
) {
}
