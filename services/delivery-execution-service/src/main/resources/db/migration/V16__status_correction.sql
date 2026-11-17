-- 운영자 상태 정정(2026-11-17, 0.17.0 — 원문 §9.3·§6.4 "확인 근거·처리자·시각 기록", 사용자 "라이더 운영 관리자가 승인").
-- 이력(execution_history)은 OPERATOR·정정 ID 로 남고, 사유·근거는 여기에(직원용 — 로그 금지).
CREATE TABLE delivery_status_correction (
    correction_id  varchar(64)   NOT NULL,
    delivery_id    varchar(64)   NOT NULL,
    from_state     varchar(32)   NOT NULL,
    to_state       varchar(32)   NOT NULL,
    reason         varchar(500)  NOT NULL,
    evidence_refs  jsonb         NOT NULL DEFAULT '[]'::jsonb,
    corrected_by   varchar(64)   NOT NULL,
    corrected_at   timestamptz   NOT NULL,
    CONSTRAINT pk_delivery_status_correction PRIMARY KEY (correction_id)
);
CREATE INDEX ix_delivery_status_correction_delivery ON delivery_status_correction (delivery_id, corrected_at);
