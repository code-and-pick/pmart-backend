-- 수행료 지급 결과(2026-11-18 사용자 "결과 받는 창구 + 수동 표시"). 외부 회계(서비스) 또는 관리자가 라이더 × 지급 예정일 묶음 단위로 남긴다.
CREATE TABLE rider_payout_result (
    result_id       varchar(64)   PRIMARY KEY,
    request_key     varchar(200)  NOT NULL UNIQUE,
    rider_id        varchar(64)   NOT NULL,
    payout_date     date          NOT NULL,
    result          varchar(16)   NOT NULL CHECK (result IN ('PAYING', 'PAID', 'FAILED')),
    reference       varchar(100),
    failure_reason  varchar(300),
    earning_count   integer       NOT NULL,
    total           integer       NOT NULL,
    recorded_by     varchar(64)   NOT NULL,
    recorded_at     timestamptz   NOT NULL
);
CREATE INDEX ix_rider_payout_result_batch ON rider_payout_result (rider_id, payout_date, recorded_at DESC);
