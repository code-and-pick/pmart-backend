-- 라이더 휴대폰 번호 확인(2026-11-19 — 사용자 "옥토모", 라이더 앱 요청 R2)·탈퇴 번호 재가입 막기(R4, 사용자 "30일 동안 막기").
-- 번호 원문은 두지 않는다(해시) — 확인 때 라이더가 같은 번호를 다시 보낸다.
CREATE TABLE rider_phone_verification (
    verification_id  varchar(64)  PRIMARY KEY,
    subject          varchar(64)  NOT NULL,   -- 라이더 주체(RD-…, 가입 전이어도)
    phone_hash       char(64)     NOT NULL,
    code             char(6)      NOT NULL,
    state            varchar(16)  NOT NULL CHECK (state IN ('PENDING', 'VERIFIED', 'FAILED', 'EXPIRED')),
    attempts         integer      NOT NULL DEFAULT 0,
    failure_reason   varchar(32),
    expires_at       timestamptz  NOT NULL,
    created_at       timestamptz  NOT NULL,
    finished_at      timestamptz
);
CREATE INDEX ix_rider_phone_verification_subject ON rider_phone_verification (subject, created_at);

CREATE TABLE rider_rejoin_block (
    phone_hash     char(64)     PRIMARY KEY,
    blocked_until  timestamptz  NOT NULL,
    created_at     timestamptz  NOT NULL
);
