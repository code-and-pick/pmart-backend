-- 오배송 다시 배송 실제 집 주소(2026-11-16 — ADR-0065, 상담원 기록). 주문 배송지 사본(customer_order_shipping)은 바꾸지 않는다.
-- 기록자·사유·시각 = 입력 기록(audit-service 는 열람 기록만). destination_id 는 (직원·주문·요청 키)로 정해 같은 요청은 하나. 개인정보 — 로그 금지.
CREATE TABLE order_redelivery_destination (
    destination_id  varchar(64)  NOT NULL,
    order_id        varchar(64)  NOT NULL,
    delivery_id     varchar(64)  NOT NULL,
    road_address    varchar(200) NOT NULL,
    detail_address  varchar(100),
    postal_code     varchar(10),
    reason          varchar(200) NOT NULL,
    recorded_by     varchar(128) NOT NULL,
    recorded_at     timestamptz  NOT NULL,
    CONSTRAINT pk_order_redelivery_destination PRIMARY KEY (destination_id)
);
CREATE INDEX ix_order_redelivery_destination_order ON order_redelivery_destination (order_id);
