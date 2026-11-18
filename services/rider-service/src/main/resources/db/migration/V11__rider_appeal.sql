-- 라이더 소명·이의 신청(2026-11-18 사용자 "앱에서 신청 → 백오피스 결정", 원문 §9.6). 대상마다 한 번.
CREATE TABLE rider_appeal (
    appeal_id      varchar(64)    PRIMARY KEY,
    request_key    varchar(200)   NOT NULL UNIQUE,
    rider_id       varchar(64)    NOT NULL REFERENCES rider (rider_id),
    target_type    varchar(16)    NOT NULL CHECK (target_type IN ('RESTRICTION', 'EARNING')),
    target_ref     varchar(128)   NOT NULL,
    restriction    varchar(24),
    body           varchar(1000)  NOT NULL,
    state          varchar(16)    NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'UPHELD', 'ACCEPTED')),
    submitted_at   timestamptz    NOT NULL,
    decided_by     varchar(64),
    decided_at     timestamptz,
    decision_note  varchar(300),
    CONSTRAINT uq_rider_appeal_target UNIQUE (rider_id, target_type, target_ref)
);
CREATE INDEX ix_rider_appeal_state ON rider_appeal (state, submitted_at);
