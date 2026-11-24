-- R-16(2026-11-24 ADR-0086 — 사용자 "고객이 오배송으로 문의를 남기기 편해야 … 채팅에서 다시 보내거나 취소환불 할지를 선택").
-- 고객 NOT_RECEIVED 문의가 전달 완료된 고객 배송(FORWARD·RESHIPMENT)을 가리키면 오배송 사건을 자동으로 열고 고객 선택을 기다린다.

-- ① 배송 사본: delivery.delivery.v1 DeliveryCreated(종류·주문·센터) + DeliveryCompleted(라이더·전달 시각).
--    case 가 문의를 받을 때 배송이 전달 완료인지 이것으로 판단한다(동기 호출 없음). 배포 전 배송은 없다 → 그때는 지금처럼 상담원 문의.
CREATE TABLE case_delivery_copy (
    delivery_id     varchar(64)  NOT NULL,
    order_id        varchar(64),
    delivery_type   varchar(16),                -- FORWARD / RETURN / RESHIPMENT (DeliveryCreated 를 못 받았으면 NULL)
    purpose         varchar(40),
    mfc_id          varchar(64),
    rider_id        varchar(64),
    completed_at    timestamptz,                -- DeliveryCompleted deliveredAt
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT pk_case_delivery_copy PRIMARY KEY (delivery_id)
);
CREATE INDEX ix_case_delivery_copy_order ON case_delivery_copy (order_id, completed_at DESC);

-- ② 오배송 문의의 고객 선택(문의마다 하나). resolution NULL = 고르기 기다림. 기한이 지나면 자동 REFUND(chosen_by_type AUTO).
CREATE TABLE inquiry_misdelivery (
    inquiry_id      varchar(64)  NOT NULL,
    case_id         varchar(64)  NOT NULL,      -- 자동으로 연 MISDELIVERY 사건
    delivery_id     varchar(64)  NOT NULL,
    order_id        varchar(64)  NOT NULL,
    rider_id        varchar(64),
    deadline_at     timestamptz  NOT NULL,
    resolution      varchar(10),
    chosen_by_type  varchar(10),
    chosen_by       varchar(128),
    chosen_reason   varchar(200),               -- 상담원이 대신 고른 사유(직원에게만)
    chosen_at       timestamptz,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_inquiry_misdelivery PRIMARY KEY (inquiry_id),
    CONSTRAINT fk_inquiry_misdelivery FOREIGN KEY (inquiry_id) REFERENCES cs_inquiry (inquiry_id),
    CONSTRAINT ck_inquiry_misdelivery_resolution CHECK (resolution IS NULL OR resolution IN ('RESHIP', 'REFUND')),
    CONSTRAINT ck_inquiry_misdelivery_chosen CHECK (chosen_by_type IS NULL OR chosen_by_type IN ('CUSTOMER', 'STAFF', 'AUTO'))
);
CREATE INDEX ix_inquiry_misdelivery_due ON inquiry_misdelivery (deadline_at) WHERE resolution IS NULL;
