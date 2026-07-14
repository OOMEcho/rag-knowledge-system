package com.ragknowledge.infrastructure.storage;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;

/**
 * 文档存储服务接口，抽象本地磁盘与未来对象存储实现
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public interface DocumentStorageService {

    /**
     * 保存上传文件，并在保存过程中计算 SHA-256 校验和。
     *
     * @param knowledgeBaseId 知识库 ID，用于构造文件存储分区
     * @param documentId      文档 ID，用于构造稳定存储路径
     * @param file            用户上传的文件
     * @return 已保存文件的存储信息
     * @throws com.ragknowledge.common.exception.BusinessException 当文件无法保存或存储路径非法时抛出
     */
    StoredDocumentFile storeUploadedFile(Long knowledgeBaseId, Long documentId, MultipartFile file);

    /**
     * 根据存储键加载已保存的原始文件资源。
     *
     * @param storageKey 文件存储键
     * @return 可供解析器读取的 Spring Resource
     * @throws com.ragknowledge.common.exception.BusinessException 当存储键非法或文件不存在时抛出
     */
    Resource loadStoredFile(String storageKey);

    /**
     * 删除已保存文件。删除失败只记录日志，不中断主业务流程。
     *
     * @param storageKey 文件存储键
     */
    void deleteStoredFile(String storageKey);
}
