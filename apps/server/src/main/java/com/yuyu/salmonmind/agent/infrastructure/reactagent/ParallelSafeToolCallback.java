package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import org.springframework.ai.tool.ToolCallback;

/**
 * 声明工具在同一模型响应中的并行策略。
 *
 * <p>接口本身只代表“可以参与并行调度”，具体工具仍可通过
 * {@link #parallelAllowed()} 声明自己是批次屏障。未实现本接口的工具默认保持顺序执行，
 * 避免未来新增写操作时继承错误的并行策略。</p>
 */
interface ParallelSafeToolCallback extends ToolCallback {

    /**
     * 返回当前工具是否可以与同一响应中的相邻只读工具重叠执行。
     *
     * @return true 表示只读且无顺序依赖；false 表示该工具是批次屏障
     */
    default boolean parallelAllowed() {
        return true;
    }
}
