package com.ragknowledge.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.config.ingestion.IngestionProperties;
import com.ragknowledge.config.ingestion.IngestionStartupGuard;
import com.ragknowledge.document.entity.KnowledgeDocumentEntity;
import com.ragknowledge.document.mapper.DocumentChunkMapper;
import com.ragknowledge.document.mapper.KnowledgeDocumentMapper;
import com.ragknowledge.document.service.impl.DocumentIngestionServiceImpl;
import com.ragknowledge.document.service.impl.DocumentVectorCleanupService;
import com.ragknowledge.infrastructure.storage.DocumentStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验证启动恢复门禁的开放顺序与失败行为，阻止新入库与残留清理交错。
 *
 * @author xuesong.lei
 * @since 2026-10-12
 */
class IngestionRecoveryTests {

    @Test
    void ingestionAndRetryShouldRejectBeforeAnyDatabaseWork() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var executor = mock(TaskExecutor.class);
        var guard = new IngestionStartupGuard();
        var service = new DocumentIngestionServiceImpl(documents, mock(DocumentChunkMapper.class),
                mock(DocumentStorageService.class), mock(DocumentVectorCleanupService.class), mock(VectorStore.class),
                new ObjectMapper(), new IngestionProperties(), executor, guard);
        // 两个入口都必须在读取文档或提交任务之前返回可重试的 503。
        assertThatThrownBy(() -> service.ingestDocument(1L)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> service.retryFailedDocument(1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(documents, executor);
    }

    @Test
    void guardShouldOpenOnlyAfterCleanupAndStateUpdate() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var cleanup = mock(DocumentVectorCleanupService.class);
        var guard = new IngestionStartupGuard();
        var interrupted = new KnowledgeDocumentEntity();
        interrupted.setId(1L);
        interrupted.setStatus("EMBEDDING");
        when(documents.selectList(any())).thenReturn(List.of(interrupted));
        doAnswer(invocation -> {
            // 清理过程中即使 HTTP 端口已监听，新请求仍然不能抢占任务。
            assertThatThrownBy(guard::ensureReadyForIngestion).isInstanceOf(BusinessException.class);
            return null;
        }).when(cleanup).deleteActiveChunksAndVectorsQuietly(1L);
        when(documents.update(isNull(), any())).thenAnswer(invocation -> {
            // 标记失败尚未完成时仍必须保持门禁关闭。
            assertThatThrownBy(guard::ensureReadyForIngestion).isInstanceOf(BusinessException.class);
            return 1;
        });
        new IngestionRecoveryRunner(documents, cleanup, guard).run(null);
        // 恢复结束后才允许启动新任务。
        guard.ensureReadyForIngestion();
        var order = inOrder(cleanup, documents);
        order.verify(cleanup).deleteActiveChunksAndVectorsQuietly(1L);
        order.verify(documents).update(isNull(), any());
    }

    @Test
    void failedRecoveryShouldNeverOpenGuard() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var guard = new IngestionStartupGuard();
        when(documents.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));
        var runner = new IngestionRecoveryRunner(documents, mock(DocumentVectorCleanupService.class), guard);
        // 不吞掉数据库故障，启动必须失败而非带着未恢复状态提供服务。
        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(guard::ensureReadyForIngestion).isInstanceOf(BusinessException.class);
    }
}
