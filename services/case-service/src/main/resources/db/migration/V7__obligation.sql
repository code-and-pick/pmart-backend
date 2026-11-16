-- 사건 의무(2026-11-16, 계약 0.8.0 — 사용자 "고친 안으로 진행해 줘", 업무 원문 §2 Obligation·§5.3)
-- 사건 안에 여러 의무. 수량과 금액은 따로(합치지 않음). 해소는 원결과 근거와 함께 이력으로 남긴다.
CREATE TABLE case_obligation (
    obligation_id    varchar(64)  PRIMARY KEY,
    case_id          varchar(64)  NOT NULL REFERENCES case_record (case_id),
    kind             varchar(40)  NOT NULL,
    owner            varchar(40)  NOT NULL,   -- 담당 팀: DELIVERY_OPS·ORDER_OPS·CS
    target_ref       varchar(200),
    qty              integer CHECK (qty IS NULL OR qty >= 0),
    amount           bigint  CHECK (amount IS NULL OR amount >= 0),  -- KRW
    due_at           timestamptz,
    note             varchar(1000),           -- 직원 메모 — 로그 금지
    state            varchar(30)  NOT NULL,   -- OPEN·PARTIALLY_RESOLVED·RESOLVED·WAIVED·FAILED
    resolved_qty     integer NOT NULL DEFAULT 0,
    resolved_amount  bigint  NOT NULL DEFAULT 0,
    version          integer NOT NULL,
    created_by       varchar(100) NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL
);
CREATE INDEX ix_case_obligation_case ON case_obligation (case_id);
CREATE INDEX ix_case_obligation_open_owner ON case_obligation (owner, created_at)
    WHERE state IN ('OPEN', 'PARTIALLY_RESOLVED');

CREATE TABLE case_obligation_resolution (
    id               bigserial    PRIMARY KEY,
    obligation_id    varchar(64)  NOT NULL REFERENCES case_obligation (obligation_id),
    outcome          varchar(20)  NOT NULL,   -- FULFILLED·WAIVED·FAILED
    qty              integer,
    amount           bigint,
    evidence_system  varchar(100) NOT NULL,
    evidence_ref     varchar(200) NOT NULL,
    evidence_kind    varchar(20),
    evidence_as_of   timestamptz,
    note             varchar(1000),
    resolved_by      varchar(100) NOT NULL,
    resolved_at      timestamptz  NOT NULL
);
CREATE INDEX ix_case_obligation_resolution_obl ON case_obligation_resolution (obligation_id, resolved_at);
