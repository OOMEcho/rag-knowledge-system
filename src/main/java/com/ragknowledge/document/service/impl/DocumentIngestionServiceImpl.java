package com.ragknowledge.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.common.response.ErrorCode;
import com.ragknowledge.common.response.PageResponse;
import com.ragknowledge.config.ingestion.IngestionProperties;
import com.ragknowledge.document.dto.DocumentChunkResponse;
import com.ragknowledge.document.dto.KnowledgeDocumentResponse;
import com.ragknowledge.document.entity.DocumentChunkEntity;
import com.ragknowledge.document.entity.DocumentStatus;
import com.ragknowledge.document.entity.KnowledgeDocumentEntity;
import com.ragknowledge.document.mapper.DocumentChunkMapper;
import com.ragknowledge.document.mapper.KnowledgeDocumentMapper;
import com.ragknowledge.document.service.DocumentIngestionService;
import com.ragknowledge.infrastructure.storage.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档入库服务实现，负责异步入库管道：状态抢占、解析、切分、向量化与失败清理
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionServiceImpl.class);
    private static final boolean NOT_DELETED = false;
    private static final int MIN_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int APPROX_CHARS_PER_TOKEN = 4;
    private static final int MAX_FAILURE_REASON_LENGTH = 2000;
    private static final Set<String> PROCESSING_STATUSES = Set.of(DocumentStatus.PARSING.name(), DocumentStatus.EMBEDDING.name());

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentStorageService documentStorageService;
    private final DocumentVectorCleanupService documentVectorCleanupService;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final IngestionProperties ingestionProperties;
    private final TaskExecutor ingestionTaskExecutor;

    public DocumentIngestionServiceImpl(KnowledgeDocumentMapper knowledgeDocumentMapper,
                                        DocumentChunkMapper documentChunkMapper,
                                        DocumentStorageService documentStorageService,
                                        DocumentVectorCleanupService documentVectorCleanupService,
                                        VectorStore vectorStore,
                                        ObjectMapper objectMapper,
                                        IngestionProperties ingestionProperties,
                                        @Qualifier("ingestionTaskExecutor") TaskExecutor ingestionTaskExecutor) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.documentStorageService = documentStorageService;
        this.documentVectorCleanupService = documentVectorCleanupService;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.ingestionProperties = ingestionProperties;
        this.ingestionTaskExecutor = ingestionTaskExecutor;
    }

    @Override
    public KnowledgeDocumentResponse ingestDocument(Long documentId) {
        // 入库前必须读取最新文档状态，避免对已删除文档触发长耗时任务。
        KnowledgeDocumentEntity document = findRequiredActiveDocument(documentId);
        // 通过条件更新原子抢占 PARSING 状态并立即提交，是并发防重入的唯一有效手段：
        // 该方法不在事务中执行，状态变更对其他请求立即可见，重复触发会在这里被拒绝。
        claimIngestion(document);
        // 抢占成功后把耗时的解析和向量化提交到独立线程池，HTTP 请求立即返回 PARSING 状态。
        submitIngestionTask(document.getId());
        // 调用方通过轮询文档详情跟踪 PARSING -> EMBEDDING -> COMPLETED/FAILED 生命周期。
        return toDocumentResponse(document);
    }

    /**
     * 异步执行入库管道：清理旧数据、解析、切分、chunk 持久化和向量写入。
     * 该方法在独立线程池中运行，不持有数据库事务，每次状态更新独立提交，保证外部可见。
     *
     * @param documentId 文档 ID
     */
    private void runIngestionPipeline(Long documentId) {
        // 异步任务启动时重新读取文档，抢占和执行之间文档可能已被删除。
        KnowledgeDocumentEntity document = knowledgeDocumentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getId, documentId)
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED));

        // 文档已被删除时直接放弃任务，删除流程会负责清理 chunk 和向量。
        if (document == null) {
            log.warn("Ingestion task skipped because document was deleted, documentId={}", documentId);
            return;
        }

        // vectorDocumentIds 用于失败时尽力删除已写入 pgvector 的新向量，降低孤儿向量风险。
        List<String> vectorDocumentIds = new ArrayList<>();

        try {
            // 重新入库前必须清理旧 chunk 和旧向量，避免同一文档重复参与检索。
            documentVectorCleanupService.deleteActiveChunksAndVectors(document.getId());
            // 通过 storageKey 加载原始文件资源，解析层不直接拼接本地文件路径。
            Resource resource = documentStorageService.loadStoredFile(document.getStorageKey());
            // 使用 Tika 解析 txt、Markdown、PDF 等常见格式，为后续专用解析器预留扩展空间。
            List<Document> parsedDocuments = parseDocuments(resource, document);
            // 解析结果为空时标记失败，而不是生成空向量污染知识库。
            ensureParsedDocumentsNotEmpty(parsedDocuments);
            // 解析完成后切换到 EMBEDDING，表达当前阶段开始生成和写入向量。
            updateDocumentStatus(document, DocumentStatus.EMBEDDING, null, 0);
            // 使用 TokenTextSplitter 对解析文本切分，避免超长文档直接进入 embedding。
            List<Document> splitDocuments = splitDocuments(parsedDocuments);
            // 切分结果为空说明文档内容不足以入库，必须失败并提示调用方检查文档内容。
            ensureSplitDocumentsNotEmpty(splitDocuments);
            // 构造业务 chunk 和向量文档，二者共享 chunkId 作为向量文档 ID，便于后续精确删除。
            List<ChunkVectorDocument> chunkVectorDocuments = buildChunkVectorDocuments(document, splitDocuments);
            // 提取新向量 ID，失败清理时会用这些 ID 精确删除刚写入的向量。
            vectorDocumentIds = chunkVectorDocuments.stream().map(chunkVectorDocument -> chunkVectorDocument.vectorDocument().getId()).toList();
            // 先保存 chunk 元数据，再写入向量，确保向量引用的 chunkId 在业务表中可追踪。
            insertChunks(chunkVectorDocuments);
            // 写入 pgvector 会触发 Ollama embedding 调用，是本阶段最耗时且最容易因环境失败的步骤。
            addVectorDocuments(chunkVectorDocuments);
            // 入库成功后更新文档状态和 chunk 数量，调用方通过文档详情即可看到可检索状态。
            updateDocumentStatus(document, DocumentStatus.COMPLETED, null, chunkVectorDocuments.size());
        } catch (RuntimeException exception) {
            // 入库失败时尽力删除新写入的向量，避免失败文档仍参与后续检索。
            documentVectorCleanupService.deleteVectorsQuietly(vectorDocumentIds);
            // 入库失败时同步清理本次可能写入的活跃 chunk，保持 FAILED 状态下 chunk_count 为 0。
            documentVectorCleanupService.deleteActiveChunksAndVectorsQuietly(document.getId());
            // 将失败原因写回文档元数据，便于调用方和运维排查解析或 embedding 问题。
            markDocumentFailed(document, exception);
        }
    }

    @Override
    public KnowledgeDocumentResponse retryFailedDocument(Long documentId) {
        // 重试前先读取文档状态，明确该恢复入口只处理 FAILED 文档。
        KnowledgeDocumentEntity document = findRequiredActiveDocument(documentId);
        // 状态校验用于防止用户误把已完成或正在处理的文档通过恢复入口重复入库。
        ensureRetryAllowed(document);
        // 复用标准入库流程，确保重试和首次入库拥有完全一致的解析、切分和向量写入规则。
        return ingestDocument(documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DocumentChunkResponse> listDocumentChunks(Long documentId, int pageNum, int pageSize) {
        // 查询 chunk 前先确认文档存在，避免调用方通过不存在 ID 探测 chunk 表。
        findRequiredActiveDocument(documentId);
        // 分页页码统一兜底，避免数据库 offset 出现负数。
        int safePageNum = normalizePageNum(pageNum);
        // 分页大小统一限流，避免一次返回过多 chunk 内容造成响应过大。
        int safePageSize = normalizePageSize(pageSize);

        // MyBatis-Plus Page 承载分页参数，数据库层执行分页更适合 chunk 内容这种大字段。
        Page<DocumentChunkEntity> page = new Page<>(safePageNum, safePageSize);
        // chunk 列表只展示活跃 chunk，历史重入库产生的逻辑删除 chunk 不对外展示。
        LambdaQueryWrapper<DocumentChunkEntity> queryWrapper = new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, documentId)
                .eq(DocumentChunkEntity::getDeleted, NOT_DELETED)
                .orderByAsc(DocumentChunkEntity::getChunkIndex);
        // 执行分页查询，避免一次加载全部 chunk 内容。
        Page<DocumentChunkEntity> resultPage = documentChunkMapper.selectPage(page, queryWrapper);
        // 转换为响应 DTO，隐藏 metadataJson 等内部治理字段。
        List<DocumentChunkResponse> records = resultPage.getRecords().stream().map(this::toChunkResponse).toList();

        // 返回统一分页结构，保持知识库、文档和 chunk 列表协议一致。
        return PageResponse.of(records, resultPage.getTotal(), resultPage.getCurrent(), resultPage.getSize());
    }

    /**
     * 查询未删除文档，不存在时抛出业务异常。
     *
     * @param documentId 文档 ID
     * @return 文档实体
     */
    private KnowledgeDocumentEntity findRequiredActiveDocument(Long documentId) {
        // 文档入库必须基于未删除元数据，已删除文档不能重新生成 chunk 和向量。
        KnowledgeDocumentEntity document = knowledgeDocumentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getId, documentId)
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED));

        // 未找到文档时返回 404，避免后续解析流程对空实体产生误导性失败状态。
        if (document == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在", HttpStatus.NOT_FOUND);
        }

        return document;
    }

    /**
     * 原子抢占文档入库状态。通过带状态条件的 UPDATE 把文档切换到 PARSING，
     * 只有恰好更新一行才算抢占成功，天然防止并发重复入库。
     *
     * @param document 文档实体，抢占成功后内存状态同步更新为 PARSING
     */
    private void claimIngestion(KnowledgeDocumentEntity document) {
        // 条件更新要求当前状态不是 PARSING/EMBEDDING，两个并发请求只有一个能更新成功。
        LambdaUpdateWrapper<KnowledgeDocumentEntity> updateWrapper = new LambdaUpdateWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getId, document.getId())
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED)
                .notIn(KnowledgeDocumentEntity::getStatus, PROCESSING_STATUSES)
                .set(KnowledgeDocumentEntity::getStatus, DocumentStatus.PARSING.name())
                .set(KnowledgeDocumentEntity::getFailureReason, null)
                .set(KnowledgeDocumentEntity::getChunkCount, 0)
                .set(KnowledgeDocumentEntity::getUpdatedAt, LocalDateTime.now());
        // 更新行数为 0 表示已有入库任务运行中（或文档刚被删除），按状态冲突处理。
        int updatedRows = knowledgeDocumentMapper.update(null, updateWrapper);

        // 抢占失败必须拒绝本次请求，否则并发任务会互相清理对方刚写入的 chunk 和向量。
        if (updatedRows == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "文档正在入库处理中，请稍后再试", HttpStatus.CONFLICT);
        }

        // 同步内存对象，保证 ingestDocument 返回的 DTO 与数据库抢占结果一致。
        document.setStatus(DocumentStatus.PARSING.name());
        document.setFailureReason(null);
        document.setChunkCount(0);
        document.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 将入库管道提交到独立线程池。队列已满时回滚抢占状态并返回业务冲突。
     *
     * @param documentId 文档 ID
     */
    private void submitIngestionTask(Long documentId) {
        try {
            // 入库管道包含 Tika 解析和 Ollama embedding，必须在独立线程池执行，不能阻塞 HTTP 线程。
            ingestionTaskExecutor.execute(() -> runIngestionPipeline(documentId));
        } catch (TaskRejectedException exception) {
            // 队列满时任务不会执行，必须把刚抢占的 PARSING 状态回滚为 FAILED，避免文档永久卡在处理中。
            KnowledgeDocumentEntity document = knowledgeDocumentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                    .eq(KnowledgeDocumentEntity::getId, documentId)
                    .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED));

            // 文档仍然存在时才需要回滚状态，已删除文档由删除流程负责。
            if (document != null) {
                updateDocumentStatus(document, DocumentStatus.FAILED, "入库任务队列已满，请稍后重试", 0);
            }

            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "入库任务队列已满，请稍后重试", HttpStatus.CONFLICT);
        }
    }

    /**
     * 校验文档是否允许通过失败恢复入口重试。
     *
     * @param document 文档实体
     */
    private void ensureRetryAllowed(KnowledgeDocumentEntity document) {
        // 失败恢复入口只处理 FAILED 状态，其他状态应使用普通入库入口或等待当前任务结束。
        if (!DocumentStatus.FAILED.name().equals(document.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "只有入库失败的文档可以重试", HttpStatus.CONFLICT);
        }
    }

    /**
     * 更新文档状态、失败原因和 chunk 数量。
     *
     * @param document      文档实体
     * @param status        目标状态
     * @param failureReason 失败原因
     * @param chunkCount    chunk 数量
     */
    private void updateDocumentStatus(KnowledgeDocumentEntity document, DocumentStatus status, String failureReason, int chunkCount) {
        // 文档状态是入库生命周期的主口径，必须和 chunk_count、failure_reason 同步更新。
        LambdaUpdateWrapper<KnowledgeDocumentEntity> updateWrapper = new LambdaUpdateWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getId, document.getId())
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED)
                .set(KnowledgeDocumentEntity::getStatus, status.name())
                .set(KnowledgeDocumentEntity::getFailureReason, failureReason)
                .set(KnowledgeDocumentEntity::getChunkCount, chunkCount)
                .set(KnowledgeDocumentEntity::getUpdatedAt, LocalDateTime.now());
        // 使用条件更新保护已删除文档，避免入库流程覆盖删除后的状态。
        knowledgeDocumentMapper.update(null, updateWrapper);

        // 同步更新内存对象，保证当前方法返回的 DTO 与数据库更新结果一致。
        document.setStatus(status.name());
        document.setFailureReason(failureReason);
        document.setChunkCount(chunkCount);
        document.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 使用 Tika 解析文档。
     *
     * @param resource 原始文件资源
     * @param document 文档元数据
     * @return 解析后的 Spring AI 文档列表
     */
    private List<Document> parseDocuments(Resource resource, KnowledgeDocumentEntity document) {
        // TikaDocumentReader 负责自动识别文件格式，第一版用它覆盖 txt、Markdown、PDF 等常见文档。
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        // read 会读取原始文件并抽取文本内容，解析失败会抛出 RuntimeException 进入失败状态处理。
        List<Document> parsedDocuments = reader.read();

        // 给解析结果补充业务元数据，后续切分时 Spring AI 会复制这些元数据到 chunk。
        for (Document parsedDocument : parsedDocuments) {
            // metadata 必须包含知识库和文档边界，后续向量检索才能按 knowledgeBaseId 做隔离。
            parsedDocument.getMetadata().putAll(baseMetadata(document, null, null));
        }

        return parsedDocuments;
    }

    /**
     * 校验解析结果不为空。
     *
     * @param parsedDocuments 解析结果
     */
    private void ensureParsedDocumentsNotEmpty(List<Document> parsedDocuments) {
        // Tika 可能解析出空内容，空内容不能生成有效 embedding。
        if (parsedDocuments == null || parsedDocuments.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "文档解析结果为空", HttpStatus.BAD_REQUEST);
        }

        // 只要所有文本都为空，就视为不可入库，避免空 chunk 污染向量库。
        boolean hasText = parsedDocuments.stream().anyMatch(document -> StringUtils.hasText(document.getText()));
        // 没有任何有效文本时标记入库失败，让调用方检查文档是否为扫描件或受保护文件。
        if (!hasText) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "文档未解析出有效文本", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 使用 TokenTextSplitter 切分文档。
     *
     * @param parsedDocuments 已解析文档列表
     * @return 切分后的文档列表
     */
    private List<Document> splitDocuments(List<Document> parsedDocuments) {
        // 切分参数来自配置，后续可针对模型上下文窗口和检索效果调优。
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(ingestionProperties.getChunkSize())
                .withMinChunkSizeChars(ingestionProperties.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(ingestionProperties.getMinChunkLengthToEmbed())
                .withMaxNumChunks(ingestionProperties.getMaxNumChunks())
                .withKeepSeparator(ingestionProperties.isKeepSeparator())
                .build();
        // apply 会保留原文档 metadata，使每个 chunk 继续携带知识库和文档边界。
        return splitter.apply(parsedDocuments);
    }

    /**
     * 校验切分结果不为空。
     *
     * @param splitDocuments 切分结果
     */
    private void ensureSplitDocumentsNotEmpty(List<Document> splitDocuments) {
        // 切分为空说明文本太短或被过滤，不应继续写入 chunk 表和向量库。
        if (splitDocuments == null || splitDocuments.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "文档切分结果为空", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 构造 chunk 实体和对应向量文档。
     *
     * @param document       文档元数据
     * @param splitDocuments 切分后的文档列表
     * @return chunk 与向量文档组合列表
     */
    private List<ChunkVectorDocument> buildChunkVectorDocuments(KnowledgeDocumentEntity document, List<Document> splitDocuments) {
        // 预分配列表大小，避免大量 chunk 入库时频繁扩容。
        List<ChunkVectorDocument> chunkVectorDocuments = new ArrayList<>(splitDocuments.size());
        // 创建时间统一使用同一个时间点，便于审计同一批次生成的 chunk。
        LocalDateTime now = LocalDateTime.now();

        for (int index = 0; index < splitDocuments.size(); index++) {
            // 每个 chunk 预生成业务 ID，并复用为向量文档 ID，保证业务表和向量表可双向追踪。
            Long chunkId = IdWorker.getId();
            // chunk 序号从 0 开始，便于与列表索引、切分顺序和后续引用来源对齐。
            int chunkIndex = index;
            // 读取切分后的文本内容，空白内容在后续构造中跳过。
            String content = splitDocuments.get(index).getText();

            // 空白 chunk 没有检索价值，跳过以减少无效 embedding 调用。
            if (!StringUtils.hasText(content)) {
                continue;
            }

            // 构造向量 metadata，必须包含知识库、文档和 chunk 边界信息。
            Map<String, Object> metadata = baseMetadata(document, chunkId, chunkIndex);
            // 保留原始解析器 metadata，便于后续排查 Tika 来源信息，但不得覆盖核心业务字段。
            metadata.putAll(nonBusinessMetadata(splitDocuments.get(index).getMetadata()));
            // 构造 chunk 持久化实体，metadataJson 用于业务侧排查，不直接作为检索条件。
            DocumentChunkEntity chunkEntity = buildChunkEntity(document, chunkId, chunkIndex, content, metadata, now);
            // 构造 Spring AI Document，显式 ID 等于 chunkId，后续删除向量可直接按 ID 精确删除。
            Document vectorDocument = new Document(String.valueOf(chunkId), content, metadata);
            // 保存组合结果，后续先落业务表再批量写向量库。
            chunkVectorDocuments.add(new ChunkVectorDocument(chunkEntity, vectorDocument));
        }

        return chunkVectorDocuments;
    }

    /**
     * 构造核心业务 metadata。
     *
     * @param document   文档元数据
     * @param chunkId    chunk ID
     * @param chunkIndex chunk 序号
     * @return metadata 映射
     */
    private Map<String, Object> baseMetadata(KnowledgeDocumentEntity document, Long chunkId, Integer chunkIndex) {
        // metadata 只使用简单类型，保证兼容 pgvector 和 Spring AI metadata filter。
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("knowledgeBaseId", String.valueOf(document.getKnowledgeBaseId()));
        metadata.put("documentId", String.valueOf(document.getId()));
        metadata.put("documentName", document.getFileName());
        metadata.put("sourceType", resolveSourceType(document));

        // 解析阶段尚未生成 chunkId，只有构造向量文档时才补充 chunk 级 metadata。
        if (chunkId != null) {
            metadata.put("chunkId", String.valueOf(chunkId));
        }

        // chunkIndex 为空表示文档级 metadata，不能写入空值，因为 Spring AI Document 禁止 metadata 空值。
        if (chunkIndex != null) {
            metadata.put("chunkIndex", chunkIndex);
        }

        return metadata;
    }

    /**
     * 提取非核心业务 metadata。
     *
     * @param metadata 原始 metadata
     * @return 可合并的非核心 metadata
     */
    private Map<String, Object> nonBusinessMetadata(Map<String, Object> metadata) {
        // 复制一份 metadata，避免修改 Spring AI Document 原始 metadata 对象造成非预期副作用。
        Map<String, Object> result = new HashMap<>();

        // 逐项复制是为了过滤空值和核心业务字段，避免解析器 metadata 覆盖检索隔离字段。
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            // 核心业务字段必须由 baseMetadata 统一生成，不能被解析器或历史 metadata 覆盖。
            if (isBusinessMetadataKey(entry.getKey())) {
                continue;
            }

            // Spring AI metadata 不允许 null 值，空值也没有排查价值，直接跳过。
            if (entry.getValue() == null) {
                continue;
            }

            result.put(entry.getKey(), entry.getValue());
        }

        return result;
    }

    /**
     * 判断是否为核心业务 metadata 字段。
     *
     * @param key metadata key
     * @return 是否为核心业务字段
     */
    private boolean isBusinessMetadataKey(String key) {
        // 核心字段决定知识库隔离和引用来源，不允许被外部解析器 metadata 覆盖。
        return Set.of("knowledgeBaseId", "documentId", "documentName", "chunkId", "chunkIndex", "sourceType").contains(key);
    }

    /**
     * 构造 chunk 实体。
     *
     * @param document   文档元数据
     * @param chunkId    chunk ID
     * @param chunkIndex chunk 序号
     * @param content    chunk 内容
     * @param metadata   向量 metadata
     * @param now        当前业务时间
     * @return chunk 实体
     */
    private DocumentChunkEntity buildChunkEntity(KnowledgeDocumentEntity document,
                                                 Long chunkId,
                                                 int chunkIndex,
                                                 String content,
                                                 Map<String, Object> metadata,
                                                 LocalDateTime now) {
        // chunk 表保存文本和 metadata 快照，后续可用于引用来源展示和检索质量排查。
        DocumentChunkEntity entity = new DocumentChunkEntity();
        entity.setId(chunkId);
        entity.setDocumentId(document.getId());
        entity.setKnowledgeBaseId(document.getKnowledgeBaseId());
        entity.setChunkIndex(chunkIndex);
        entity.setContent(content);
        entity.setTokenCount(estimateTokenCount(content));
        entity.setMetadataJson(toJson(metadata));
        entity.setVectorDocumentId(String.valueOf(chunkId));
        entity.setDeleted(NOT_DELETED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    /**
     * 持久化 chunk 列表。
     *
     * @param chunkVectorDocuments chunk 与向量文档组合列表
     */
    private void insertChunks(List<ChunkVectorDocument> chunkVectorDocuments) {
        // 逐条插入便于 MyBatis-Plus 管理实体字段；后续数据量变大时可替换为批量 SQL。
        for (ChunkVectorDocument chunkVectorDocument : chunkVectorDocuments) {
            // 写入 chunk 元数据，向量写入失败时外层失败处理会逻辑删除这些活跃 chunk。
            documentChunkMapper.insert(chunkVectorDocument.chunkEntity());
        }
    }

    /**
     * 将向量文档写入 VectorStore。
     *
     * @param chunkVectorDocuments chunk 与向量文档组合列表
     */
    private void addVectorDocuments(List<ChunkVectorDocument> chunkVectorDocuments) {
        // 提取 Spring AI Document 列表，VectorStore 会调用 Ollama embedding 后写入 pgvector。
        List<Document> vectorDocuments = chunkVectorDocuments.stream().map(ChunkVectorDocument::vectorDocument).toList();
        // 批量 add 可以减少向量库交互次数，并保持同一文档 chunks 的入库批次一致。
        vectorStore.add(vectorDocuments);
    }

    /**
     * 将文档标记为 FAILED。
     *
     * @param document  文档实体
     * @param exception 入库异常
     */
    private void markDocumentFailed(KnowledgeDocumentEntity document, RuntimeException exception) {
        // 完整异常必须进日志，failure_reason 对外只保留脱敏文案，运维通过 documentId 关联排查。
        log.error("Document ingestion failed, documentId={}", document.getId(), exception);
        // 失败原因需要脱敏和裁剪，避免底层异常消息把路径、SQL 等内部细节泄露给 API 调用方。
        String failureReason = normalizeFailureReason(exception);
        // FAILED 状态 chunkCount 固定为 0，因为失败处理会清理活跃 chunk 和向量。
        updateDocumentStatus(document, DocumentStatus.FAILED, failureReason, 0);
    }

    /**
     * 规范化失败原因。业务异常消息是服务层可控文案可直接保存；
     * 其他异常的原始消息可能包含文件路径、SQL 或依赖组件细节，只保留通用提示。
     *
     * @param exception 入库异常
     * @return 可保存并对外展示的失败原因
     */
    private String normalizeFailureReason(RuntimeException exception) {
        String message;

        // 业务异常的消息由服务层显式编写（如"文档解析结果为空"），不含内部实现细节，可安全对外。
        if (exception instanceof BusinessException businessException && StringUtils.hasText(businessException.getMessage())) {
            message = businessException.getMessage();
        } else {
            // 非业务异常一律脱敏为通用文案，原始堆栈已由 markDocumentFailed 写入服务日志。
            message = "入库处理失败，请联系管理员查看服务日志";
        }

        // 超长失败消息截断保存，避免单条元数据异常膨胀。
        if (message.length() > MAX_FAILURE_REASON_LENGTH) {
            return message.substring(0, MAX_FAILURE_REASON_LENGTH);
        }

        return message;
    }

    /**
     * 解析文档来源类型。
     *
     * @param document 文档元数据
     * @return 来源类型
     */
    private String resolveSourceType(KnowledgeDocumentEntity document) {
        // contentType 是浏览器上传时最稳定的类型信息，优先用于来源类型。
        if (StringUtils.hasText(document.getContentType())) {
            return document.getContentType();
        }

        // contentType 缺失时退回文件名，便于后续根据扩展名排查解析行为。
        return document.getFileName();
    }

    /**
     * 估算 token 数。
     *
     * @param content chunk 内容
     * @return 估算 token 数
     */
    private int estimateTokenCount(String content) {
        // 当前只需要粗略估算用于排查 chunk 大小，真实切分由 TokenTextSplitter 控制。
        return Math.max(1, content.length() / APPROX_CHARS_PER_TOKEN);
    }

    /**
     * 将 metadata 序列化为 JSON。
     *
     * @param metadata metadata 映射
     * @return JSON 字符串
     */
    private String toJson(Map<String, Object> metadata) {
        try {
            // metadataJson 是排查字段，不参与核心检索；序列化失败仍应阻止入库，避免 chunk 元数据不可追踪。
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            // JSON 序列化失败说明 metadata 中出现不兼容类型，必须失败以保护后续可维护性。
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "文档分块元数据序列化失败", HttpStatus.BAD_REQUEST);
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

        // 超过上限的分页大小强制收敛，避免一次请求读取过多 chunk 内容。
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
    private KnowledgeDocumentResponse toDocumentResponse(KnowledgeDocumentEntity entity) {
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

    /**
     * 将 chunk 实体转换为 API 响应 DTO。
     *
     * @param entity chunk 实体
     * @return chunk 响应 DTO
     */
    private DocumentChunkResponse toChunkResponse(DocumentChunkEntity entity) {
        // chunk 响应暴露内容和向量文档 ID，便于调试检索引用来源和向量清理问题。
        return new DocumentChunkResponse(
                entity.getId(),
                entity.getDocumentId(),
                entity.getKnowledgeBaseId(),
                entity.getChunkIndex(),
                entity.getContent(),
                entity.getTokenCount(),
                entity.getVectorDocumentId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private record ChunkVectorDocument(DocumentChunkEntity chunkEntity, Document vectorDocument) {
    }
}
