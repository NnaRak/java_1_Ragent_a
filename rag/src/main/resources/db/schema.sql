-- 新 agent 自己的入库表结构，与 /home/naraka/project1java/ragent 的库完全隔离。
-- 应用启动时由 spring.sql.init 自动执行，全部使用 IF NOT EXISTS，可重复运行。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS t_rag_knowledge_base (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(64)  NOT NULL,
    collection_name VARCHAR(64)  NOT NULL,
    created_by      VARCHAR(20)  NOT NULL,
    updated_by      VARCHAR(20),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_rag_collection_name UNIQUE (collection_name)
);

CREATE TABLE IF NOT EXISTS t_rag_knowledge_document (
    id               VARCHAR(20)   NOT NULL PRIMARY KEY,
    kb_id            VARCHAR(20)   NOT NULL,
    doc_name         VARCHAR(256)  NOT NULL,
    enabled          SMALLINT      NOT NULL DEFAULT 1,
    chunk_count      INTEGER       DEFAULT 0,
    file_url         VARCHAR(1024) NOT NULL,
    file_type        VARCHAR(16)   NOT NULL,
    mime_type        VARCHAR(128),
    file_size        BIGINT,
    process_mode     VARCHAR(16)   DEFAULT 'chunk',
    status           VARCHAR(16)   NOT NULL DEFAULT 'pending',
    source_type      VARCHAR(16),
    ingestion_spec   JSONB,
    created_by       VARCHAR(20)   NOT NULL,
    updated_by       VARCHAR(20),
    create_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_rag_kb_id ON t_rag_knowledge_document (kb_id);

CREATE TABLE IF NOT EXISTS t_rag_knowledge_chunk (
    id             VARCHAR(20) NOT NULL PRIMARY KEY,
    kb_id          VARCHAR(20) NOT NULL,
    doc_id         VARCHAR(20) NOT NULL,
    chunk_index    INTEGER     NOT NULL,
    content        TEXT        NOT NULL,
    content_hash   VARCHAR(64),
    char_count     INTEGER,
    token_count    INTEGER,
    embedding_text TEXT,
    enabled        SMALLINT    NOT NULL DEFAULT 1,
    created_by     VARCHAR(20) NOT NULL,
    updated_by     VARCHAR(20),
    create_time    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_rag_doc_id ON t_rag_knowledge_chunk (doc_id);

CREATE TABLE IF NOT EXISTS t_rag_knowledge_document_chunk_log (
    id               VARCHAR(20) NOT NULL PRIMARY KEY,
    doc_id           VARCHAR(20) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    process_mode     VARCHAR(16),
    parse_profile    VARCHAR(16),
    extract_duration BIGINT,
    chunk_duration   BIGINT,
    embed_duration   BIGINT,
    persist_duration BIGINT,
    total_duration   BIGINT,
    chunk_count      INTEGER,
    error_message    TEXT,
    start_time       TIMESTAMP,
    end_time         TIMESTAMP,
    create_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_log_doc_id ON t_rag_knowledge_document_chunk_log (doc_id);

CREATE TABLE IF NOT EXISTS t_rag_knowledge_vector (
    id              VARCHAR(20) PRIMARY KEY,
    collection_name VARCHAR(64) NOT NULL,
    content         TEXT,
    metadata        JSONB,
    embedding       vector(1536)
);
CREATE INDEX IF NOT EXISTS idx_rag_kv_collection_name ON t_rag_knowledge_vector (collection_name);
CREATE INDEX IF NOT EXISTS idx_rag_kv_metadata ON t_rag_knowledge_vector USING gin (metadata);
CREATE INDEX IF NOT EXISTS idx_rag_kv_embedding ON t_rag_knowledge_vector USING hnsw (embedding vector_cosine_ops);
