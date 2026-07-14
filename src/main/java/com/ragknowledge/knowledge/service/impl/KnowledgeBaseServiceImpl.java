package com.ragknowledge.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.common.response.ErrorCode;
import com.ragknowledge.common.response.PageResponse;
import com.ragknowledge.knowledge.dto.CreateKnowledgeBaseRequest;
import com.ragknowledge.knowledge.dto.KnowledgeBaseResponse;
import com.ragknowledge.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.ragknowledge.knowledge.entity.KnowledgeBaseEntity;
import com.ragknowledge.knowledge.entity.KnowledgeBaseStatus;
import com.ragknowledge.knowledge.mapper.KnowledgeBaseMapper;
import com.ragknowledge.knowledge.service.KnowledgeBaseService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库服务实现
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final boolean NOT_DELETED = false;
    private static final boolean DELETED = true;
    private static final int DEFAULT_DOCUMENT_COUNT = 0;
    private static final int MIN_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        // 知识库名称会进入唯一索引和检索条件，入库前统一裁剪空白，避免用户输入差异造成重复数据。
        String normalizedName = normalizeName(request.name());
        // 创建时不允许与任何未删除知识库重名，保证知识库作为业务边界的可识别性。
        ensureNameAvailable(normalizedName, null);

        // 统一生成时间戳，避免同一实体的创建时间和更新时间出现无意义偏差。
        LocalDateTime now = LocalDateTime.now();
        // 构造持久化实体时集中设置生命周期字段，避免依赖数据库默认值导致返回对象缺少字段。
        KnowledgeBaseEntity entity = buildNewKnowledgeBaseEntity(request, normalizedName, now);

        // 通过 MyBatis-Plus 写入业务表，后续可在此处扩展审计字段或事件发布。
        knowledgeBaseMapper.insert(entity);

        // 将持久化实体转换为 API DTO，隔离数据库字段和外部接口结构。
        return toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<KnowledgeBaseResponse> listKnowledgeBases(String keyword, int pageNum, int pageSize) {
        // 分页参数必须在服务层兜底，避免不同 Controller 调用路径产生不一致分页边界。
        int safePageNum = normalizePageNum(pageNum);
        // 限制单页大小，避免开源项目默认接口被大分页请求拖垮。
        int safePageSize = normalizePageSize(pageSize);

        // MyBatis-Plus Page 保存数据库分页结果，后续由 PageResponse 统一转换为 API 协议。
        Page<KnowledgeBaseEntity> page = new Page<>(safePageNum, safePageSize);
        // 查询条件只返回未删除知识库，逻辑删除数据不应出现在正常业务列表中。
        LambdaQueryWrapper<KnowledgeBaseEntity> queryWrapper = buildListQueryWrapper(keyword);
        // 执行分页查询，数据库层完成 limit/offset，避免应用层加载过多数据。
        Page<KnowledgeBaseEntity> resultPage = knowledgeBaseMapper.selectPage(page, queryWrapper);
        // 实体列表转换为响应 DTO，避免向 API 暴露 deleted、deletedAt 等内部治理字段。
        List<KnowledgeBaseResponse> records = resultPage.getRecords().stream().map(this::toResponse).toList();

        // 统一分页响应结构，便于前端或调用方按固定字段解析分页结果。
        return PageResponse.of(records, resultPage.getTotal(), resultPage.getCurrent(), resultPage.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getKnowledgeBase(Long id) {
        // 查询详情必须校验未删除状态，避免调用方通过 ID 访问已删除知识库。
        KnowledgeBaseEntity entity = findRequiredActiveEntity(id);
        // 将实体转换为响应 DTO，保护内部删除标记和治理字段不外泄。
        return toResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseResponse updateKnowledgeBase(Long id, UpdateKnowledgeBaseRequest request) {
        // 更新前必须读取当前实体，确认知识库存在且未被逻辑删除。
        KnowledgeBaseEntity entity = findRequiredActiveEntity(id);
        // 名称仍然是知识库业务边界标识，更新时同样需要裁剪空白。
        String normalizedName = normalizeName(request.name());

        // 只有名称发生变化时才检查重名，减少无意义的数据库计数查询。
        if (!entity.getName().equals(normalizedName)) {
            // 排除当前知识库 ID 后检查名称，允许用户只修改描述或保持原名称。
            ensureNameAvailable(normalizedName, id);
        }

        // 业务字段和更新时间一起更新，确保列表排序和审计信息能反映最近修改。
        entity.setName(normalizedName);
        entity.setDescription(request.description());
        entity.setUpdatedAt(LocalDateTime.now());

        // 根据主键更新实体，当前表未启用乐观锁，后续有并发编辑需求再加入 version 字段。
        knowledgeBaseMapper.updateById(entity);
        // 返回更新后的实体状态，避免调用方再次查询才能拿到最新字段。
        return toResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long id) {
        // 删除前读取实体以确认存在性和文档数量，避免直接 update 造成静默无效删除。
        KnowledgeBaseEntity entity = findRequiredActiveEntity(id);

        // 知识库下仍有文档时禁止删除，避免误删导致后续文档、向量和引用来源失去业务边界。
        if (entity.getDocumentCount() != null && entity.getDocumentCount() > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "知识库下存在文档，请先删除文档后再删除知识库", HttpStatus.CONFLICT);
        }

        // 逻辑删除保留审计历史，后续如需恢复或排查数据问题仍可追踪原始记录。
        LambdaUpdateWrapper<KnowledgeBaseEntity> updateWrapper = new LambdaUpdateWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getId, id)
                .eq(KnowledgeBaseEntity::getDeleted, NOT_DELETED)
                .set(KnowledgeBaseEntity::getDeleted, DELETED)
                .set(KnowledgeBaseEntity::getDeletedAt, LocalDateTime.now())
                .set(KnowledgeBaseEntity::getUpdatedAt, LocalDateTime.now());
        // 使用带 deleted 条件的更新，避免重复删除或并发删除覆盖异常状态。
        knowledgeBaseMapper.update(null, updateWrapper);
    }

    /**
     * 构造新的知识库实体。
     *
     * @param request        创建请求
     * @param normalizedName 已规范化的知识库名称
     * @param now            当前业务时间
     * @return 待持久化的知识库实体
     */
    private KnowledgeBaseEntity buildNewKnowledgeBaseEntity(CreateKnowledgeBaseRequest request, String normalizedName, LocalDateTime now) {
        // 创建时所有生命周期字段显式赋值，避免数据库默认值和 Java 返回值不一致。
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setName(normalizedName);
        entity.setDescription(request.description());
        entity.setStatus(KnowledgeBaseStatus.ACTIVE.name());
        entity.setDocumentCount(DEFAULT_DOCUMENT_COUNT);
        entity.setDeleted(NOT_DELETED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    /**
     * 构造知识库列表查询条件。
     *
     * @param keyword 名称关键字
     * @return MyBatis-Plus 查询条件
     */
    private LambdaQueryWrapper<KnowledgeBaseEntity> buildListQueryWrapper(String keyword) {
        // 默认只查询未删除数据，这是业务列表的基础数据口径，不能随意移除。
        LambdaQueryWrapper<KnowledgeBaseEntity> queryWrapper = new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getDeleted, NOT_DELETED)
                .orderByDesc(KnowledgeBaseEntity::getUpdatedAt);

        // 只有关键字有效时才追加 like 条件，避免空字符串导致数据库无法使用更优查询计划。
        if (StringUtils.hasText(keyword)) {
            // 关键字当前只匹配名称，描述全文检索留到后续搜索能力中统一设计。
            queryWrapper.like(KnowledgeBaseEntity::getName, keyword.trim());
        }

        return queryWrapper;
    }

    /**
     * 查询未删除知识库，不存在时抛出业务异常。
     *
     * @param id 知识库 ID
     * @return 未删除知识库实体
     */
    private KnowledgeBaseEntity findRequiredActiveEntity(Long id) {
        // ID 是所有后续业务操作的边界条件，先按 ID 和 deleted 状态查询避免访问已删除数据。
        KnowledgeBaseEntity entity = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getId, id)
                .eq(KnowledgeBaseEntity::getDeleted, NOT_DELETED));

        // 未查询到实体时返回 404，明确告诉调用方资源不存在或已被删除。
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在", HttpStatus.NOT_FOUND);
        }

        return entity;
    }

    /**
     * 校验知识库名称在未删除数据中是否可用。
     *
     * @param name      待校验名称
     * @param excludeId 需要排除的知识库 ID，创建时为空
     */
    private void ensureNameAvailable(String name, Long excludeId) {
        // 使用 QueryWrapper 是因为 PostgreSQL 唯一索引按 lower(name) 判断，Java 侧校验也必须保持同一口径。
        QueryWrapper<KnowledgeBaseEntity> queryWrapper = new QueryWrapper<KnowledgeBaseEntity>()
                .eq("deleted", NOT_DELETED)
                .apply("LOWER(name) = LOWER({0})", name);

        // 更新场景需要排除当前记录，避免名称未改变时误判为重复。
        if (excludeId != null) {
            queryWrapper.ne("id", excludeId);
        }

        // 通过 count 判断冲突即可，不需要加载实体内容，减少不必要的数据传输。
        Long duplicateCount = knowledgeBaseMapper.selectCount(queryWrapper);
        // 大于 0 表示名称已经被其他未删除知识库占用，必须返回冲突错误而不是覆盖原数据。
        if (duplicateCount > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "知识库名称已存在", HttpStatus.CONFLICT);
        }
    }

    /**
     * 规范化知识库名称。
     *
     * @param name 原始名称
     * @return 去除首尾空白后的名称
     */
    private String normalizeName(String name) {
        // Bean Validation 已保证非空，这里只处理业务层入库前的空白裁剪。
        return name.trim();
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

        // 超过上限的分页大小强制收敛，避免一次请求读取过多知识库数据。
        if (pageSize > MAX_PAGE_SIZE) {
            return MAX_PAGE_SIZE;
        }

        return pageSize;
    }

    /**
     * 将知识库实体转换为 API 响应 DTO。
     *
     * @param entity 知识库实体
     * @return 知识库响应 DTO
     */
    private KnowledgeBaseResponse toResponse(KnowledgeBaseEntity entity) {
        // DTO 只暴露调用方需要的业务字段，隐藏 deleted、deletedAt 等内部治理字段。
        return new KnowledgeBaseResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getDocumentCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
