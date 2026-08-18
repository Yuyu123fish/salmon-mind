package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import org.springframework.ai.tool.ToolCallback;

/**
 * 明确声明无副作用、可在同一模型响应中并行执行的工具。
 * 未实现本接口的工具默认保持顺序执行，避免未来新增写操作时继承错误的并行策略。
 */
interface ParallelSafeToolCallback extends ToolCallback {
}
