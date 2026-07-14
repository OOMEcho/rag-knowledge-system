package com.ragknowledge.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 迁移集成测试，用真实 pgvector 容器验证迁移脚本可在干净库执行
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Testcontainers
class PostgresMigrationIT {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("rag_knowledge_test")
            .withUsername("rag_user")
            .withPassword("rag_password");

    @Test
    void flywayMigrationsShouldApplyToPgvectorPostgres() {
        // 使用 Testcontainers 启动 pgvector PostgreSQL，验证迁移脚本适配真实扩展环境。
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        // 执行全部迁移，确保业务表、pgvector 扩展和索引能在真实 PostgreSQL 中创建。
        int migrations = flyway.migrate().migrationsExecuted;

        // 至少应执行一条迁移，避免测试误连空配置或未加载 migration 目录。
        assertThat(migrations).isGreaterThan(0);
    }
}
