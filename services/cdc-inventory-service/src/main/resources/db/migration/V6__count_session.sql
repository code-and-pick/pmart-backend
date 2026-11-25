-- 위치 실사·재계수·CNT 조정(0.8.0, 2026-11-25 — WMS CDC-F-029·030, 사용자 "MFC 실사와 같게"). 적용된 파일은 수정하지 않는다.
-- state: COUNTING → MATCHED / RECOUNT_REQUIRED → MATCHED / PENDING_APPROVAL → APPROVED / REJECTED
CREATE TABLE count_session (
    session_id          varchar(64)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    state               varchar(20)  NOT NULL,
    reason              varchar(300),
    started_by          varchar(128) NOT NULL,
    first_counter_id    varchar(128),
    recounter_id        varchar(128),
    decided_by          varchar(128),
    decision_reason     varchar(300),
    approval_ref        varchar(128),
    commit_id           varchar(64),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    decided_at          timestamptz,
    CONSTRAINT pk_count_session PRIMARY KEY (session_id),
    CONSTRAINT ck_count_session_state CHECK (state IN ('COUNTING', 'RECOUNT_REQUIRED', 'PENDING_APPROVAL', 'MATCHED', 'APPROVED', 'REJECTED'))
);

-- 실사 칸 — 열린(open) 동안 새 할당 후보에서 빠진다. 칸 하나에 열린 실사 하나.
CREATE TABLE count_location (
    session_id          varchar(64)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    open                boolean      NOT NULL,
    CONSTRAINT pk_count_location PRIMARY KEY (session_id, location_code)
);
CREATE UNIQUE INDEX uq_count_location_open ON count_location (cdc_id, location_code) WHERE open;

-- 장부 기준(시작 때 고정)과 계수. round 0 = 장부, 1 = 첫 계수, 2 = 재계수.
CREATE TABLE count_line (
    session_id          varchar(64)  NOT NULL,
    round               integer      NOT NULL,
    location_code       varchar(40)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    qty                 integer      NOT NULL,
    CONSTRAINT pk_count_line PRIMARY KEY (session_id, round, location_code, sku, lot),
    CONSTRAINT ck_count_line CHECK (round IN (0, 1, 2) AND qty >= 0)
);
