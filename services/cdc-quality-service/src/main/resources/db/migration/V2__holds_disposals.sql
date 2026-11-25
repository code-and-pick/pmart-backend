-- 품질 보류·해제·폐기(cdc-quality 0.2.0, 2026-11-26 — WMS CDC-F-028, 사용자 "보류·해제·폐기만"·"MFC 품질과 같게"). 적용된 파일은 수정하지 않는다.
-- 수량 정본은 cdc-inventory — 여기는 판단·승인 기록과 재고 반영 결과(commitId·반영 수량)만.
CREATE TABLE quality_hold (
    hold_id             varchar(40)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    reason              varchar(32)  NOT NULL,
    source_ref          varchar(128),
    evidence_ref        varchar(128),
    note                varchar(1000),
    state               varchar(24)  NOT NULL,          -- APPLYING / UNKNOWN_RESULT / APPLIED / REJECTED / PARTIALLY_RELEASED / CLOSED
    requested_by        varchar(64)  NOT NULL,
    reject_reason       varchar(300),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_quality_hold PRIMARY KEY (hold_id)
);
CREATE INDEX ix_quality_hold_cdc ON quality_hold (cdc_id, hold_id);

CREATE TABLE quality_hold_line (
    hold_id             varchar(40)  NOT NULL,
    line_no             integer      NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40),
    requested_qty       integer,
    held_qty            integer      NOT NULL DEFAULT 0,
    released_qty        integer      NOT NULL DEFAULT 0,
    disposed_qty        integer      NOT NULL DEFAULT 0,
    commit_id           varchar(64),
    state               varchar(16)  NOT NULL,          -- PENDING / UNKNOWN_RESULT / APPLIED / REJECTED
    reject_reason       varchar(300),
    CONSTRAINT pk_quality_hold_line PRIMARY KEY (hold_id, line_no),
    CONSTRAINT ck_quality_hold_line_qty CHECK (held_qty >= 0 AND released_qty >= 0 AND disposed_qty >= 0
        AND released_qty + disposed_qty <= held_qty)
);

-- 해제 요청(줄 = sku·lot·locationCode·qty, JSON)
CREATE TABLE hold_release (
    release_id          varchar(40)  NOT NULL,
    hold_id             varchar(40)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    state               varchar(16)  NOT NULL,          -- REQUESTED / APPLYING / UNKNOWN_RESULT / APPLIED / REJECTED / DECLINED
    requested_by        varchar(64)  NOT NULL,
    note                varchar(500),
    decided_by          varchar(64),
    decision_reason     varchar(500),
    approval_ref        varchar(128),
    commit_id           varchar(64),
    reject_reason       varchar(300),
    lines               text         NOT NULL,
    created_at          timestamptz  NOT NULL,
    decided_at          timestamptz,
    CONSTRAINT pk_hold_release PRIMARY KEY (release_id)
);
CREATE INDEX ix_hold_release_hold ON hold_release (hold_id);

-- 폐기 요청·승인(줄 = sku·lot·locationCode·approvedQty·executedQty, JSON)
CREATE TABLE disposal (
    disposal_id         varchar(40)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    hold_id             varchar(40)  NOT NULL,
    reason              varchar(32)  NOT NULL,
    note                varchar(500),
    state               varchar(24)  NOT NULL,          -- REQUESTED / APPROVED / DECLINED / PARTIALLY_EXECUTED / COMPLETED
    requested_by        varchar(64)  NOT NULL,
    decided_by          varchar(64),
    decision_reason     varchar(500),
    approval_ref        varchar(128),
    lines               text         NOT NULL,
    created_at          timestamptz  NOT NULL,
    decided_at          timestamptz,
    CONSTRAINT pk_disposal PRIMARY KEY (disposal_id)
);
CREATE INDEX ix_disposal_cdc ON disposal (cdc_id, disposal_id);
CREATE INDEX ix_disposal_hold ON disposal (hold_id);

-- 폐기 실행(실제 폐기 수량·증빙) — 재고 DISPOSE 한 번
CREATE TABLE disposal_execution (
    execution_id        varchar(40)  NOT NULL,
    disposal_id         varchar(40)  NOT NULL,
    state               varchar(16)  NOT NULL,          -- APPLYING / UNKNOWN_RESULT / APPLIED / REJECTED
    evidence_ref        varchar(128) NOT NULL,
    note                varchar(500),
    executed_by         varchar(64)  NOT NULL,
    commit_id           varchar(64),
    reject_reason       varchar(300),
    lines               text         NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_disposal_execution PRIMARY KEY (execution_id)
);
CREATE INDEX ix_disposal_execution_disposal ON disposal_execution (disposal_id);
