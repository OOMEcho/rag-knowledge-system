package com.ragknowledge.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragknowledge.config.maintenance.OrphanCleanupProperties;
import com.ragknowledge.config.storage.StorageProperties;
import com.ragknowledge.document.entity.KnowledgeDocumentEntity;
import com.ragknowledge.document.mapper.KnowledgeDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 孤儿数据对账清理定时任务，回收无主向量记录和无主上传文件
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Component
@ConditionalOnProperty(prefix = "rag.maintenance.orphan-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrphanDataCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanDataCleanupJob.class);
    private static final boolean NOT_DELETED = false;
    private static final int VECTOR_BATCH_SIZE = 500;
    // 批次上限防止向量删除静默失败时同一批 ID 反复返回导致死循环。
    private static final int MAX_VECTOR_BATCHES = 100;

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final OrphanCleanupProperties orphanCleanupProperties;
    private final Path localStorageRoot;
    private final String vectorTableName;

    public OrphanDataCleanupJob(JdbcTemplate jdbcTemplate,
                                VectorStore vectorStore,
                                KnowledgeDocumentMapper knowledgeDocumentMapper,
                                OrphanCleanupProperties orphanCleanupProperties,
                                StorageProperties storageProperties,
                                @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String vectorTableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.orphanCleanupProperties = orphanCleanupProperties;
        // 与 LocalDocumentStorageService 相同的规范化口径，保证相对路径能对应 storageKey。
        this.localStorageRoot = storageProperties.getLocalRoot().toAbsolutePath().normalize();
        this.vectorTableName = vectorTableName;
    }

    /**
     * 定时对账清理孤儿数据。孤儿来源包括：入库失败后向量删除失败的残留、
     * 删除流程中文件系统清理失败的残留，以及历史异常导致的业务表与向量表不一致。
     * 默认每天凌晨 3 点执行，cron 可通过 rag.maintenance.orphan-cleanup.cron 覆盖。
     */
    @Scheduled(cron = "${rag.maintenance.orphan-cleanup.cron:0 0 3 * * *}")
    public void reconcileOrphanData() {
        // 两类清理相互独立，单类失败不应阻断另一类，因此各自捕获异常。
        try {
            // 先清理向量孤儿，它们会真实参与检索，是对检索质量影响最大的残留数据。
            cleanupOrphanVectors();
        } catch (RuntimeException exception) {
            log.warn("Orphan vector cleanup failed", exception);
        }

        try {
            // 再清理文件孤儿，它们只占用磁盘空间，不影响检索正确性。
            cleanupOrphanFiles();
        } catch (RuntimeException exception) {
            log.warn("Orphan file cleanup failed", exception);
        }
    }

    /**
     * 清理向量表中没有对应活跃 chunk 的孤儿向量。
     * 入库流程先写 chunk 再写向量、删除流程先删向量再删 chunk，
     * 因此"向量存在但活跃 chunk 不存在"一定是异常残留，可以安全删除。
     */
    private void cleanupOrphanVectors() {
        int totalDeleted = 0;

        // 分批扫描并删除，避免一次加载超大孤儿列表；批次上限防御删除静默失败导致的死循环。
        for (int batch = 0; batch < MAX_VECTOR_BATCHES; batch++) {
            // 表名来自应用自身配置属于可信输入，pgvector 表名无法通过 PreparedStatement 参数化。
            List<String> orphanVectorIds = jdbcTemplate.queryForList(
                    "SELECT v.id FROM " + vectorTableName + " v "
                            + "LEFT JOIN document_chunk c ON c.vector_document_id = v.id AND c.deleted = FALSE "
                            + "WHERE c.id IS NULL LIMIT " + VECTOR_BATCH_SIZE,
                    String.class);

            // 没有孤儿向量时对账完成，退出扫描。
            if (orphanVectorIds.isEmpty()) {
                break;
            }

            // 通过 VectorStore 按 ID 精确删除，与业务删除流程使用同一删除入口。
            vectorStore.delete(orphanVectorIds);
            totalDeleted += orphanVectorIds.size();
        }

        // 只在实际清理时输出日志，无孤儿的常态运行保持静默。
        if (totalDeleted > 0) {
            log.info("Orphan vector cleanup removed {} vector documents", totalDeleted);
        }
    }

    /**
     * 清理本地上传目录中没有对应活跃文档元数据的孤儿文件。
     * 只清理超过最小文件年龄的文件，保护"文件已保存、元数据尚未提交"的上传中间态。
     */
    private void cleanupOrphanFiles() {
        // 存储根目录尚未创建说明还没有任何上传，无需对账。
        if (!Files.isDirectory(localStorageRoot)) {
            return;
        }

        // 一次性加载所有活跃 storageKey，避免逐文件查询数据库。
        Set<String> activeStorageKeys = loadActiveStorageKeys();
        // 只清理早于该时间点的文件，避免误删正在上传的文件。
        Instant deletableBefore = Instant.now().minus(orphanCleanupProperties.getMinFileAge());
        int totalDeleted = 0;

        // walk 遍历 kbId/docId/fileName 三层目录结构下的所有常规文件。
        try (Stream<Path> paths = Files.walk(localStorageRoot)) {
            // 收集成列表后逐个处理，避免在遍历流打开期间执行删除操作。
            List<Path> files = paths.filter(Files::isRegularFile).toList();

            for (Path file : files) {
                // 相对路径统一为 / 分隔符后与 storageKey 口径一致，可直接比较。
                String storageKey = localStorageRoot.relativize(file).toString().replace('\\', '/');

                // 仍被活跃文档引用的文件不是孤儿，跳过。
                if (activeStorageKeys.contains(storageKey)) {
                    continue;
                }

                // 新近修改的文件可能属于正在进行的上传，留到下次对账再判断。
                if (isModifiedAfter(file, deletableBefore)) {
                    continue;
                }

                // 删除孤儿文件并尽力清理留下的空目录。
                if (deleteOrphanFile(file)) {
                    totalDeleted++;
                }
            }
        } catch (IOException exception) {
            log.warn("Orphan file cleanup failed to walk storage root {}", localStorageRoot, exception);
        }

        // 只在实际清理时输出日志，保持常态运行静默。
        if (totalDeleted > 0) {
            log.info("Orphan file cleanup removed {} files under {}", totalDeleted, localStorageRoot);
        }
    }

    /**
     * 加载所有未删除文档的 storageKey。
     *
     * @return 活跃 storageKey 集合
     */
    private Set<String> loadActiveStorageKeys() {
        // 只取 storage_key 单列，文档量增长时也不会加载整个实体列表。
        List<KnowledgeDocumentEntity> activeDocuments = knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .select(KnowledgeDocumentEntity::getStorageKey)
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED));
        // 使用 Set 支撑遍历文件时的 O(1) 引用判断。
        Set<String> storageKeys = new HashSet<>();

        for (KnowledgeDocumentEntity document : activeDocuments) {
            // 防御历史数据 storageKey 为空的情况，空值无法对应文件路径。
            if (document.getStorageKey() != null) {
                storageKeys.add(document.getStorageKey());
            }
        }

        return storageKeys;
    }

    /**
     * 判断文件最后修改时间是否晚于指定时间点。
     *
     * @param file            待判断文件
     * @param deletableBefore 可删除时间界限
     * @return 文件晚于界限修改时返回 true
     */
    private boolean isModifiedAfter(Path file, Instant deletableBefore) {
        try {
            // 以文件系统的最后修改时间作为文件年龄口径。
            return Files.getLastModifiedTime(file).toInstant().isAfter(deletableBefore);
        } catch (IOException exception) {
            // 读取失败按"新文件"处理跳过删除，宁可残留也不误删。
            return true;
        }
    }

    /**
     * 删除孤儿文件并尽力清理其父级空目录。
     *
     * @param file 孤儿文件
     * @return 删除成功返回 true
     */
    private boolean deleteOrphanFile(Path file) {
        try {
            // 删除孤儿文件本体。
            Files.deleteIfExists(file);
            // 依次尝试删除 docId 目录和 kbId 目录，仅在目录已空时成功，非空时抛异常被忽略。
            deleteDirectoryIfEmpty(file.getParent());
            deleteDirectoryIfEmpty(file.getParent() == null ? null : file.getParent().getParent());
            return true;
        } catch (IOException exception) {
            // 单个文件删除失败不影响其余文件对账，保留日志供人工排查。
            log.warn("Failed to delete orphan file {}", file, exception);
            return false;
        }
    }

    /**
     * 删除空目录，目录非空或越界时静默跳过。
     *
     * @param directory 待删除目录
     */
    private void deleteDirectoryIfEmpty(Path directory) {
        // 越过存储根目录的路径不属于本任务管理范围，跳过。
        if (directory == null || !directory.startsWith(localStorageRoot) || directory.equals(localStorageRoot)) {
            return;
        }

        try {
            // delete 对非空目录会抛 DirectoryNotEmptyException，天然保证只清理空目录。
            Files.deleteIfExists(directory);
        } catch (IOException exception) {
            // 目录非空属于正常情况，静默跳过即可。
        }
    }
}
