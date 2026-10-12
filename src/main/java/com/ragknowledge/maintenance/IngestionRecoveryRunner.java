package com.ragknowledge.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragknowledge.document.entity.DocumentStatus;
import com.ragknowledge.document.entity.KnowledgeDocumentEntity;
import com.ragknowledge.document.mapper.KnowledgeDocumentMapper;
import com.ragknowledge.document.service.impl.DocumentVectorCleanupService;
import com.ragknowledge.config.ingestion.IngestionStartupGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 入库任务启动恢复器，应用启动时把被重启中断的入库任务标记为失败供用户重试
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngestionRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRecoveryRunner.class);
    private static final boolean NOT_DELETED = false;
    private static final Set<String> PROCESSING_STATUSES = Set.of(DocumentStatus.PARSING.name(), DocumentStatus.EMBEDDING.name());
    private static final String INTERRUPTED_REASON = "服务重启导致入库任务中断，请重试入库";

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final DocumentVectorCleanupService documentVectorCleanupService;
    private final IngestionStartupGuard startupGuard;

    public IngestionRecoveryRunner(KnowledgeDocumentMapper knowledgeDocumentMapper,
                                   DocumentVectorCleanupService documentVectorCleanupService,
                                   IngestionStartupGuard startupGuard) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.documentVectorCleanupService = documentVectorCleanupService;
        this.startupGuard = startupGuard;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Runner 在发布就绪事件前执行；门禁同时保护此时已监听端口的新入库请求。
        recoverInterruptedIngestions();
        // 仅全部恢复成功后开放请求；异常会使启动失败，不能带未完成恢复的状态提供服务。
        startupGuard.markRecoveryCompleted();
    }

    /**
     * 应用启动后恢复被重启中断的入库任务。入库线程池不等待任务完成即关闭，
     * 残留在 PARSING/EMBEDDING 的文档没有任何线程继续处理，必须标记为 FAILED
     * 才能通过重试入口恢复，否则会永久卡在处理中状态。
     */
    private void recoverInterruptedIngestions() {
        // 仅支持单实例：新请求被门禁阻挡，因此处理中记录只能属于上次退出的进程。
        List<KnowledgeDocumentEntity> staleDocuments = knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED)
                .in(KnowledgeDocumentEntity::getStatus, PROCESSING_STATUSES));

        // 没有中断任务时保持静默，避免每次启动都输出无意义日志。
        if (staleDocuments.isEmpty()) {
            return;
        }

        for (KnowledgeDocumentEntity document : staleDocuments) {
            // 中断的任务可能已写入部分 chunk 和向量，尽力清理保证 FAILED 状态下派生数据为空。
            documentVectorCleanupService.deleteActiveChunksAndVectorsQuietly(document.getId());
            // 标记 FAILED 并写入固定中断原因，用户可通过 retry-ingestion 入口重新入库。
            markDocumentInterrupted(document.getId());
            log.warn("Recovered interrupted ingestion, documentId={}, previousStatus={}", document.getId(), document.getStatus());
        }
    }

    /**
     * 将单个中断文档标记为 FAILED。
     *
     * @param documentId 文档 ID
     */
    private void markDocumentInterrupted(Long documentId) {
        // 只更新处理中记录，正常完成和已删除文档必须保持原状；新入库由门禁阻挡。
        LambdaUpdateWrapper<KnowledgeDocumentEntity> updateWrapper = new LambdaUpdateWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getId, documentId)
                .eq(KnowledgeDocumentEntity::getDeleted, NOT_DELETED)
                .in(KnowledgeDocumentEntity::getStatus, PROCESSING_STATUSES)
                .set(KnowledgeDocumentEntity::getStatus, DocumentStatus.FAILED.name())
                .set(KnowledgeDocumentEntity::getFailureReason, INTERRUPTED_REASON)
                .set(KnowledgeDocumentEntity::getChunkCount, 0)
                .set(KnowledgeDocumentEntity::getUpdatedAt, LocalDateTime.now());
        // 更新结果无需强校验，抢占失败说明文档已进入新的生命周期，恢复流程不应干预。
        knowledgeDocumentMapper.update(null, updateWrapper);
    }
}
