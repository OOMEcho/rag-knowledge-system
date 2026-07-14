package com.ragknowledge.document.service;

import com.ragknowledge.common.response.PageResponse;
import com.ragknowledge.document.dto.DocumentChunkResponse;
import com.ragknowledge.document.dto.KnowledgeDocumentResponse;

/**
 * 文档入库服务接口：触发异步入库、失败重试与分块查询
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public interface DocumentIngestionService {

    /**
     * 触发文档异步入库。方法原子抢占 PARSING 状态并把解析、切分、chunk 持久化
     * 和向量写入提交到独立线程池后立即返回，调用方需轮询文档详情获取终态。
     *
     * @param documentId 文档 ID
     * @return 抢占成功后的文档元数据，状态为 PARSING；终态 COMPLETED/FAILED 通过文档详情查询
     * @throws com.ragknowledge.common.exception.BusinessException 当文档不存在、已删除、正在入库处理中或任务队列已满时抛出
     */
    KnowledgeDocumentResponse ingestDocument(Long documentId);

    /**
     * 重试入库失败的文档。仅允许状态为 FAILED 的文档进入该恢复入口，
     * 校验通过后复用异步入库流程，返回语义与 {@link #ingestDocument(Long)} 一致。
     *
     * @param documentId 文档 ID
     * @return 抢占成功后的文档元数据，状态为 PARSING；终态通过文档详情查询
     * @throws com.ragknowledge.common.exception.BusinessException 当文档不存在、已删除或当前状态不是 FAILED 时抛出
     */
    KnowledgeDocumentResponse retryFailedDocument(Long documentId);

    /**
     * 分页查询指定文档的活跃 chunk。
     *
     * @param documentId 文档 ID
     * @param pageNum    当前页码，从 1 开始
     * @param pageSize   每页大小
     * @return chunk 分页结果
     * @throws com.ragknowledge.common.exception.BusinessException 当文档不存在或已删除时抛出
     */
    PageResponse<DocumentChunkResponse> listDocumentChunks(Long documentId, int pageNum, int pageSize);
}
