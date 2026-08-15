package com.yuyu.salmonmind.agent.api;

/**
 * 流式主回答的公开能力。conversation 模块只通过本接口获得有序 delta、最终完整结果与
 * 明确失败，不接触 ReactAgent、Flux、ChatResponse 或 RedisSaver。
 *
 * <p>调用语义：{@code stream} 同步阻塞直到本次调用结束（成功或失败），期间按序回调
 * {@link AgentStreamListener#onDelta}；结束时恰好调用一次 onComplete 或 onError。
 * Checkpoint 语义与同步 complete 一致：期望叶子匹配则复用，否则释放并用完整投影重建。
 */
public interface AgentStreamSession {

    void stream(AgentRequest request, AgentStreamListener listener);
}
