-- 오배송 재발송 사가(2026-11-16 — ADR-0052 결정 4·5, 사용자 "새상품 재배차 서비스 넣어" → "전부 PR로"). 오배송 확인 사건(case
-- MisdeliveryConfirmed, platform.case.v1)마다 하나.
-- RESOLVE_ORDER(배송 → 주문·센터·보낼 줄) → RECORD_RESHIPMENT(order 수량 원장 RESHIPMENT) → RESERVE_INVENTORY(재고 예약 — 모자란 SKU 는
-- 가용만큼 줄여 다시) → CONFIRM_RESERVATION → REQUEST_WORK(mfc-fulfillment 재발송 작업 지시 kind RESHIPMENT) → APPLY_SHORTAGE(못
-- 보내는 줄 order SHORTAGE) → REQUEST_REFUND(claim 부분 환불) → COMPLETED. 업무 거절은 ESCALATED(수동).
-- lines/reserved/short: [{orderLineId, sku, qty, productName?}] — 보낼 줄 / 예약된 줄 / 재고 없어 못 보내는 줄.
CREATE TABLE reshipment_saga (
    saga_id            VARCHAR(64)  PRIMARY KEY,
    case_id            VARCHAR(64)  NOT NULL,
    delivery_id        VARCHAR(64)  NOT NULL,
    order_id           VARCHAR(64),
    mfc_id             VARCHAR(32),
    shipment_id        VARCHAR(80),
    lines              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    reserved           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    short_lines        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    reserve_attempt    INT          NOT NULL DEFAULT 0,
    reservation_id     VARCHAR(64),
    fulfillment_id     VARCHAR(64),
    claim_id           VARCHAR(64),
    state              VARCHAR(16)  NOT NULL,       -- RUNNING / COMPLETED / ESCALATED
    current_step       VARCHAR(24)  NOT NULL,
    last_error_step    VARCHAR(24),
    last_error_code    VARCHAR(64),
    last_error_message VARCHAR(500),
    confirmed_at       TIMESTAMPTZ,                 -- 오배송 확인 시각(사건)
    started_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ,
    CONSTRAINT uq_reshipment_saga_case UNIQUE (case_id),
    CONSTRAINT ck_reshipment_saga_state CHECK (state IN ('RUNNING', 'COMPLETED', 'ESCALATED'))
);
CREATE INDEX ix_reshipment_saga_open ON reshipment_saga (updated_at) WHERE state = 'RUNNING';
CREATE INDEX ix_reshipment_saga_order ON reshipment_saga (order_id);
