-- 고객·직원 ETA 조회·실시간 구독(TR-3, 2026-11-17 — 사용자 "PC 5에 요청").
-- delivery_progress: 배송 진행 단계 사본(delivery.delivery.v1 — dispatch 생성·배정·취소, execution 도착·픽업·완료·실패). 주문·센터는 접근 판정용,
-- 고객 소유는 order 내부 조회로 확인한다(저장하지 않음). 단계는 앞으로만 간다(픽업 전 배정 해제만 UNASSIGNED 로 되돌림). DONE 뒤 보존 기간이 지나면 지운다.
CREATE TABLE delivery_progress (
    delivery_id    varchar(64)  NOT NULL,
    order_id       varchar(64),
    mfc_id         varchar(40),
    stage          varchar(16)  NOT NULL,
    outcome        varchar(16),
    arrival_date   date,
    arrival_start  varchar(5),
    arrival_end    varchar(5),
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_delivery_progress PRIMARY KEY (delivery_id),
    CONSTRAINT ck_delivery_progress_stage CHECK (stage IN ('UNASSIGNED', 'TO_STORE', 'AT_STORE', 'TO_CUSTOMER', 'AT_CUSTOMER', 'DONE'))
);
CREATE INDEX ix_delivery_progress_done ON delivery_progress (updated_at) WHERE stage = 'DONE';

-- 픽업 전 도착 예정(직원용 pickupEta). 도착지 예정은 eta_drop(V4).
ALTER TABLE rider_active_delivery ADD COLUMN eta_pickup timestamptz;
