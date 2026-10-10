package com.ragknowledge.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.config.ingestion.IngestionProperties;
import com.ragknowledge.document.mapper.*;
import com.ragknowledge.document.service.KnowledgeDocumentService;
import com.ragknowledge.document.service.DocumentIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.document.service.impl.*;
import com.ragknowledge.infrastructure.storage.*;
import com.ragknowledge.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.ragknowledge.knowledge.mapper.KnowledgeBaseMapper;
import com.ragknowledge.knowledge.service.KnowledgeBaseService;
import com.ragknowledge.knowledge.service.impl.KnowledgeBaseServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ai.vectorstore.VectorStore;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 使用隔离 PostgreSQL 验证业务事务并发与检索可见性，不依赖 Ollama。
 *
 * @author xuesong.lei
 * @since 2026-10-10
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(BusinessConsistencyIT.DatabaseConfiguration.class)
class BusinessConsistencyIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private KnowledgeDocumentService documents;
    @Autowired private KnowledgeBaseService bases;
    @Autowired private DocumentIngestionService ingestion;
    @Autowired private DocumentChunkMapper chunks;

    @Test
    void concurrentDeletionShouldDecrementExactlyOnce() throws Exception {
        // 两份文档中并发删除同一份，另一份与计数必须保留。
        insertBase(101L, 2);
        insertDocument(1011L, 101L, "COMPLETED");
        insertDocument(1012L, 101L, "COMPLETED");
        var outcomes = raceWithRowLock("knowledge_document", 1011L,
                () -> documents.deleteDocument(1011L), () -> documents.deleteDocument(1011L));
        assertThat(outcomes.stream().filter(outcome -> outcome == null).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT document_count FROM knowledge_base WHERE id=101", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE knowledge_base_id=101 AND deleted=FALSE", Integer.class)).isEqualTo(1);
    }

    @Test
    void uploadAndKnowledgeBaseDeletionShouldNeverBothSucceed() throws Exception {
        insertBase(102L, 0);
        // 上传和删除同时等待父行锁，释放后只能有一个操作成功。
        var file = new MockMultipartFile("file", "source.md", "text/plain", "policy".getBytes());
        var outcomes = raceWithRowLock("knowledge_base", 102L,
                () -> documents.uploadDocument(102L, file), () -> bases.deleteKnowledgeBase(102L));
        assertThat(outcomes.stream().filter(outcome -> outcome == null).count()).isEqualTo(1);
        // 不论谁先获得锁，都不能存在逻辑删除知识库下的活跃文档。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document d JOIN knowledge_base k ON k.id=d.knowledge_base_id WHERE k.id=102 AND k.deleted=TRUE AND d.deleted=FALSE", Integer.class)).isZero();
        int activeDocuments = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE knowledge_base_id=102 AND deleted=FALSE", Integer.class);
        assertThat(jdbc.queryForObject("SELECT document_count FROM knowledge_base WHERE id=102", Integer.class)).isEqualTo(activeDocuments);
    }

    @Test
    void deletionAndIngestionClaimShouldNeverBothSucceed() throws Exception {
        insertBase(106L, 1);
        insertDocument(1061L, 106L, "COMPLETED");
        // 入库原子更新与删除行锁竞争，只允许删除或抢占中的一个成功。
        var outcomes = raceWithRowLock("knowledge_document", 1061L,
                () -> ingestion.ingestDocument(1061L), () -> documents.deleteDocument(1061L));
        assertThat(outcomes.stream().filter(outcome -> outcome == null).count()).isEqualTo(1);
        boolean deleted = jdbc.queryForObject("SELECT deleted FROM knowledge_document WHERE id=1061", Boolean.class);
        // 两种合法终态：删除且计数归零，或保留文档且任务处于 PARSING。
        assertThat(jdbc.queryForObject("SELECT document_count FROM knowledge_base WHERE id=106", Integer.class))
                .isEqualTo(deleted ? 0 : 1);
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_document WHERE id=1061", String.class))
                .isEqualTo(deleted ? "DELETED" : "PARSING");
    }

    @Test
    void editingShouldPreserveCountAndAllowClearingDescription() {
        insertBase(103L, 1);
        insertDocument(1031L, 103L, "COMPLETED");
        // 编辑不能写回计数，null 描述应明确清空而不是被默认非空更新策略忽略。
        bases.updateKnowledgeBase(103L, new UpdateKnowledgeBaseRequest("edited", null));
        assertThat(jdbc.queryForObject("SELECT document_count FROM knowledge_base WHERE id=103", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT description FROM knowledge_base WHERE id=103", String.class)).isNull();
    }

    @Test
    void retrievalShouldHideUnfinishedDeletedAndCrossBaseDocuments() {
        insertBase(104L, 4);
        insertBase(105L, 1);
        // 所有正文相同，检索可见性只能依靠业务状态和归属判断。
        insertDocument(1041L, 104L, "COMPLETED");
        insertDocument(1042L, 104L, "EMBEDDING");
        insertDocument(1043L, 104L, "FAILED");
        insertDocument(1044L, 104L, "COMPLETED");
        insertDocument(1051L, 105L, "COMPLETED");
        jdbc.update("UPDATE knowledge_document SET deleted=TRUE WHERE id=1044");
        for (long documentId : new long[]{1041, 1042, 1043, 1044, 1051}) {
            long baseId = documentId == 1051 ? 105 : 104;
            // 对应向量 ID 使用片段 ID，保持真实入库约定。
            jdbc.update("INSERT INTO document_chunk(id, document_id, knowledge_base_id, chunk_index, content, vector_document_id) VALUES(?,?,?,?,?,?)",
                    documentId, documentId, baseId, 0, "reimbursement policy", String.valueOf(documentId));
        }
        assertThat(chunks.selectKeywordScores(104L, "policy", 10)).extracting(score -> score.getId()).containsExactly(1041L);
        assertThat(chunks.selectRetrievableChunks(104L, List.of(1041L, 1042L, 1043L, 1044L, 1051L)))
                .extracting(chunk -> chunk.getId()).containsExactly(1041L);
    }

    /** 创建隔离的知识库测试数据。 */
    private void insertBase(long id, int count) {
        // 不触碰任何用户数据库，全部记录只存在于本测试容器。
        jdbc.update("INSERT INTO knowledge_base(id,name,description,document_count) VALUES(?,?,?,?)", id, "base-" + id, "description", count);
    }

    /** 创建指定状态的文档测试数据。 */
    private void insertDocument(long id, long baseId, String status) {
        // 指纹固定长度且各文档不同，避免唯一约束干扰并发测试目标。
        jdbc.update("INSERT INTO knowledge_document(id,knowledge_base_id,file_name,file_size,checksum_sha256,storage_key,status) VALUES(?,?,?,?,?,?,?)",
                id, baseId, "source.md", 6, String.format("%064d", id), "source-" + id, status);
    }

    /** 持有业务行锁，确认两个操作都等待数据库锁后释放，构造可重复的并发时序。 */
    private List<BusinessException> raceWithRowLock(String table, long id, Runnable first, Runnable second) throws Exception {
        var executor = Executors.newFixedThreadPool(3);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // 表名来自本测试内部固定常量，业务参数仍使用 JDBC 占位符。
        var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", id);
            locked.countDown();
            try {
                // 超时确保断言失败时不会遗留永远持锁的测试线程。
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待释放测试行锁超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }));
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var firstResult = executor.submit(() -> outcome(first));
            var secondResult = executor.submit(() -> outcome(second));
            // 观察真实 PostgreSQL 等锁状态，不能靠固定 sleep 猜测并发请求已经到达关键位置。
            awaitLockWaiters();
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            return Arrays.asList(firstResult.get(5, TimeUnit.SECONDS), secondResult.get(5, TimeUnit.SECONDS));
        } finally {
            // 所有失败路径都释放锁并终止执行器。
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** 等待两个真实业务事务进入数据库锁等待。 */
    private void awaitLockWaiters() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            // 容器只供本测试使用，不会把其他业务请求误算为等待者。
            if (jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Integer.class) >= 2) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("两个业务请求未进入预期行锁等待");
    }

    /** 保留业务冲突供断言检查，非业务异常交给 Future 暴露测试失败。 */
    private BusinessException outcome(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (BusinessException exception) {
            return exception;
        }
    }

    /**
     * 仅装配数据库、事务与业务服务，模型和文件存储使用测试替身。
     *
     * @author xuesong.lei
     * @since 2026-10-10
     */
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {KnowledgeBaseMapper.class, KnowledgeDocumentMapper.class})
    static class DatabaseConfiguration {
        /** 创建测试容器数据源。 */
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        /** 在业务 Mapper 初始化前执行真实迁移。 */
        @Bean(initMethod = "migrate")
        Flyway flyway(DataSource dataSource) {
            return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        }

        /** 使用 MyBatis-Plus 创建真实 Mapper 与字段映射。 */
        @Bean
        @DependsOn("flyway")
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        /** 启用服务方法的真实 JDBC 事务。 */
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /** 提供测试查询与断言。 */
        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        /** 真实清理业务 chunk，向量服务使用替身避免依赖模型。 */
        @Bean
        DocumentVectorCleanupService cleanup(DocumentChunkMapper chunks) {
            return new DocumentVectorCleanupService(chunks, mock(VectorStore.class));
        }

        /** 装配带事务代理的知识库服务。 */
        @Bean
        KnowledgeBaseService bases(KnowledgeBaseMapper mapper) {
            return new KnowledgeBaseServiceImpl(mapper);
        }

        /** 保留真实入库抢占，将耗时后台任务留在队列替身中，避免依赖 Ollama。 */
        @Bean
        DocumentIngestionService ingestion(KnowledgeDocumentMapper documents, DocumentChunkMapper chunks, DocumentVectorCleanupService cleanup) {
            return new DocumentIngestionServiceImpl(documents, chunks, mock(DocumentStorageService.class), cleanup,
                    mock(VectorStore.class), new ObjectMapper(), new IngestionProperties(), task -> {});
        }

        /** 装配文档服务并模拟已保存文件，实际数据库写入与锁全部保留。 */
        @Bean
        KnowledgeDocumentService documents(KnowledgeDocumentMapper documents, KnowledgeBaseMapper bases, DocumentVectorCleanupService cleanup) {
            var storage = mock(DocumentStorageService.class);
            // 上传路径需要文件信息，但本测试只验证数据库事务，不创建用户文件。
            org.mockito.Mockito.when(storage.storeUploadedFile(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new StoredDocumentFile("test-source", "source.md", 6, "0".repeat(64)));
            return new KnowledgeDocumentServiceImpl(documents, bases, storage, cleanup, new IngestionProperties());
        }
    }
}
