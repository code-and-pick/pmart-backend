-- 엘리베이터 고장 추가 배달료(2026-11-17, 0.19.0 — 원문 §9.2, 사용자 "다른 사유로 추가금을 주진 않음").
-- 15층부터 5층 단위 500원(서버가 층으로 계산). 승인하면 rider_earning.total 에 더한다. 배송마다 열린·승인된 신청 하나.
CREATE TABLE surcharge_request (
    request_id     varchar(64)   NOT NULL,
    delivery_id    varchar(64)   NOT NULL,
    rider_id       varchar(64)   NOT NULL,
    earning_id     varchar(64)   NOT NULL,
    reason         varchar(32)   NOT NULL,
    floor          integer       NOT NULL,
    amount         integer       NOT NULL,
    evidence_refs  jsonb         NOT NULL,
    state          varchar(16)   NOT NULL,
    requested_at   timestamptz   NOT NULL,
    decided_by     varchar(64),
    decided_at     timestamptz,
    decision_note  varchar(300),
    CONSTRAINT pk_surcharge_request PRIMARY KEY (request_id),
    CONSTRAINT ck_surcharge_request_state CHECK (state IN ('REQUESTED', 'APPROVED', 'REJECTED'))
);
CREATE UNIQUE INDEX ux_surcharge_request_open ON surcharge_request (delivery_id) WHERE state IN ('REQUESTED', 'APPROVED');
CREATE INDEX ix_surcharge_request_state ON surcharge_request (state, requested_at);
