-- 오배송 고객 잘못 다시 배송 9,000원 결제 대기(2026-11-16, ADR-0065 4단계). dispatch RedeliveryPaymentRequired 로 시작.
-- 단계: PREPARE_PAYMENT(결제 시도 생성) → AWAIT_PAYMENT(승인·기한 30분·고객 거절) → RELEASE_DELIVERY(결제됨 → dispatch 배차 풀기)
--       또는 CONVERT_TO_RECOVERY(기한·거절 → dispatch 회수로 바꾸기 + 재발송 사가 CUSTOMER_REFUND) → COMPLETED.
-- 기한 뒤 늦은 승인은 CANCEL_LATE_PAYMENT(보상 취소). outcome: PAID / DECLINED / TIMED_OUT. 주소·좌표 없음.
CREATE TABLE redelivery_payment (
    saga_id            VARCHAR(64)  PRIMARY KEY,
    case_id            VARCHAR(64)  NOT NULL,
    delivery_id        VARCHAR(64)  NOT NULL,       -- 다시 배송(dispatch)
    origin_delivery_id VARCHAR(64),                 -- 오배송된 원래 배송(회수·환불 사가가 쓴다)
    order_id           VARCHAR(64)  NOT NULL,
    account_id         VARCHAR(64),                 -- 주문 소유자(결제 customerRef·고객 조회 본인 확인)
    amount             BIGINT       NOT NULL,
    state              VARCHAR(16)  NOT NULL,       -- RUNNING / COMPLETED / ESCALATED
    current_step       VARCHAR(24)  NOT NULL,
    outcome            VARCHAR(16),
    payment_attempt_id VARCHAR(64),
    attempt_no         INTEGER      NOT NULL DEFAULT 0,
    deadline_at        TIMESTAMPTZ  NOT NULL,
    last_error_step    VARCHAR(24),
    last_error_code    VARCHAR(64),
    last_error_message VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ,
    CONSTRAINT uq_redelivery_payment_case UNIQUE (case_id),
    CONSTRAINT ck_redelivery_payment_state CHECK (state IN ('RUNNING', 'COMPLETED', 'ESCALATED')),
    CONSTRAINT ck_redelivery_payment_outcome CHECK (outcome IS NULL OR outcome IN ('PAID', 'DECLINED', 'TIMED_OUT'))
);
CREATE INDEX ix_redelivery_payment_open ON redelivery_payment (updated_at) WHERE state = 'RUNNING';
CREATE INDEX ix_redelivery_payment_attempt ON redelivery_payment (payment_attempt_id);
CREATE INDEX ix_redelivery_payment_account ON redelivery_payment (account_id, created_at);
