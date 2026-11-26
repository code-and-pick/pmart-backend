-- 리콜(cdc-quality 0.3.0, 2026-11-26 — WMS CDC-F-028·정책 16.13, 사용자 "본사 승인 번호 + 모든 CDC"·"리콜은 출고대기분도 막음"). 적용된 파일은 수정하지 않는다.
CREATE TABLE recall (
    recall_id           varchar(40)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    reason              varchar(32)  NOT NULL,
    severity            varchar(16),
    approval_ref        varchar(128) NOT NULL,
    external_ref        varchar(128),
    note                varchar(1000),
    state               varchar(16)  NOT NULL,          -- TRACING / ISSUED
    issued_by           varchar(64)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_recall PRIMARY KEY (recall_id)
);
CREATE INDEX ix_recall_sku ON recall (sku, recall_id);

CREATE TABLE recall_lot (
    recall_id           varchar(40)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    CONSTRAINT pk_recall_lot PRIMARY KEY (recall_id, lot)
);

-- 리콜이 건 센터·LOT 별 보류(한 번)
CREATE TABLE recall_center (
    recall_id           varchar(40)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    hold_id             varchar(40)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_recall_center PRIMARY KEY (recall_id, cdc_id, lot)
);
