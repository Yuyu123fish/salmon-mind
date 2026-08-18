package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.port.ObjectStoragePort;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 单文档跨存储删除编排器。PostgreSQL 的 DELETING 提交是可见性切断点；
 * 之后任何失败都保留该状态，下一次调用复用同一 PostgreSQL Target 并安全重试。
 */
@Service
class KnowledgeDeletionCoordinator implements KnowledgeDeletion {

    private final KnowledgeMetadataPort metadata;
    private final EvidenceIndexPort evidenceIndex;
    private final ObjectStoragePort objectStorage;

    KnowledgeDeletionCoordinator(
            KnowledgeMetadataPort metadata,
            EvidenceIndexPort evidenceIndex,
            ObjectStoragePort objectStorage
    ) {
        this.metadata = metadata;
        this.evidenceIndex = evidenceIndex;
        this.objectStorage = objectStorage;
    }

    @Override
    public void delete(UUID workspaceId, UUID documentId) {
        KnowledgeMetadataPort.DeletionTarget target = metadata.markDeleting(workspaceId, documentId);
        try {
            for (KnowledgeMetadataPort.GenerationTarget generation : target.generations()) {
                evidenceIndex.deleteForRevisions(generation.physicalIndex(), target.revisionIds());
            }
            for (KnowledgeMetadataPort.RevisionTarget revision : target.revisions()) {
                objectStorage.deleteStrict(revision.objectKey());
            }
            metadata.finalizeDeletion(target);
        } catch (KnowledgeException ex) {
            if (ex.code() == KnowledgeException.Code.DOCUMENT_DELETE_INCOMPLETE) {
                throw ex;
            }
            throw incomplete(ex);
        } catch (RuntimeException ex) {
            throw incomplete(ex);
        }
    }

    private static KnowledgeException incomplete(Throwable cause) {
        return new KnowledgeException(
                KnowledgeException.Code.DOCUMENT_DELETE_INCOMPLETE,
                "文档删除未完成，请重试",
                cause);
    }
}
