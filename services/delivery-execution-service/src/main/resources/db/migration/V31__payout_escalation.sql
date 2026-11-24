-- 지급 넘김 상한을 다 쓴 묶음(2026-11-24 DE-10 — RiderPayoutEscalated 를 묶음마다 한 번).
CREATE TABLE rider_payout_escalation (
    rider_id      varchar(64)  NOT NULL,
    payout_date   date         NOT NULL,
    line_count    integer      NOT NULL,
    amount        bigint       NOT NULL,
    roll_count    integer      NOT NULL,
    escalated_at  timestamptz  NOT NULL,
    PRIMARY KEY (rider_id, payout_date)
);
