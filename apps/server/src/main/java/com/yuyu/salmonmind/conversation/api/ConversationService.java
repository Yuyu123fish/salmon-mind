package com.yuyu.salmonmind.conversation.api;

import java.util.List;
import java.util.UUID;

/**
 * Conversation 用例入口。Workspace 由 application 通过 workspace::api 自行取得，
 * 调用方不传单例 Workspace ID。同一 Conversation 的打开、发送与重试由进程内队列串行执行。
 */
public interface ConversationService {

    /**
     * 在唯一 Workspace 下创建一个新 Conversation（临时标题，无消息）。
     *
     * @return 新 Conversation 的列表项，latestRun 恒为 null
     */
    ConversationSummary create();

    /** @return 当前 Workspace 下所有 Conversation，按最近更新倒序，只读 PostgreSQL 元数据 */
    List<ConversationSummary> list();

    /**
     * 打开 Conversation 并返回按 Active Path 排列的可见消息。
     * 打开时以 JSONL 为权威修复数据库索引。
     *
     * @param conversationId 目标 Conversation ID
     * @throws ConversationException CONVERSATION_NOT_FOUND（不存在或不属于当前 Workspace）、
     *                               CONVERSATION_HISTORY_CORRUPTED（历史损坏）
     */
    ConversationDetail open(UUID conversationId);

    /**
     * 发送一条用户消息并等待完整回答。用户 Entry 先落 JSONL，再以数据库事务创建 RUNNING Run
     * 并推进活动叶子与标题，之后调用 Agent；成功后再追加 Assistant Entry 并完成 Run。
     * Agent 失败时不追加 Assistant Entry，只把 Run 置为 FAILED 后抛出稳定异常。
     *
     * @param text 去首尾空白后必须非空的用户消息正文
     * @throws ConversationException CONVERSATION_NOT_FOUND（不存在或不属于当前 Workspace）、
     *                               CONVERSATION_AWAITING_RETRY（活动叶子仍是待重试的用户消息）、
     *                               CONTEXT_LIMIT_REACHED（模型上下文超过硬限制）、
     *                               CONVERSATION_HISTORY_CORRUPTED（历史损坏）
     * @throws com.yuyu.salmonmind.agent.api.AgentExecutionException
     *         模型未配置、模型调用失败或 Redis 不可用；Run 已标记 FAILED，可重试
     */
    ConversationRunResult send(UUID conversationId, String text);

    /**
     * 重试指定失败或中断的 Run：复用原用户 Entry，不追加重复用户消息，并为同一触发 Entry
     * 创建新的 Run 记录。重试成功后返回新的终态结果。
     *
     * @param runId 待重试的 Run ID；只有当前活动叶子（待回答用户 Entry）的最新失败 Run 可重试
     * @throws ConversationException CONVERSATION_NOT_FOUND（Conversation 或 Run 不存在、Run 不属于该 Conversation）、
     *                               CONVERSATION_BUSY（Run 仍处于 RUNNING）、
     *                               CONVERSATION_AWAITING_RETRY（Run 已成功或该消息已得到回答）、
     *                               CONTEXT_LIMIT_REACHED、CONVERSATION_HISTORY_CORRUPTED
     * @throws com.yuyu.salmonmind.agent.api.AgentExecutionException
     *         模型未配置、模型调用失败或 Redis 不可用；新 Run 已标记 FAILED，可再次重试
     */
    ConversationRunResult retry(UUID conversationId, UUID runId);
}
