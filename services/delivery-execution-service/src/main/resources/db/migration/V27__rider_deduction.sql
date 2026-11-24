-- 오배송 정산 차감(2026-11-24, ADR-0086 — DE-9, 사용자 "하루 지나면 회수 배송 포기하고 라이더 귀책으로 고객 주문금액 만큼 정산금 차감").
-- 귀책 사본: case MisdeliveryConfirmed 의 fault(사건마다 처음 받은 값). 회수 포기(RECOVERY_ABANDONED) 때 RIDER 면 차감 예정을 만든다.
CREATE TABLE misdelivery_fault (
    case_id             varchar(64)  PRIMARY KEY,
    origin_delivery_id  varchar(64)  NOT NULL,
    rider_id            varchar(64)  NOT NULL,
    fault               varchar(16)  NOT NULL,
    recorded_at         timestamptz  NOT NULL
);

-- 차감 한 줄 = 오배송 사건 하나. 이의 5일 → 확정·취소. remaining = 아직 지급 묶음에서 빼지 않은 금액(이월, 상한 없음).
CREATE TABLE rider_deduction (
    deduction_id          varchar(64)   PRIMARY KEY,
    case_id               varchar(64)   NOT NULL UNIQUE,
    origin_delivery_id    varchar(64)   NOT NULL,
    delivery_number       varchar(32),
    recovery_delivery_id  varchar(64),
    order_id              varchar(64)   NOT NULL,
    rider_id              varchar(64)   NOT NULL,
    amount                integer       NOT NULL CHECK (amount > 0),
    remaining             integer       NOT NULL CHECK (remaining >= 0),
    state                 varchar(24)   NOT NULL CHECK (state IN ('PENDING_OBJECTION', 'OBJECTED', 'CONFIRMED', 'CANCELLED')),
    objection_deadline    timestamptz   NOT NULL,
    objection_note        varchar(500),
    objected_at           timestamptz,
    objection_key         varchar(200),
    decision              varchar(8),
    decision_note         varchar(500),
    decided_by            varchar(64),
    decided_at            timestamptz,
    decision_key          varchar(200),
    confirmed_at          timestamptz,
    created_at            timestamptz   NOT NULL,
    updated_at            timestamptz   NOT NULL
);
CREATE INDEX ix_rider_deduction_rider ON rider_deduction (rider_id, state);
CREATE INDEX ix_rider_deduction_due ON rider_deduction (objection_deadline) WHERE state = 'PENDING_OBJECTION';

-- 지급 묶음(라이더 × 지급 예정일)에서 뺀 기록 — 묶음이 지급 중·지급됨이 될 때 한 번.
CREATE TABLE rider_deduction_application (
    deduction_id  varchar(64)  NOT NULL REFERENCES rider_deduction (deduction_id),
    rider_id      varchar(64)  NOT NULL,
    payout_date   date         NOT NULL,
    amount        integer      NOT NULL CHECK (amount > 0),
    applied_at    timestamptz  NOT NULL,
    PRIMARY KEY (deduction_id, payout_date)
);
CREATE INDEX ix_rider_deduction_application_batch ON rider_deduction_application (rider_id, payout_date);
