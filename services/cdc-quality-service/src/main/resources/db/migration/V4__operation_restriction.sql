-- 운영 제한(cdc-quality 0.4.0, 2026-11-26 — WMS CDC-F-031, 사용자 "새 할당과 출발"·"문서 그대로"). 자동 해제 없음. 적용된 파일은 수정하지 않는다.
CREATE TABLE operation_restriction (
    restriction_id      varchar(40)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    activities          varchar(64)  NOT NULL,          -- 쉼표로: ALLOCATION,DEPARTURE
    temperature_zone    varchar(16),                    -- 비면 모든 온도대
    skus                text,                           -- 쉼표로, 비면 모든 SKU
    reason              varchar(16)  NOT NULL,          -- CONGESTION / SAFETY / HQ / QUALITY
    approval_ref        varchar(128),
    planned_until       timestamptz,
    note                varchar(1000),
    state               varchar(8)   NOT NULL,          -- ACTIVE / LIFTED
    applied_by          varchar(64)  NOT NULL,
    applied_at          timestamptz  NOT NULL,
    lifted_by           varchar(64),
    lift_approval_ref   varchar(128),
    lift_note           varchar(500),
    lifted_at           timestamptz,
    CONSTRAINT pk_operation_restriction PRIMARY KEY (restriction_id)
);
CREATE INDEX ix_operation_restriction_active ON operation_restriction (cdc_id, state);
