-- 넘김 상한 사건을 줄마다 한 번(2026-11-25 검토 — 묶음마다 한 번이면 같은 묶음에서 나중에 상한을 다 쓴 줄은 알리지 않았다).
CREATE TABLE rider_payout_escalation_line (
    line_kind     varchar(12)  NOT NULL CHECK (line_kind IN ('EARNING', 'COMPENSATION')),
    line_id       varchar(64)  NOT NULL,
    rider_id      varchar(64)  NOT NULL,
    payout_date   date         NOT NULL,
    escalated_at  timestamptz  NOT NULL,
    PRIMARY KEY (line_kind, line_id)
);

-- 이미 알린 묶음의 남은 실패 줄은 알린 것으로(다시 사건을 내지 않게).
INSERT INTO rider_payout_escalation_line (line_kind, line_id, rider_id, payout_date, escalated_at)
SELECT 'EARNING', e.earning_id, e.rider_id, e.payout_date, x.escalated_at
FROM rider_earning e JOIN rider_payout_escalation x ON x.rider_id = e.rider_id AND x.payout_date = e.payout_date
WHERE e.settlement_state = 'PAYMENT_FAILED';
INSERT INTO rider_payout_escalation_line (line_kind, line_id, rider_id, payout_date, escalated_at)
SELECT 'COMPENSATION', c.compensation_id, c.rider_id, c.payout_date, x.escalated_at
FROM rider_compensation c JOIN rider_payout_escalation x ON x.rider_id = c.rider_id AND x.payout_date = c.payout_date
WHERE c.settlement_state = 'PAYMENT_FAILED';
