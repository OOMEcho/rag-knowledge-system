-- 初始化数据库扩展与全部业务表（原 V1~V4 迁移合并，项目未发布前一次性建库）。

-- pgvector 提供向量类型与相似度检索；hstore/uuid-ossp 为 Spring AI PgVectorStore 初始化所需。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- pg_trgm 为混合检索的关键词通道提供 word_similarity 函数。
-- 选择 trigram 而不是 tsvector，因为默认分词器无法切分中文，trigram 对中英文都有效。
-- 扩展需超级用户预创建（见 scripts/init-local-db.sh），已存在时本语句无需权限即可通过。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 知识库元数据表：逻辑删除 + 部分唯一索引保证活跃知识库名称唯一。
CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    document_count INTEGER NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_base_name_active
    ON knowledge_base (LOWER(name))
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_knowledge_base_status_deleted
    ON knowledge_base (status, deleted);

-- 文档元数据表：checksum 部分唯一索引实现同一知识库内的内容去重。
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    original_file_name VARCHAR(255),
    content_type VARCHAR(128),
    file_size BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    failure_reason TEXT,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_knowledge_document_base
        FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_base (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_document_checksum_active
    ON knowledge_document (knowledge_base_id, checksum_sha256)
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_knowledge_document_base_status_deleted
    ON knowledge_document (knowledge_base_id, status, deleted);

-- 文档分块表：vector_document_id 关联 pgvector 侧向量记录，关键词通道直接检索 content。
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER NOT NULL DEFAULT 0,
    metadata_json TEXT,
    vector_document_id VARCHAR(128) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_document_chunk_document
        FOREIGN KEY (document_id)
        REFERENCES knowledge_document (id),
    CONSTRAINT fk_document_chunk_base
        FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_base (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_chunk_document_index_active
    ON document_chunk (document_id, chunk_index)
    WHERE deleted = FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_chunk_vector_id_active
    ON document_chunk (vector_document_id)
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_document_chunk_document_deleted
    ON document_chunk (document_id, deleted);

CREATE INDEX IF NOT EXISTS idx_document_chunk_base_deleted
    ON document_chunk (knowledge_base_id, deleted);
