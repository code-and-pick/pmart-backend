-- 탈퇴 뒤 부정 이용 방지 보관(2026-11-20 사용자 "이름이나 전화번호를 5분 보관", 처리방침 §3) — 재가입 막는 기간(rejoin-block)과 같은 기간만
-- 이름·전화를 따로 두고, 기한이 지나면 주기 작업(WithdrawalHoldPurger)이 지운다. rider 행은 탈퇴 때 바로 비운다(재가입 번호 겹침 없음).
CREATE TABLE rider_withdrawal_hold (
    rider_id     varchar(64)  PRIMARY KEY,
    name         varchar(50),
    phone        varchar(20),
    held_until   timestamptz  NOT NULL,
    created_at   timestamptz  NOT NULL
);
CREATE INDEX ix_rider_withdrawal_hold_until ON rider_withdrawal_hold (held_until);
