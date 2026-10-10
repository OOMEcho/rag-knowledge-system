package com.ragknowledge.document.service;

import com.ragknowledge.common.response.PageResponse;
import com.ragknowledge.document.dto.KnowledgeDocumentResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档服务接口：上传、查询与删除
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public interface KnowledgeDocumentService {

    /**
     * 上传文档原始文件，保存本地文件并持久化文档元数据。
     *
     * @param knowledgeBaseId 所属知识库 ID
     * @param file            上传文件
     * @return 上传后的文档元数据
     * @throws com.ragknowledge.common.exception.BusinessException 当知识库不存在、文件为空、文件重复或文件保存失败时抛出
     */
    KnowledgeDocumentResponse uploadDocument(Long knowledgeBaseId, MultipartFile file);

    /**
     * 分页查询未删除的文档元数据，可按知识库和状态过滤。
     *
     * @param knowledgeBaseId 知识库 ID，可为空
     * @param status          文档状态，可为空
     * @param pageNum         当前页码，从 1 开始
     * @param pageSize        每页大小
     * @return 文档元数据分页结果
     */
    PageResponse<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId, String status, int pageNum, int pageSize);

    /**
     * 根据 ID 查询未删除的文档元数据。
     *
     * @param id 文档 ID
     * @return 文档元数据
     * @throws com.ragknowledge.common.exception.BusinessException 当文档不存在或已删除时抛出
     */
    KnowledgeDocumentResponse getDocument(Long id);

    /**
     * 逻辑删除文档及派生数据，提交后尽力清理本地原始文件；事务回滚时保留原文件。
     *
     * @param id 文档 ID
     * @throws com.ragknowledge.common.exception.BusinessException 当文档不存在、已删除或正在入库时抛出
     */
    void deleteDocument(Long id);
}
