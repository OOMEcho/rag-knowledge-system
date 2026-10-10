package com.ragknowledge.document.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.config.ingestion.IngestionProperties;
import com.ragknowledge.document.entity.KnowledgeDocumentEntity;
import com.ragknowledge.document.mapper.DocumentChunkMapper;
import com.ragknowledge.document.mapper.KnowledgeDocumentMapper;
import com.ragknowledge.document.service.impl.DocumentVectorCleanupService;
import com.ragknowledge.document.service.impl.KnowledgeDocumentServiceImpl;
import com.ragknowledge.infrastructure.storage.DocumentStorageService;
import com.ragknowledge.infrastructure.storage.StoredDocumentFile;
import com.ragknowledge.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.ragknowledge.knowledge.entity.KnowledgeBaseEntity;
import com.ragknowledge.knowledge.mapper.KnowledgeBaseMapper;
import com.ragknowledge.knowledge.service.impl.KnowledgeBaseServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 验证精准更新、删除计数与文件提交边界，防止业务状态竞争破坏数据一致性。
 *
 * @author xuesong.lei
 * @since 2026-10-10
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class BusinessRegressionTests {
    private static final MybatisConfiguration MAPPING = new MybatisConfiguration();

    @BeforeAll
    static void registerMappings() {
        // 使用真实 MyBatis-Plus 元数据生成 wrapper SQL，避免仅断言 mock 调用次数。
        MAPPING.addMapper(KnowledgeBaseMapper.class);
        MAPPING.addMapper(KnowledgeDocumentMapper.class);
        MAPPING.addMapper(DocumentChunkMapper.class);
    }

    @Test
    void editingShouldNotWriteLifecycleFieldsAndShouldReturnCurrentCount() {
        var mapper = mock(KnowledgeBaseMapper.class);
        // 模拟编辑读取零份文档后发生上传，更新后的读取应看到一份文档。
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(base(0), base(1));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        var result = new KnowledgeBaseServiceImpl(mapper).updateKnowledgeBase(7L, new UpdateKnowledgeBaseRequest("base", null));
        var wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSet()).contains("name", "description", "updated_at")
                .doesNotContain("document_count", "deleted", "status", "created_at");
        assertThat(wrapper.getValue().getSqlSegment()).contains("deleted");
        assertThat(result.documentCount()).isEqualTo(1);
        verify(mapper, never()).updateById(any(KnowledgeBaseEntity.class));
    }

    @Test
    void editingDeletedKnowledgeBaseShouldNotReportSuccess() {
        var mapper = mock(KnowledgeBaseMapper.class);
        // 编辑读取后被删除，精准更新零行必须返回资源不存在。
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(base(0));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> new KnowledgeBaseServiceImpl(mapper)
                .updateKnowledgeBase(7L, new UpdateKnowledgeBaseRequest("base", "edited")))
                .isInstanceOf(BusinessException.class).hasMessage("知识库不存在");
    }

    @Test
    void failedDeleteShouldNotDecrementCountOrDeleteFile() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var storage = mock(DocumentStorageService.class);
        var cleanup = mock(DocumentVectorCleanupService.class);
        // 即便将来锁路径调整后出现零行更新，也必须停止扣减计数。
        when(documents.selectActiveForUpdate(1L)).thenReturn(document("COMPLETED"));
        when(documents.update(isNull(), any(Wrapper.class))).thenReturn(0);
        var service = new KnowledgeDocumentServiceImpl(documents, bases, storage, cleanup, new IngestionProperties());
        assertThatThrownBy(() -> service.deleteDocument(1L)).isInstanceOf(BusinessException.class);
        verify(bases, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(storage);
    }

    @Test
    void processingDocumentShouldBeRejectedBeforeCleanup() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var storage = mock(DocumentStorageService.class);
        var cleanup = mock(DocumentVectorCleanupService.class);
        // 行锁后读取最新处理状态，禁止删除正在入库的文档。
        when(documents.selectActiveForUpdate(1L)).thenReturn(document("PARSING"));
        var service = new KnowledgeDocumentServiceImpl(documents, bases, storage, cleanup, new IngestionProperties());
        assertThatThrownBy(() -> service.deleteDocument(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("入库处理中");
        verifyNoInteractions(cleanup, storage);
    }

    @Test
    void fileShouldOnlyBeRemovedAfterCommit() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var storage = mock(DocumentStorageService.class);
        when(documents.selectActiveForUpdate(1L)).thenReturn(document("COMPLETED"));
        when(documents.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(bases.update(isNull(), any(Wrapper.class))).thenReturn(1);
        var service = new KnowledgeDocumentServiceImpl(documents, bases, storage, mock(DocumentVectorCleanupService.class), new IngestionProperties());
        // 显式驱动事务同步阶段，模拟提交前与提交后的文件清理时机。
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteDocument(1L);
            verifyNoInteractions(storage);
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
            verify(storage).deleteStoredFile("source.md");
        } finally {
            // 测试失败时也必须释放线程局部事务同步，避免污染后续测试。
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackShouldKeepOriginalFile() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var storage = mock(DocumentStorageService.class);
        when(documents.selectActiveForUpdate(1L)).thenReturn(document("COMPLETED"));
        when(documents.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(bases.update(isNull(), any(Wrapper.class))).thenReturn(1);
        var service = new KnowledgeDocumentServiceImpl(documents, bases, storage, mock(DocumentVectorCleanupService.class), new IngestionProperties());
        // 回滚只触发 afterCompletion，不会执行 afterCommit，原文件应保留。
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteDocument(1L);
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCompletion(1));
            verifyNoInteractions(storage);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void bothRetrievalQueriesShouldEnforceDocumentVisibilityAndKnowledgeBase() {
        // 检查真实映射生成的 SQL，两路查询必须共同校验父文档、父知识库与业务归属。
        for (String method : List.of("selectKeywordScores", "selectRetrievableChunks")) {
            var statement = MAPPING.getMappedStatement(DocumentChunkMapper.class.getName() + "." + method);
            var sql = statement.getBoundSql(Map.of("knowledgeBaseId", 7L, "query", "policy", "limit", 5, "chunkIds", List.of(1L))).getSql();
            assertThat(sql).contains("d.status = 'COMPLETED'", "d.deleted = FALSE", "k.deleted = FALSE", "c.knowledge_base_id = ?");
        }
    }

    @Test
    void uploadShouldCleanFileWhenKnowledgeBaseWasDeletedDuringFileSave() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var storage = mock(DocumentStorageService.class);
        // 初次校验成功，落盘后获得行锁时知识库已删除，不能再插入文档。
        when(bases.selectOne(any(Wrapper.class))).thenReturn(base(0));
        when(bases.selectActiveForUpdate(7L)).thenReturn(null);
        when(storage.storeUploadedFile(eq(7L), anyLong(), any())).thenReturn(new StoredDocumentFile("source.md", "source.md", 6, "checksum"));
        var service = new KnowledgeDocumentServiceImpl(documents, bases, storage, mock(DocumentVectorCleanupService.class), new IngestionProperties());
        var file = new MockMultipartFile("file", "source.md", "text/plain", "policy".getBytes());
        assertThatThrownBy(() -> service.uploadDocument(7L, file)).isInstanceOf(BusinessException.class).hasMessage("知识库不存在");
        verify(storage).deleteStoredFile("source.md");
        verify(documents, never()).insert(any(KnowledgeDocumentEntity.class));
    }

    /** 构造具有指定计数的知识库快照。 */
    private KnowledgeBaseEntity base(int count) {
        var entity = new KnowledgeBaseEntity();
        // 模拟完整持久化快照，检验编辑不会写回这些非编辑字段。
        entity.setId(7L);
        entity.setName("base");
        entity.setDocumentCount(count);
        entity.setDeleted(false);
        entity.setStatus("ACTIVE");
        return entity;
    }

    /** 构造删除流程使用的文档快照。 */
    private KnowledgeDocumentEntity document(String status) {
        var entity = new KnowledgeDocumentEntity();
        // 状态由测试场景指定，其余归属保持固定以检查计数与清理调用。
        entity.setId(1L);
        entity.setKnowledgeBaseId(7L);
        entity.setStatus(status);
        entity.setDeleted(false);
        entity.setStorageKey("source.md");
        return entity;
    }
}
