-- 0.3.0(2026-11-13, 사용자 결정): 전달 실패(라이더 등록 → 운영자 확정), 도움요청(기록 + DeliveryIncidentRaised), 수행료(수락 당시 금액).

-- 실패 등록 상태(확정 전 — 아직 진행 중)
ALTER TABLE execution_delivery DROP CONSTRAINT execution_delivery_state_check;
ALTER TABLE execution_delivery ADD CONSTRAINT execution_delivery_state_check CHECK (state IN ('UNASSIGNED',
    'ASSIGNED', 'ARRIVED_AT_STORE', 'PICKED_UP', 'ARRIVED_AT_CUSTOMER', 'FAILURE_REPORTED', 'COMPLETED', 'FAILED',
    'SUSPENDED', 'RETURN_IN_PROGRESS', 'RETURN_PICKED_UP', 'RETURNED_TO_STORE', 'CANCELLED'));

-- 수락 당시 금액·조건(dispatch DeliveryAssigned — 원문 §7.1 "제안·수락 당시 금액과 적용 조건 기록")
ALTER TABLE execution_delivery ADD COLUMN accepted_fee INT;
ALTER TABLE execution_delivery ADD COLUMN vehicle_type VARCHAR(32);
ALTER TABLE execution_delivery ADD COLUMN distance_m INT;
-- 실패 등록·결정(사유·연락 기록·사진 참조·상품 위치·되돌릴 상태·운영자 결정)
ALTER TABLE execution_delivery ADD COLUMN failure JSONB;

-- 도움요청(정본은 case-service 사건 — 여기는 어느 배송·어느 단계에서 요청했는지와 사건 연결)
CREATE TABLE help_request (
    request_id    VARCHAR(128) NOT NULL,
    delivery_id   VARCHAR(64)  NOT NULL,
    rider_id      VARCHAR(64)  NOT NULL,
    category      VARCHAR(32)  NOT NULL,
    detail        VARCHAR(500),
    evidence_refs JSONB        NOT NULL DEFAULT '[]',
    stage         VARCHAR(24)  NOT NULL,
    state         VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    case_id       VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_help_request PRIMARY KEY (delivery_id, request_id)
);

-- 수행료(배송당 하나 — 완료·실패·취소 때 만든다). 지급 실행은 외부 회계
CREATE TABLE rider_earning (
    earning_id         VARCHAR(64)  PRIMARY KEY,
    delivery_id        VARCHAR(64)  NOT NULL,
    rider_id           VARCHAR(64)  NOT NULL,
    calculable         BOOLEAN      NOT NULL,
    rate_table_version INT          NOT NULL,
    vehicle_type       VARCHAR(32),
    distance_m         INT,
    base_fee           INT,
    total              INT,
    settlement_state   VARCHAR(24)  NOT NULL CHECK (settlement_state IN ('ESTIMATED', 'PENDING_CONFIRMATION',
        'CONFIRMED', 'PAYING', 'PAID', 'PAYMENT_FAILED', 'ADJUSTED')),
    performed_on       DATE         NOT NULL,   -- 수행일(Asia/Seoul)
    confirm_at         TIMESTAMPTZ,             -- 확정 시각(완료 + 5시간)
    payout_date        DATE,                    -- 지급 예정일(수행일 + 3영업일)
    note               VARCHAR(200),
    recorded_at        TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);
-- 배송·라이더당 하나(픽업 전 배차 취소 뒤 다른 라이더가 맡으면 둘 다 남는다)
CREATE UNIQUE INDEX rider_earning_delivery_rider ON rider_earning (delivery_id, rider_id);
CREATE INDEX rider_earning_rider ON rider_earning (rider_id, performed_on DESC);
CREATE INDEX rider_earning_due ON rider_earning (confirm_at) WHERE settlement_state = 'PENDING_CONFIRMATION';
