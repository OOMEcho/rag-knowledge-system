package com.ragknowledge.knowledge.service;

import com.ragknowledge.common.response.PageResponse;
import com.ragknowledge.knowledge.dto.CreateKnowledgeBaseRequest;
import com.ragknowledge.knowledge.dto.KnowledgeBaseResponse;
import com.ragknowledge.knowledge.dto.UpdateKnowledgeBaseRequest;

/**
 * 知识库服务接口：增删改查与名称唯一性约束
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public interface KnowledgeBaseService {

    /**
     * 创建新的知识库，并校验未删除知识库中名称不能重复。
     *
     * @param request 创建知识库请求，包含名称和描述
     * @return 创建后的知识库信息
     * @throws com.ragknowledge.common.exception.BusinessException 当知识库名称重复或请求数据不符合业务规则时抛出
     */
    KnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    /**
     * 分页查询未删除的知识库列表，可按名称关键字过滤。
     *
     * @param keyword  知识库名称关键字，可为空
     * @param pageNum  当前页码，从 1 开始
     * @param pageSize 每页大小
     * @return 知识库分页结果
     */
    PageResponse<KnowledgeBaseResponse> listKnowledgeBases(String keyword, int pageNum, int pageSize);

    /**
     * 根据 ID 查询未删除的知识库详情。
     *
     * @param id 知识库 ID
     * @return 知识库详情
     * @throws com.ragknowledge.common.exception.BusinessException 当知识库不存在或已删除时抛出
     */
    KnowledgeBaseResponse getKnowledgeBase(Long id);

    /**
     * 更新知识库基础信息，并校验新名称未被其他未删除知识库占用。
     *
     * @param id      知识库 ID
     * @param request 更新知识库请求，包含名称和描述
     * @return 更新后的知识库信息
     * @throws com.ragknowledge.common.exception.BusinessException 当知识库不存在、已删除或名称冲突时抛出
     */
    KnowledgeBaseResponse updateKnowledgeBase(Long id, UpdateKnowledgeBaseRequest request);

    /**
     * 逻辑删除知识库。为避免误删知识资产，存在未删除文档时禁止删除。
     *
     * @param id 知识库 ID
     * @throws com.ragknowledge.common.exception.BusinessException 当知识库不存在、已删除或仍有关联文档时抛出
     */
    void deleteKnowledgeBase(Long id);
}
