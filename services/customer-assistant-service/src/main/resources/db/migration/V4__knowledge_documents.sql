-- 고객 RAG 문서의 정본(ADR-0094, 2026-11-28 — 사용자 "디폴트는 Git 하고 운영자랑 고객 둘다 DB에"). ai-assistant-service V3 와 같은 표.
-- 처음 한 번 저장소 manifest 의 문서(customer_app 16 + customer-faq 13)를 가져오고, 그 뒤로는 초안 → 승인(approval-service RAG_DOCUMENT,
-- 정본 customer-assistant-service) → 반영 → 재색인으로만 바뀐다. customer_app 은 운영자 RAG 도 근거로 쓴다 — 원본은 여기 하나(내부 API 로 건넨다).

-- 지금 판. 내린 문서(retired)는 색인하지 않는다. read_only — 고객 RAG 에는 쓰지 않는다(모두 DB 가 정본, ADR-0094).
CREATE TABLE knowledge_document (
    document_id       varchar(300)  NOT NULL,
    domain_area       varchar(50)   NOT NULL,
    title             varchar(300)  NOT NULL,
    version           integer       NOT NULL,
    content           text          NOT NULL,
    content_sha256    char(64)      NOT NULL,
    retired           boolean       NOT NULL DEFAULT false,
    read_only         boolean       NOT NULL DEFAULT false,
    updated_at        timestamptz   NOT NULL,
    updated_by        varchar(128)  NOT NULL,      -- 반영한 승인의 요청자, 가져오기는 IMPORT
    last_approval_id  varchar(64),
    CONSTRAINT pk_knowledge_document PRIMARY KEY (document_id),
    CONSTRAINT ck_knowledge_document_version CHECK (version >= 1)
);

-- 판 이력(고치거나 지우지 않는다 — 되돌리기는 옛 판 본문으로 새 초안). op: IMPORT / UPSERT / RETIRE.
CREATE TABLE knowledge_document_version (
    document_id     varchar(300)  NOT NULL REFERENCES knowledge_document (document_id),
    version         integer       NOT NULL,
    op              varchar(10)   NOT NULL,
    title           varchar(300)  NOT NULL,
    content         text          NOT NULL,
    content_sha256  char(64)      NOT NULL,
    approval_id     varchar(64),
    draft_id        varchar(40),
    created_by      varchar(128)  NOT NULL,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT pk_knowledge_document_version PRIMARY KEY (document_id, version)
);

-- 초안: 만든 사람만 고치고 버린다. 승인 요청 뒤에도 고칠 수는 있지만 그러면 본문 해시가 달라져 반영되지 않는다(DRAFT_CHANGED).
-- state: OPEN / APPLIED / REJECTED / DISCARDED. (author_subject, request_id) = 만들기 멱등.
CREATE TABLE knowledge_draft (
    draft_id         varchar(40)   NOT NULL,
    document_id      varchar(300)  NOT NULL,
    op               varchar(10)   NOT NULL,      -- UPSERT / RETIRE
    base_version     integer       NOT NULL,      -- 새 문서 0
    title            varchar(300),
    content          text,
    content_sha256   char(64),
    author_subject   varchar(128)  NOT NULL,
    request_id       varchar(128)  NOT NULL,
    state            varchar(12)   NOT NULL,
    approval_id      varchar(64),                 -- 반영·거절한 승인
    result_reason    varchar(40),
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    CONSTRAINT pk_knowledge_draft PRIMARY KEY (draft_id),
    CONSTRAINT uq_knowledge_draft_request UNIQUE (author_subject, request_id)
);
CREATE INDEX ix_knowledge_draft_author ON knowledge_draft (author_subject, state, updated_at DESC);

-- 승인 하나의 반영 기록(멱등 — ApprovalDecided 를 다시 받으면 같은 회신만 다시 보낸다).
CREATE TABLE knowledge_application (
    approval_id   varchar(64)   NOT NULL,
    draft_id      varchar(40),
    document_id   varchar(300),
    result        varchar(10)   NOT NULL,       -- APPLIED / REJECTED
    reason        varchar(40),
    version       integer,                      -- 반영해서 생긴 판(REJECTED 면 그때 지금 판)
    approver_id   varchar(128),
    at            timestamptz   NOT NULL,
    CONSTRAINT pk_knowledge_application PRIMARY KEY (approval_id)
);

-- 재색인 요청(행 하나). requested_at > started_at 인 동안 1분 주기 작업이 DB 의 지금 판으로 새 스냅숏을 만든다.
CREATE TABLE knowledge_reindex (
    id               integer       NOT NULL DEFAULT 1,
    requested_at     timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    last_result      varchar(10),                -- OK / FAILED
    last_snapshot_id varchar(40),
    last_error       varchar(500),
    CONSTRAINT pk_knowledge_reindex PRIMARY KEY (id),
    CONSTRAINT ck_knowledge_reindex_one CHECK (id = 1)
);
INSERT INTO knowledge_reindex (id) VALUES (1);
