-- embedding-service(ADR-0098): ES 청크의 벡터(pgvector). ES-First 하이브리드 RAG 계획 §1-2 단계 3·4.
-- LAN 은 rag-pgvector 의 embedding_service(확장은 rag-init-db.sh 가 관리 계정으로 먼저 만든다 — 여기서는 있으면 그대로).
CREATE EXTENSION IF NOT EXISTS vector;

-- 임베딩 캐시: 같은 글(임베딩한 글의 해시)·같은 모델 판이면 다시 부르지 않는다(Ollama CPU 절약).
CREATE TABLE embedding_cache (
    text_sha256    varchar(64)   NOT NULL,
    model          varchar(100)  NOT NULL,
    model_version  varchar(100)  NOT NULL,
    embedding      vector(1024)  NOT NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (text_sha256, model, model_version)
);

-- 청크 벡터: ES 청크 하나 = 행 하나(같은 chunkId). customer_app 문서는 운영자·고객 두 범위에 같은 chunkId 로 들어간다 → 키에 범위.
-- 본문·제목 경로는 ES 에서 읽은 값의 사본(벡터 검색 결과를 ES 없이도 보이게). 내린 청크는 지우지 않고 is_active = false.
CREATE TABLE chunk_vector (
    access_scope    varchar(20)   NOT NULL,
    chunk_id        varchar(64)   NOT NULL,
    document_id     varchar(500)  NOT NULL,
    content_sha256  varchar(64)   NOT NULL,   -- 문서 판(ES contentSha256)
    text_sha256     varchar(64)   NOT NULL,   -- 임베딩한 글의 해시(캐시 키)
    commit_sha      varchar(64),
    section_path    text          NOT NULL,
    ordinal         integer       NOT NULL,
    content         text          NOT NULL,
    model           varchar(100)  NOT NULL,
    model_version   varchar(100)  NOT NULL,
    embedding       vector(1024)  NOT NULL,
    is_active       boolean       NOT NULL,
    embedded_at     timestamptz   NOT NULL,
    retired_at      timestamptz,
    PRIMARY KEY (access_scope, chunk_id)
);
CREATE INDEX ix_chunk_vector_doc ON chunk_vector (access_scope, document_id);
CREATE INDEX ix_chunk_vector_embedding ON chunk_vector USING hnsw (embedding vector_cosine_ops);
