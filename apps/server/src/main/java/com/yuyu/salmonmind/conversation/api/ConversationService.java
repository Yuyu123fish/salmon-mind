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
     * 发送一条用户消息，以 SSE Run Stream 返回结果。User Entry 先落 JSONL，再以数据库
     * 事务创建 RUNNING Run 并推进活动叶子；durable 状态成立后回调
     * {@link RunStreamListener#onRunStarted}，随后按 Spec 顺序发送压缩、delta、完成、
     * 标题与终态事件。模型成功且文本非空时追加一个 Assistant Entry；该 Entry 的
     * completionStatus 可以是自然完成或长度中断的 INCOMPLETE_LENGTH，后者仍属于
     * SUCCEEDED Run，可由“继续生成”用例追加后续正文。
     *
     * <p>失败边界：onRunStarted 之前（Conversation 不存在、历史损坏、输入非法、忙碌）
     * 抛异常，由 HTTP 层映射为 JSON 错误；之后的一切失败（模型、Redis、压缩、持久化）
     * 通过 {@link RunStreamListener#onRunFailed} 收束，方法正常返回表示流已结束。
     *
     * @param text     去首尾空白后必须非空的用户消息正文
     * @param listener 本次 Run 的事件消费者；本方法同步阻塞直到 Run 完成
     */
    void send(UUID conversationId, String text, RunStreamListener listener);

    /**
     * 重试指定失败或中断的 Run：复用原用户 Entry，不追加重复用户消息，并为同一触发 Entry
     * 创建新的 Run 记录。若旧 Run 已追加 Compaction 但没有成功 Assistant，活动叶子可以
     * 是该 Compaction：重试直接复用压缩结果，不得因活动叶子不再是 User Entry 而拒绝。
     * 事件合同与 {@link #send} 一致，onRunStarted 标记 isRetry=true。
     *
     * @param runId    待重试的 Run ID；只有当前活动叶子仍等待回答的最新失败 Run 可重试
     * @param listener 本次 Run 的事件消费者；本方法同步阻塞直到 Run 完成
     */
    void retry(UUID conversationId, UUID runId, RunStreamListener listener);

    /**
     * 从当前 Active Path 叶子的未完成 Assistant 追加一次继续生成动作；动作与新 Run
     * 都是可恢复的 durable 状态，旧 User/Assistant Entry 不被修改或复制。
     * 只有目标恰为当前叶子且 completionStatus=INCOMPLETE_LENGTH 时允许；前置冲突抛出
     * CONTINUE_GENERATION_NOT_ALLOWED，Run 启动后的失败按普通 SSE run_failed 合同收束。
     *
     * @param assistantEntryId 当前 Active Path 叶子的 Assistant Entry ID
     * @param listener         本次新 Run 的 SSE 事件消费者
     */
    void continueGeneration(UUID conversationId, UUID assistantEntryId, RunStreamListener listener);
}
