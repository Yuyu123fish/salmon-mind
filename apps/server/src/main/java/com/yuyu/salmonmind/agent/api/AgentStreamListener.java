package com.yuyu.salmonmind.agent.api;

/**
 * 流式主回答的稳定监听合同：Displayable Reasoning 与回答 delta 分开按序到达，
 * 最终以 onComplete 或 onError 恰好一次结束。两类 delta 都只用于临时显示；
 * 最终回答与持久化 Trace 以 onComplete 携带的结果为准。
 *
 * <p>工具生命周期事件（started/completed/failed）是平台内部模块合同：
 * 一个 Tool Call ID 的 started 后至多一次 completed 或 failed；工具失败不必然终止运行。
 * 所有事件实现方均可安全忽略（默认空实现），忽略后行为与 Feature 002 完全一致。
 */
public interface AgentStreamListener {

    /** 收到模型公开提供的一段可展示 reasoning；不得与最终回答文本混合。 */
    default void onReasoningDelta(String delta) {
    }

    /** 收到一段有序增量文本；多次调用按到达顺序拼接即为完整回答。 */
    void onDelta(String delta);

    /** 主调用成功结束：完整文本、provider/model 与可取得的 usage；与 onError 互斥。 */
    void onComplete(AgentResult result);

    /** 主调用失败：错误码表达明确失败语义（含上下文溢出）；与 onComplete 互斥。 */
    void onError(AgentExecutionException error);

    /** 工具调用开始执行。 */
    default void onToolStarted(AgentToolStarted event) {
    }

    /** 工具调用成功结束；与同 ID 的 onToolFailed 互斥。 */
    default void onToolCompleted(AgentToolCompleted event) {
    }

    /** 工具调用失败结束；与同 ID 的 onToolCompleted 互斥。 */
    default void onToolFailed(AgentToolFailed event) {
    }
}
