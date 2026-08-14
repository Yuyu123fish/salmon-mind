package com.yuyu.salmonmind.conversation.application.port;

import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.Run;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation/Run 元数据（PostgreSQL）的内部 seam。实现位于 infrastructure.postgres，
 * 不接受或返回 MyBatis Entity；消息正文不进入本 seam。
 */
public interface ConversationMetadataRepository {

    /** 插入新 Conversation 元数据行。 */
    void create(Conversation conversation);

    /** 修复或推进 Conversation 元数据（活动叶子、确认序号、标题、压缩索引）。 */
    void update(Conversation conversation);

    /** 按 ID 查询 Conversation；不存在时返回 null。 */
    Conversation findById(UUID conversationId);

    /** 按 Workspace 查询，最近更新在前。 */
    List<Conversation> listByWorkspace(UUID workspaceId);

    /** 每个 Conversation 的最新 Run；无 Run 的 Conversation 不出现在结果中。 */
    Map<UUID, Run> latestRunsByConversation(Collection<UUID> conversationIds);

    /** 按 ID 查询 Run；不存在时返回 null。 */
    Run findRunById(UUID runId);

    /** 指定触发 Entry 的最新未成功 Run；没有则为 null。 */
    Run latestUnsuccessfulRun(UUID conversationId, UUID triggerEntryId);

    /** 指定触发 Entry 是否已存在 Run（含重试创建的多个 Run）。 */
    boolean existsRunByTrigger(UUID conversationId, UUID triggerEntryId);

    /** 插入 Run 行。 */
    void insertRun(Run run);

    /** 更新 Run 状态与结束时间。 */
    void updateRun(Run run);

    /** 把指定 Conversation 遗留的 RUNNING Run 恢复为 INTERRUPTED（进程中断后的可重试状态）。 */
    void interruptRunningRuns(UUID conversationId);

    /** 把全部遗留 RUNNING Run 恢复为 INTERRUPTED；返回受影响行数，用于启动恢复。 */
    int interruptAllRunningRuns();
}
