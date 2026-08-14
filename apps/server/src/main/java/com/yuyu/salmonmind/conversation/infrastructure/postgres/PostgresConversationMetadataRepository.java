package com.yuyu.salmonmind.conversation.infrastructure.postgres;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.salmonmind.conversation.api.Conversation;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.application.port.ConversationMetadataRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation/Run 元数据 MyBatis Adapter：封装 Mapper、Entity 与 api 值之间的映射，
 * 只通过内部 port 被 application 使用。
 */
@Repository
class PostgresConversationMetadataRepository implements ConversationMetadataRepository {

    private final ConversationMapper conversationMapper;
    private final RunMapper runMapper;

    PostgresConversationMetadataRepository(ConversationMapper conversationMapper, RunMapper runMapper) {
        this.conversationMapper = conversationMapper;
        this.runMapper = runMapper;
    }

    @Override
    public void create(Conversation conversation) {
        conversationMapper.insert(toEntity(conversation));
    }

    @Override
    public void update(Conversation conversation) {
        conversationMapper.updateById(toEntity(conversation));
    }

    @Override
    public Conversation findById(UUID conversationId) {
        ConversationEntity entity = conversationMapper.selectById(conversationId);
        return entity == null ? null : toConversation(entity);
    }

    @Override
    public List<Conversation> listByWorkspace(UUID workspaceId) {
        return conversationMapper.selectList(
                        Wrappers.<ConversationEntity>lambdaQuery()
                                .eq(ConversationEntity::getWorkspaceId, workspaceId)
                                .orderByDesc(ConversationEntity::getUpdatedAt))
                .stream()
                .map(PostgresConversationMetadataRepository::toConversation)
                .toList();
    }

    @Override
    public Map<UUID, Run> latestRunsByConversation(Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        List<RunEntity> runs = runMapper.selectList(
                Wrappers.<RunEntity>lambdaQuery().in(RunEntity::getConversationId, conversationIds));
        Map<UUID, Run> latestByConversation = new HashMap<>();
        for (RunEntity run : runs) {
            Run existing = latestByConversation.get(run.getConversationId());
            if (existing == null || run.getStartedAt().isAfter(existing.startedAt())) {
                latestByConversation.put(run.getConversationId(), toRun(run));
            }
        }
        return latestByConversation;
    }

    @Override
    public Run findRunById(UUID runId) {
        RunEntity entity = runMapper.selectById(runId);
        return entity == null ? null : toRun(entity);
    }

    @Override
    public Run latestUnsuccessfulRun(UUID conversationId, UUID triggerEntryId) {
        List<RunEntity> runs = runMapper.selectList(
                Wrappers.<RunEntity>lambdaQuery()
                        .eq(RunEntity::getConversationId, conversationId)
                        .eq(RunEntity::getTriggerEntryId, triggerEntryId)
                        .orderByDesc(RunEntity::getStartedAt));
        for (RunEntity run : runs) {
            if (!Run.RunStatus.SUCCEEDED.name().equals(run.getStatus())) {
                return toRun(run);
            }
        }
        return null;
    }

    @Override
    public boolean existsRunByTrigger(UUID conversationId, UUID triggerEntryId) {
        return runMapper.selectCount(Wrappers.<RunEntity>lambdaQuery()
                .eq(RunEntity::getConversationId, conversationId)
                .eq(RunEntity::getTriggerEntryId, triggerEntryId)) > 0;
    }

    @Override
    public void insertRun(Run run) {
        runMapper.insert(toEntity(run));
    }

    @Override
    public void updateRun(Run run) {
        runMapper.updateById(toEntity(run));
    }

    private static ConversationEntity toEntity(Conversation conversation) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(conversation.id());
        entity.setWorkspaceId(conversation.workspaceId());
        entity.setTitle(conversation.title());
        entity.setHistoryFormatVersion(conversation.historyFormatVersion());
        entity.setActiveLeafEntryId(conversation.activeLeafEntryId());
        entity.setLastConfirmedSeq(conversation.lastConfirmedSeq());
        entity.setLatestCompactionEntryId(conversation.latestCompactionEntryId());
        entity.setLatestCompactionSeq(conversation.latestCompactionSeq());
        entity.setLatestCompactionByteOffset(conversation.latestCompactionByteOffset());
        entity.setCreatedAt(conversation.createdAt());
        entity.setUpdatedAt(conversation.updatedAt());
        return entity;
    }

    private static Conversation toConversation(ConversationEntity entity) {
        return new Conversation(
                entity.getId(),
                entity.getWorkspaceId(),
                entity.getTitle(),
                entity.getHistoryFormatVersion(),
                entity.getActiveLeafEntryId(),
                entity.getLastConfirmedSeq(),
                entity.getLatestCompactionEntryId(),
                entity.getLatestCompactionSeq(),
                entity.getLatestCompactionByteOffset(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static RunEntity toEntity(Run run) {
        RunEntity entity = new RunEntity();
        entity.setId(run.id());
        entity.setConversationId(run.conversationId());
        entity.setTriggerEntryId(run.triggerEntryId());
        entity.setStatus(run.status().name());
        entity.setErrorCode(run.errorCode());
        entity.setStartedAt(run.startedAt());
        entity.setEndedAt(run.endedAt());
        return entity;
    }

    private static Run toRun(RunEntity entity) {
        return new Run(
                entity.getId(),
                entity.getConversationId(),
                entity.getTriggerEntryId(),
                Run.RunStatus.valueOf(entity.getStatus()),
                entity.getErrorCode(),
                entity.getStartedAt(),
                entity.getEndedAt());
    }
}
