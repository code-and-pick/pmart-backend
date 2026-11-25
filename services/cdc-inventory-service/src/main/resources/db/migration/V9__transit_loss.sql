-- 운송 손실 승인(0.14.0, 2026-11-26 — WMS CDC-F-027, 사용자 "MFC 입고 종결 확인을 받은 뒤에만"·"실사 조정과 같은 본사 기준"). 적용된 파일은 수정하지 않는다.
-- MFC 가 그 이관 입고를 "더 받을 것 없음"으로 종결한 사실 — TRF 하나에 한 번. 손실을 받는 조건.
CREATE TABLE transit_closure (
    trf_id              varchar(64)  NOT NULL,
    departure_id        varchar(64)  NOT NULL,
    mfc_id              varchar(20)  NOT NULL,
    closed_at           timestamptz  NOT NULL,
    source_ref          varchar(128),
    source_system       varchar(64)  NOT NULL,
    body_hash           varchar(64)  NOT NULL,
    recorded_at         timestamptz  NOT NULL,
    CONSTRAINT pk_transit_closure PRIMARY KEY (trf_id)
);

-- 반영한 운송 손실(감사·조회). 수량 자체는 transit_line.loss_qty 가 정본.
CREATE TABLE transit_loss (
    loss_id             varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    cdc_id              varchar(20)  NOT NULL,
    loss_qty            integer      NOT NULL,
    lines               text         NOT NULL,
    reason              varchar(300) NOT NULL,
    incident_ref        varchar(128),
    approval_ref        varchar(128),
    commit_id           varchar(64)  NOT NULL,
    actor_id            varchar(64)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_transit_loss PRIMARY KEY (loss_id),
    CONSTRAINT ck_transit_loss_qty CHECK (loss_qty > 0)
);
CREATE INDEX ix_transit_loss_trf ON transit_loss (trf_id);
