-- 고객 상담 메모(2026-11-23 R-13, 사용자 "상담사가 쓴다, 수정·삭제는 쓴 사람만").
-- 지우면 글은 NULL(ADR-0062), 누가·언제 지웠는지는 남긴다. 고객 ID 는 customer-service 값(FK 없음).
CREATE TABLE customer_note (
    note_id      varchar(64)   PRIMARY KEY,
    customer_id  varchar(64)   NOT NULL,
    text         varchar(1000),           -- 지우면 NULL. 로그 금지
    author_id    varchar(100)  NOT NULL,
    created_at   timestamptz   NOT NULL,
    edited_at    timestamptz,
    deleted_at   timestamptz
);
CREATE INDEX ix_customer_note_customer ON customer_note (customer_id, created_at DESC, note_id DESC) WHERE deleted_at IS NULL;
