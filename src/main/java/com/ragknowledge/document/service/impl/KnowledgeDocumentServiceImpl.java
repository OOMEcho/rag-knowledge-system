package com.ragknowledge.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.common.response.ErrorCode;
import com.ragknowledge.common.response.PageResponse;
import com.ragknowledge.config.ingestion.IngestionProperties;
import com.ragknowledge.document.dto.KnowledgeDocumentResponse;
import com.ragknowledge.document.entity.DocumentStatus;
import com.ragknowledge.document.entity.KnowledgeDocumentEntity;
import com.ragknowledge.document.mapper.KnowledgeDocumentMapper;
import com.ragknowledge.document.service.KnowledgeDocumentService;
import com.ragknowledge.infrastructure.storage.DocumentStorageService;
import com.ragknowledge.infrastructure.storage.StoredDocumentFile;
import com.ragknowledge.knowledge.entity.KnowledgeBaseEntity;
import com.ragknowledge.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 知识文档服务实现：上传去重、类型白名单校验、查询与删除保护
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final boolean NOT_DELETED = false;
    private static final boolean DELETED = true;
    private static final int MIN_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> PROCESSING_STATUSES = Set.of(DocumentStatus.PARSING.name(), DocumentStatus.EMBEDDING.name());

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentStorageService documentStorageService;
    private final DocumentVectorCleanupService documentVectorCleanupService;
    private final IngestionProperties ingestionProperties;

    public KnowledgeDocumentServiceImpl(KnowledgeDocumentMapper knowledgeDocumentMapper,
                                        KnowledgeBaseMapper knowledgeBaseMapper,
                                        DocumentStorageService documentStorageService,
                                        DocumentVectorCleanupService documentVectorCleanupService,
                                        IngestionProperties ingestionProperties) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentStorageService = documentStorageService;
        this.documentVectorCleanupService = documentVectorCleanupService;
        this.ingestionProperties = ingestionProperties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentResponse uploadDocument(Long knowledgeBaseId, MultipartFile file) {
        // 上传前先验证文件有效性，避免空文件进入存储和后续解析流程。
        validateUploadedFile(file);
        // 文档必须归属于有效知识库，跨模块读取知识库状态是为了保证文档业务边界存在。
        ensureKnowledgeBaseExists(knowledgeBaseId);

        // 预生成文档 ID 用于构造稳定存储路径，避免数据库插入后再迁移文件位置。
        Long documentId = IdWorker.getId();
        // 文件保存同时计算 checksum，后续基于 checksum 做同知识库内去重。
        StoredDocumentFile storedFile = documentStorageService.storeUploadedFile(knowledgeBaseId, documentId, file);

        try {
            // 文件准备完成后锁定知识库直到元数据提交；与知识库删除互斥，减少持锁期间的文件 I/O。
            if (knowledgeBaseMapper.selectActiveForUpdate(knowledgeBaseId) == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在", HttpStatus.NOT_FOUND);
            }
            // 保存文件后立即检查重复，若重复则删除刚保存的文件并返回冲突错误。
            ensureNoDuplicateDocument(knowledgeBaseId, storedFile.checksumSha256());
            // 构造文档元数据实体，当前阶段状态停留在 UPLOADED，解析和向量化在 v0.3 处理。
            KnowledgeDocumentEntity entity = buildNewDocumentEntity(documentId, knowledgeBaseId, file, storedFile);
            // 插入文档元数据，数据库唯一索引会兜底处理并发上传相同文件的场景。
            knowledgeDocumentMapper.insert(entity);
            // 文档数量需要与知识库列表展示保持一致，因此上传成功后同步递增计数。
            incrementKnowledgeBaseDocumentCount(knowledgeBaseId);
            // 返回文档元数据响应，调用方可据此跟踪后续解析和向量化状态。
            return toResponse(entity);
        } catch (DuplicateKeyException exception) {
            // 并发上传相同文件时数据库唯一索引可能先发现冲突，需要清理已保存文件并返回业务冲突。
            documentStorageService.deleteStoredFile(storedFile.storageKey());
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "知识库中已存在相同内容的文档", HttpStatus.CONFLICT);
        } catch (RuntimeException exception) {
            // 数据库写入或业务校验失败时主动删除已保存文件，降低文件系统和数据库不一致风险。
            documentStorageService.deleteStoredFile(storedFile.storageKey());
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId, String status, int pageNum, int pageSize) {
        // 分页参数在服务层统一规范化，避免不同入口对大分页请求处理不一致。
        int safePageNum = normalizePageNum(pageNum);
        // 分页大小限制保护数据库，避免一次请求扫描或返回过多文档元数据。
        int safePageSize = normalizePageSize(pageSize);
        // 状态参数先规范化为枚举名称，避免数据库查询条件出现无效状态字符串。
        String normalizedStatus = normalizeOptionalStatus(status);

        // MyBatis-Plus Page 承载数据库分页参数和查询结果，避免应用层手工分页。
        Page<KnowledgeDocumentEntity> page = new Page<>(safePageNum, safePageSize);
        // 查询条件集中构造，确保列表接口始终过滤已删除文档。
        LambdaQueryWrapper<KnowledgeDocumentEntity> queryWrapper = buildListQueryWrapper(knowledgeBaseId, normalizedStatus);
        // 执行数据库分页查询，只读取当前页数据。
        Page<KnowledgeDocumentEntity> resultPage = knowledgeDocumentMapper.selectPage(page, queryWrapper);
        // 将实体转换为 API DTO，避免 storageKey、deleted 等内部字段外泄。
        List<KnowledgeDocumentResponse> records = resultPage.getRecords().stream().map(this::toResponse).toList();

        // 统一分页响应结构，便于调用方处理知识库和文档列表。
        return PageResponse.of(records, resultPage.getTotal(), resultPage.getCurrent(), resultPage.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeDocumentResponse getDocument(Long id) {
        // 查询详情必须过滤 deleted，避免已删除文档仍被引用或展示。
        KnowledgeDocumentEntity entity = findRequiredActiveDocument(id);
        // 响应 DTO 只返回业务可见字段，隐藏本地存储实现细节。
        return toResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long id) {
        // 持有文档行锁直到提交；并发删除和入库条件更新必须等待，不能在状态检查后插入新任务。
        KnowledgeDocumentEntity entity = knowledgeDocumentMapper.selectActiveForUpdate(id);
        // 等待其他删除提交后，活跃行已不存在，不能再次扣减计数或清理数据。
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在", HttpStatus.NOT_FOUND);
        }

        // 入库任务运行中禁止删除，否则删除清理和异步管道写入会交错执行，产生孤儿 chunk 或向量。
        if (PROCESSING_STATUSES.contains(entity.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "文档正在入库处理中，无法删除，请稍后再试", HttpStatus.CONFLICT);
        }

        // 删除文档前先清理 chunk 和向量，避免文档不可见后向量仍参与检索。
        documentVectorCleanupService.deleteActiveChunksAndVectors(entity.getId());
        // chunk 和向量清理成功后再逻辑删除文档元数据，保证删除流程的一致性。
        markDocumentDeleted(entity);
        // 文档删除成功后递减知识库文档计数，保证知识库列表展示准确。
        decrementKnowledgeBaseDocumentCount(entity.getKnowledgeBaseId());
        // 文件系统无法参加数据库回滚，只能在事务确认提交后删除原文件。
        deleteStoredFileAfterCommit(entity.getStorageKey());
    }

    /**
     * 在删除事务提交后清理原文件，回滚时保留文件供后续重试。
     *
     * @param storageKey 原文件存储键
     */
    private void deleteStoredFileAfterCommit(String storageKey) {
        // 注册提交回调；文件删除失败由存储层记录并由孤儿文件任务补偿。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 此时数据库删除已提交，文件清理不再破坏回滚后的文档可读性。
                documentStorageService.deleteStoredFile(storageKey);
            }
        });
    }

    /**
     * 校验上传文件是否可进入文档元数据流程。
     *
     * @param file 上传文件
     */
    private void validateUploadedFile(MultipartFile file) {
        // 文件对象为空表示请求没有正确提交 multipart 字段，应按参数错误处理。
        if (file == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上传文件不能为空", HttpStatus.BAD_REQUEST);
        }

        // 空文件没有解析和向量化价值，提前拒绝可以避免产生无效知识资产。
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上传文件不能为空", HttpStatus.BAD_REQUEST);
        }

        // 扩展名白名单在入口拦截不支持的文件类型，避免恶意或无法解析的文件进入存储和 Tika 解析流程。
        ensureAllowedFileExtension(file.getOriginalFilename());
    }

    /**
     * 校验上传文件扩展名在白名单内。
     *
     * @param originalFileName 客户端原始文件名
     */
    private void ensureAllowedFileExtension(String originalFileName) {
        // 白名单校验依赖扩展名，文件名缺失时无法判断类型，按不支持处理。
        if (!StringUtils.hasText(originalFileName)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "无法识别上传文件类型", HttpStatus.BAD_REQUEST);
        }

        // 取最后一个 . 之后的部分作为扩展名，兼容 report.v2.pdf 这类多段文件名。
        int dotIndex = originalFileName.lastIndexOf('.');

        // 没有扩展名或以 . 结尾的文件名无法确定类型，直接拒绝。
        if (dotIndex < 0 || dotIndex == originalFileName.length() - 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "无法识别上传文件类型", HttpStatus.BAD_REQUEST);
        }

        // 扩展名统一转小写比较，白名单配置按小写维护。
        String extension = originalFileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);

        // 不在白名单的类型拒绝上传，白名单可通过 rag.ingestion.allowed-file-extensions 配置调整。
        if (!ingestionProperties.getAllowedFileExtensions().contains(extension)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "不支持的文件类型: " + extension + "，允许的类型: " + String.join(", ", ingestionProperties.getAllowedFileExtensions()),
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 校验知识库是否存在且未删除。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void ensureKnowledgeBaseExists(Long knowledgeBaseId) {
        // 文档模块跨模块读取知识库表，是为了确保文档归属的业务边界真实存在。
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getId, knowledgeBaseId)
                .eq(KnowledgeBaseEntity::getDeleted, NOT_DELETED));

        // 知识库不存在时文档不能被上传，否则后续检索无法按 knowledgeBaseId 做隔离。
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 校验同一知识库中是否已存在相同 checksum 的未删除文档。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param checksumSha256  文件 SHA-256 校验和
     */
    private void ensureNoDuplicateDocument(Long knowledgeBaseId, String checksumSha256) {
        // 去重范围限定在同一知识库，允许不同知识库上传相同原始文档以保持业务边界独立。
        Long duplicateCount = knowledgeDocumentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocumentEntity::getChecksumSha256, checksumSha256)
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED));

        // 已存在相同 checksum 时拒绝上传，避免重复解析、重复向量化和重复引用来源。
        if (duplicateCount > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "知识库中已存在相同内容的文档", HttpStatus.CONFLICT);
        }
    }

    /**
     * 构造新的文档元数据实体。
     *
     * @param documentId      文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param file            上传文件
     * @param storedFile      已保存文件信息
     * @return 待持久化的文档实体
     */
    private KnowledgeDocumentEntity buildNewDocumentEntity(Long documentId,
                                                           Long knowledgeBaseId,
                                                           MultipartFile file,
                                                           StoredDocumentFile storedFile) {
        // 创建和更新时间统一取同一个值，避免同一条新记录出现无意义的时间差。
        LocalDateTime now = LocalDateTime.now();
        // 文档元数据保存文件定位、checksum 和状态，后续解析任务只依赖这些稳定字段。
        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setId(documentId);
        entity.setKnowledgeBaseId(knowledgeBaseId);
        entity.setFileName(storedFile.fileName());
        entity.setOriginalFileName(file.getOriginalFilename());
        entity.setContentType(file.getContentType());
        entity.setFileSize(storedFile.fileSize());
        entity.setChecksumSha256(storedFile.checksumSha256());
        entity.setStorageKey(storedFile.storageKey());
        entity.setStatus(DocumentStatus.UPLOADED.name());
        entity.setChunkCount(0);
        entity.setDeleted(NOT_DELETED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    /**
     * 构造文档列表查询条件。
     *
     * @param knowledgeBaseId  知识库 ID
     * @param normalizedStatus 已规范化状态
     * @return MyBatis-Plus 查询条件
     */
    private LambdaQueryWrapper<KnowledgeDocumentEntity> buildListQueryWrapper(Long knowledgeBaseId, String normalizedStatus) {
        // 文档列表的基础口径是只查询未删除文档，避免删除记录继续参与后续人工操作。
        LambdaQueryWrapper<KnowledgeDocumentEntity> queryWrapper = new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED)
                .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt);

        // 指定知识库时只查询该知识库文档，这是企业知识资产隔离的基础查询条件。
        if (knowledgeBaseId != null) {
            queryWrapper.eq(KnowledgeDocumentEntity::getKnowledgeBaseId, knowledgeBaseId);
        }

        // 指定状态时只返回对应生命周期的文档，便于后续筛选失败或待解析文档。
        if (StringUtils.hasText(normalizedStatus)) {
            queryWrapper.eq(KnowledgeDocumentEntity::getStatus, normalizedStatus);
        }

        return queryWrapper;
    }

    /**
     * 根据 ID 查询未删除文档，不存在时抛出业务异常。
     *
     * @param id 文档 ID
     * @return 文档实体
     */
    private KnowledgeDocumentEntity findRequiredActiveDocument(Long id) {
        // 文档详情和删除都必须过滤 deleted，避免已删除文档被重复操作。
        KnowledgeDocumentEntity entity = knowledgeDocumentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getId, id)
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED));

        // 未查到文档时按 404 返回，调用方可以据此停止后续解析或删除动作。
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在", HttpStatus.NOT_FOUND);
        }

        return entity;
    }

    /**
     * 将文档标记为逻辑删除。
     *
     * @param entity 待删除文档实体
     */
    private void markDocumentDeleted(KnowledgeDocumentEntity entity) {
        // 删除时同时写入状态和删除时间，便于后续审计和排查文件清理问题。
        LambdaUpdateWrapper<KnowledgeDocumentEntity> updateWrapper = new LambdaUpdateWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getId, entity.getId())
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED)
                .set(KnowledgeDocumentEntity::getStatus, DocumentStatus.DELETED.name())
                .set(KnowledgeDocumentEntity::getChunkCount, 0)
                .set(KnowledgeDocumentEntity::getDeleted, DELETED)
                .set(KnowledgeDocumentEntity::getDeletedAt, LocalDateTime.now())
                .set(KnowledgeDocumentEntity::getUpdatedAt, LocalDateTime.now());
        // 即使未来调整了行锁路径，也只允许成功删除的请求继续扣减知识库计数。
        if (knowledgeDocumentMapper.update(null, updateWrapper) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "文档状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    /**
     * 递增知识库文档数量。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void incrementKnowledgeBaseDocumentCount(Long knowledgeBaseId) {
        // 文档数量使用数据库原子自增，避免并发上传时出现丢失更新。
        LambdaUpdateWrapper<KnowledgeBaseEntity> updateWrapper = new LambdaUpdateWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getId, knowledgeBaseId)
                .eq(KnowledgeBaseEntity::getDeleted, NOT_DELETED)
                .setSql("document_count = document_count + 1")
                .set(KnowledgeBaseEntity::getUpdatedAt, LocalDateTime.now());
        // 跨模块更新知识库计数是为了保证知识库列表无需每次聚合文档表，降低列表查询成本。
        // 知识库不存在时不能提交无主文档，异常会回滚文档插入并由上传补偿删除文件。
        if (knowledgeBaseMapper.update(null, updateWrapper) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 递减知识库文档数量。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void decrementKnowledgeBaseDocumentCount(Long knowledgeBaseId) {
        // 使用 GREATEST 防止异常重复删除或历史数据不一致导致文档数量变为负数。
        LambdaUpdateWrapper<KnowledgeBaseEntity> updateWrapper = new LambdaUpdateWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getId, knowledgeBaseId)
                .eq(KnowledgeBaseEntity::getDeleted, NOT_DELETED)
                .setSql("document_count = GREATEST(document_count - 1, 0)")
                .set(KnowledgeBaseEntity::getUpdatedAt, LocalDateTime.now());
        // 删除文档后同步更新知识库计数，保持列表字段和实际未删除文档数量一致。
        // 计数与文档删除必须共同提交，知识库异常时回滚删除并保留原文件。
        if (knowledgeBaseMapper.update(null, updateWrapper) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 规范化可选文档状态。
     *
     * @param status 原始状态
     * @return 枚举状态名称或 null
     */
    private String normalizeOptionalStatus(String status) {
        // 未传状态时不追加状态查询条件，表示查询所有未删除文档。
        if (!StringUtils.hasText(status)) {
            return null;
        }

        try {
            // 状态查询统一转换为枚举大写名称，避免大小写差异影响查询结果。
            return DocumentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            // 非法状态属于调用方请求错误，明确返回 400 而不是空列表，降低排查成本。
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文档状态不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 规范化页码。
     *
     * @param pageNum 原始页码
     * @return 安全页码
     */
    private int normalizePageNum(int pageNum) {
        // 页码小于 1 没有业务意义，统一回退到第一页，避免数据库 offset 出现负数。
        if (pageNum < MIN_PAGE_NUM) {
            return MIN_PAGE_NUM;
        }

        return pageNum;
    }

    /**
     * 规范化每页大小。
     *
     * @param pageSize 原始每页大小
     * @return 安全每页大小
     */
    private int normalizePageSize(int pageSize) {
        // 小于 1 的分页大小按默认值处理，避免调用方传入无效分页导致空结果误导业务。
        if (pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }

        // 超过上限的分页大小强制收敛，避免一次请求读取过多文档元数据。
        if (pageSize > MAX_PAGE_SIZE) {
            return MAX_PAGE_SIZE;
        }

        return pageSize;
    }

    /**
     * 将文档实体转换为 API 响应 DTO。
     *
     * @param entity 文档实体
     * @return 文档响应 DTO
     */
    private KnowledgeDocumentResponse toResponse(KnowledgeDocumentEntity entity) {
        // DTO 不暴露 storageKey，避免外部调用方依赖本地文件系统实现细节。
        return new KnowledgeDocumentResponse(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getFileName(),
                entity.getOriginalFileName(),
                entity.getContentType(),
                entity.getFileSize(),
                entity.getChecksumSha256(),
                entity.getStatus(),
                entity.getFailureReason(),
                entity.getChunkCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
