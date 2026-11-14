-- 계정 제한 이력(RS-1, 2026-11-14 — rider-service.yaml 0.3.0). 원문 §9.6 "사유·기간·통지·소명 결과 기록".
-- 지금 제한은 rider.restriction·restriction_reason·restriction_until, 바꿀 때마다 여기에 한 줄.
-- 사유에 개인정보를 적지 않는다(운영자 입력 — API 설명). 증빙은 참조 ID 만.
CREATE TABLE rider_restriction_history (
    request_id     varchar(128) NOT NULL,
    rider_id       varchar(64)  NOT NULL,
    restriction    varchar(24)  NOT NULL,
    previous       varchar(24)  NOT NULL,
    reason         varchar(300) NOT NULL,
    until_at       timestamptz,
    evidence_refs  text         NOT NULL DEFAULT '[]', -- JSON 배열(문자열)
    approval_ref   varchar(100),
    changed_by     varchar(64)  NOT NULL,
    changed_at     timestamptz  NOT NULL,
    rider_version  integer      NOT NULL,
    CONSTRAINT pk_rider_restriction_history PRIMARY KEY (request_id, rider_id),
    CONSTRAINT fk_rider_restriction_history FOREIGN KEY (rider_id) REFERENCES rider (rider_id),
    CONSTRAINT ck_rider_restriction_history CHECK (restriction IN ('NORMAL', 'DISPATCH_RESTRICTED', 'SUSPENDED', 'TERMINATED'))
);
CREATE INDEX ix_rider_restriction_history_rider ON rider_restriction_history (rider_id, changed_at DESC);
