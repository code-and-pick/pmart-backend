-- 라이더 공지(2026-11-18 — 사용자 "대상 고르기 + 알림함·푸시"). 받는 라이더마다 알림함 한 줄(template RIDER_NOTICE) + 휴대폰 푸시.
CREATE TABLE rider_notice (
    notice_id        varchar(64)   PRIMARY KEY,
    request_key      varchar(200)  NOT NULL UNIQUE,  -- 보낸 직원 + Idempotency-Key — 같은 요청은 한 번만
    title            varchar(40)   NOT NULL,
    body             varchar(500)  NOT NULL,
    target_type      varchar(16)   NOT NULL,         -- ALL / MFC
    mfc_id           varchar(40),
    sent_by          varchar(64)   NOT NULL,
    sent_at          timestamptz   NOT NULL,
    recipient_count  integer       NOT NULL
);
CREATE INDEX ix_rider_notice_sent ON rider_notice (sent_at DESC);
