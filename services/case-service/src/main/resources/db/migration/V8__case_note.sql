-- 접수 건 메모(2026-11-16, 사용자 "고친 안" — 업무 원문 BO-27 "전임의 늦은 보고는 버리지 않고 원사건으로")
-- 덧붙이기만(고치기·지우기 없음). 관리자 가림은 글만 지우고 누가·언제·왜 가렸는지는 남긴다(ADR-0062 — 개인정보 실수 대비).
CREATE TABLE case_note (
    note_id      varchar(64)  PRIMARY KEY,
    case_id      varchar(64)  NOT NULL REFERENCES case_record (case_id),
    text         varchar(500),            -- 가리면 NULL. 로그 금지
    author_id    varchar(100) NOT NULL,
    created_at   timestamptz  NOT NULL,
    masked_at    timestamptz,
    masked_by    varchar(100),
    mask_reason  varchar(500)
);
CREATE INDEX ix_case_note_case ON case_note (case_id, created_at);
