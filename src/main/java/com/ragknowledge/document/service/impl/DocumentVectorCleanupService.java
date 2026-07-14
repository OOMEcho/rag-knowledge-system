package com.ragknowledge.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragknowledge.document.entity.DocumentChunkEntity;
import com.ragknowledge.document.mapper.DocumentChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档分块与向量清理服务，保证业务记录和向量记录删除一致性
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Service
public class DocumentVectorCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorCleanupService.class);
    private static final boolean NOT_DELETED = false;
    private static final boolean DELETED = true;

    private final DocumentChunkMapper documentChunkMapper;
    private final VectorStore vectorStore;

    public DocumentVectorCleanupService(DocumentChunkMapper documentChunkMapper, VectorStore vectorStore) {
        this.documentChunkMapper = documentChunkMapper;
        this.vectorStore = vectorStore;
    }

    /**
     * 删除指定文档的活跃 chunk 和对应向量。向量删除失败会中断流程，避免元数据删除后向量残留。
     *
     * @param documentId 文档 ID
     */
    public void deleteActiveChunksAndVectors(Long documentId) {
        // 先读取活跃 chunk，后续需要用其中的 vectorDocumentId 精确删除 pgvector 记录。
        List<DocumentChunkEntity> activeChunks = listActiveChunks(documentId);
        // 从 chunk 元数据提取向量 ID，向量 ID 与业务 chunk ID 保持一致，便于精确清理。
        List<String> vectorDocumentIds = extractVectorDocumentIds(activeChunks);
        // 先删除向量再标记 chunk 删除，避免 chunk 不可见但向量仍参与检索。
        deleteVectors(vectorDocumentIds);
        // 向量删除成功后再逻辑删除 chunk，保留派生数据审计痕迹。
        markChunksDeleted(documentId);
    }

    /**
     * 尽力删除指定文档的活跃 chunk 和对应向量。该方法用于失败回滚，删除失败只记录日志。
     *
     * @param documentId 文档 ID
     */
    public void deleteActiveChunksAndVectorsQuietly(Long documentId) {
        try {
            // 失败回滚场景不允许二次清理异常覆盖原始解析或 embedding 失败原因。
            deleteActiveChunksAndVectors(documentId);
        } catch (RuntimeException exception) {
            // 保留日志便于后续清理孤儿 chunk 或向量，主流程继续把文档标记为 FAILED。
            log.warn("Failed to cleanup active chunks and vectors, documentId={}", documentId, exception);
            // 即使向量删除失败，也要隐藏业务 chunk，避免 FAILED 文档继续暴露不可用分块。
            markChunksDeletedQuietly(documentId);
        }
    }

    /**
     * 尽力删除指定向量文档。该方法用于失败回滚场景，删除失败只记录日志。
     *
     * @param vectorDocumentIds 向量文档 ID 列表
     */
    public void deleteVectorsQuietly(List<String> vectorDocumentIds) {
        try {
            // 尽力清理只用于失败回滚，不应因为二次清理失败覆盖原始失败原因。
            deleteVectors(vectorDocumentIds);
        } catch (RuntimeException exception) {
            // 保留日志便于后续排查孤儿向量，主流程继续按原始失败处理。
            log.warn("Failed to cleanup vector documents after ingestion failure, vectorDocumentIds={}", vectorDocumentIds, exception);
        }
    }

    /**
     * 查询指定文档的活跃 chunk。
     *
     * @param documentId 文档 ID
     * @return 活跃 chunk 列表
     */
    public List<DocumentChunkEntity> listActiveChunks(Long documentId) {
        // chunk 查询必须过滤 deleted，避免已被重新入库替换的历史 chunk 被再次删除或展示。
        return documentChunkMapper.selectList(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, documentId)
                .eq(DocumentChunkEntity::getDeleted, NOT_DELETED)
                .orderByAsc(DocumentChunkEntity::getChunkIndex));
    }

    /**
     * 提取向量文档 ID。
     *
     * @param chunks chunk 列表
     * @return 向量文档 ID 列表
     */
    private List<String> extractVectorDocumentIds(List<DocumentChunkEntity> chunks) {
        // 只保留非空向量 ID，防止历史异常数据导致 VectorStore 删除接口收到非法参数。
        return chunks.stream()
                .map(DocumentChunkEntity::getVectorDocumentId)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 删除向量库中的指定文档。
     *
     * @param vectorDocumentIds 向量文档 ID 列表
     */
    private void deleteVectors(List<String> vectorDocumentIds) {
        // 空列表说明该文档尚未向量化或派生数据已清理，无需访问向量库。
        if (vectorDocumentIds.isEmpty()) {
            return;
        }

        // 按向量文档 ID 精确删除，避免 metadata filter 表达式误删其他知识库数据。
        vectorStore.delete(vectorDocumentIds);
    }

    /**
     * 将指定文档的活跃 chunk 标记为删除。
     *
     * @param documentId 文档 ID
     */
    private void markChunksDeleted(Long documentId) {
        // 逻辑删除保留 chunk 审计痕迹，同时让唯一索引允许同一文档重新生成 chunk。
        LambdaUpdateWrapper<DocumentChunkEntity> updateWrapper = new LambdaUpdateWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, documentId)
                .eq(DocumentChunkEntity::getDeleted, NOT_DELETED)
                .set(DocumentChunkEntity::getDeleted, DELETED)
                .set(DocumentChunkEntity::getDeletedAt, LocalDateTime.now())
                .set(DocumentChunkEntity::getUpdatedAt, LocalDateTime.now());
        // 使用统一 update 语句处理所有活跃 chunk，避免逐条更新产生额外数据库开销。
        documentChunkMapper.update(null, updateWrapper);
    }

    /**
     * 尽力将指定文档的活跃 chunk 标记为删除。
     *
     * @param documentId 文档 ID
     */
    private void markChunksDeletedQuietly(Long documentId) {
        try {
            // 失败回滚兜底清理不应影响主流程，因此内部调用普通逻辑删除并吞掉异常。
            markChunksDeleted(documentId);
        } catch (RuntimeException exception) {
            // 记录日志便于人工或后续任务清理异常 chunk。
            log.warn("Failed to mark chunks deleted during quiet cleanup, documentId={}", documentId, exception);
        }
    }
}
