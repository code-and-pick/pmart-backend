-- MFC 수령·운송 중 재고 대사(0.11.0, 2026-11-25 — WMS CDC-F-024·025, 설계 2026-11-25-receipt-transit.md). 적용된 파일은 수정하지 않는다.
-- 미판정(pending)도 받은 수량(사용자) — 출발 줄에 칸을 더한다. q = 정상 + 격리 + 미판정 + 손실 + 미정산.
ALTER TABLE transit_line ADD COLUMN pending_qty integer NOT NULL DEFAULT 0;
ALTER TABLE transit_line DROP CONSTRAINT ck_transit_line_qty;
ALTER TABLE transit_line ADD CONSTRAINT ck_transit_line_qty CHECK (
    q > 0 AND normal_qty >= 0 AND quarantined_qty >= 0 AND pending_qty >= 0 AND loss_qty >= 0
    AND normal_qty + quarantined_qty + pending_qty + loss_qty <= q);

-- MFC 수령 확정 하나 = receiptId 하나(한 번). state: APPLIED(줄 일부 CONFLICT 가능) / PENDING_DEPARTURE(TOUT 전에 옴 — TOUT 때 처리)
CREATE TABLE transit_receipt (
    receipt_id          varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    departure_id        varchar(64)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    mfc_commit_id       varchar(64),
    body_hash           varchar(64)  NOT NULL,
    body                text         NOT NULL,          -- 줄 원문(대기 처리·재생용 JSON)
    state               varchar(24)  NOT NULL,
    commit_id           varchar(64),
    received_qty        integer      NOT NULL,
    applied_qty         integer      NOT NULL DEFAULT 0,
    conflicts           text,                           -- 보류 줄 JSON
    created_at          timestamptz  NOT NULL,
    applied_at          timestamptz,
    CONSTRAINT pk_transit_receipt PRIMARY KEY (receipt_id),
    CONSTRAINT ck_transit_receipt_state CHECK (state IN ('APPLIED', 'PENDING_DEPARTURE'))
);
CREATE INDEX ix_transit_receipt_pending ON transit_receipt (trf_id) WHERE state = 'PENDING_DEPARTURE';
