-- 이의 인정 보상금(2026-11-19 사용자 "보상금 한 줄 따로", 원문 §7.1 "이후 보상·조정은 별도 기록"). 원래 수행료(rider_earning)는 그대로.
-- 지급 묶음(라이더 × 지급 예정일)에 수행료와 함께 들어간다.
CREATE TABLE rider_compensation (
    compensation_id   varchar(64)   PRIMARY KEY,
    request_key       varchar(200)  NOT NULL UNIQUE,
    appeal_id         varchar(64)   NOT NULL UNIQUE,
    delivery_id       varchar(64)   NOT NULL,
    rider_id          varchar(64)   NOT NULL,
    amount            integer       NOT NULL CHECK (amount > 0),
    reason            varchar(300)  NOT NULL,
    settlement_state  varchar(24)   NOT NULL CHECK (settlement_state IN ('CONFIRMED', 'PAYING', 'PAID', 'PAYMENT_FAILED')),
    payout_date       date          NOT NULL,
    recorded_by       varchar(64)   NOT NULL,
    recorded_at       timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL
);
CREATE INDEX ix_rider_compensation_rider ON rider_compensation (rider_id, payout_date);
