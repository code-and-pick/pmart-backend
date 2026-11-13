-- 배차 취소(픽업 전 재배차 / 픽업 후 지원센터 — 원문 §5.4)와 배차 지연 안내(원문 §3.6, 2026-11-13).
CREATE TABLE assignment_cancellation (
    request_id          varchar(128) NOT NULL,
    delivery_id         varchar(64)  NOT NULL,
    requested_by        varchar(16)  NOT NULL,   -- RIDER / STORE_STAFF / OPERATOR / ORCHESTRATION
    actor               varchar(64)  NOT NULL,
    reason              varchar(24)  NOT NULL,
    reason_detail       varchar(300),
    reassign            boolean      NOT NULL,
    approval_ref        varchar(128),
    state               varchar(32)  NOT NULL,   -- CANCELLED_BEFORE_PICKUP / REJECTED_PICKED_UP
    previous_rider_id   varchar(64),
    reassign_attempt_id varchar(64),
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_assignment_cancellation PRIMARY KEY (request_id, delivery_id)
);

-- 재배차 시도에서 방금 취소한 라이더는 빼고 찾는다
ALTER TABLE dispatch_attempt ADD COLUMN excluded_rider_id varchar(64);

-- 배차 지연 안내(DispatchDelayed)를 한 번만
ALTER TABLE delivery ADD COLUMN delay_alerted_at timestamptz;
