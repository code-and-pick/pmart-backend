-- 지급 실패 → 다음 지급일로 자동 넘김(2026-11-24 사용자 "정상으로 지급해야 하는 건 … 무조건 줘야", "실패한 지급을 다음 지급일로 자동으로 넘겨").
-- 줄마다 마지막으로 넘어온 지급일·시각·넘긴 횟수, 묶음마다 넘김 기록(실패 결과·사유는 rider_payout_result).
ALTER TABLE rider_earning ADD COLUMN rolled_from date, ADD COLUMN rolled_at timestamptz, ADD COLUMN roll_count integer NOT NULL DEFAULT 0;
ALTER TABLE rider_compensation ADD COLUMN rolled_from date, ADD COLUMN rolled_at timestamptz, ADD COLUMN roll_count integer NOT NULL DEFAULT 0;
CREATE TABLE rider_payout_rollover (
    rollover_id        varchar(64)  PRIMARY KEY,
    rider_id           varchar(64)  NOT NULL,
    from_date          date         NOT NULL,
    to_date            date         NOT NULL,
    line_count         integer      NOT NULL,
    total              bigint       NOT NULL,
    failure_result_id  varchar(64),
    rolled_at          timestamptz  NOT NULL
);
CREATE INDEX ix_rider_payout_rollover_rider ON rider_payout_rollover (rider_id, rolled_at);
