-- 지급 넘김 상한(2026-11-24 사용자 "넘기는 횟수에 상한을 둬. 3번 안에는 반드시 줘야해") — 넘김 기록에 넘긴 뒤 가장 큰 넘김 횟수·마지막 자동 시도 여부.
ALTER TABLE rider_payout_rollover ADD COLUMN roll_count integer NOT NULL DEFAULT 0, ADD COLUMN final_attempt boolean NOT NULL DEFAULT false;
-- 상한을 다 쓰고 또 실패한 줄(PAYMENT_FAILED 로 남음 — 직원 직접 지급) 찾기.
CREATE INDEX ix_rider_earning_failed ON rider_earning (rider_id, payout_date) WHERE settlement_state = 'PAYMENT_FAILED';

-- 직원 직접 차감(2026-11-24 사용자 "직접 차감하는 API도 만들자").
ALTER TABLE rider_deduction
    ADD COLUMN source varchar(24) NOT NULL DEFAULT 'MISDELIVERY_RECOVERY' CHECK (source IN ('MISDELIVERY_RECOVERY', 'MANUAL')),
    ADD COLUMN reason varchar(300),
    ADD COLUMN created_by varchar(64),
    ADD COLUMN create_key varchar(200) UNIQUE;
