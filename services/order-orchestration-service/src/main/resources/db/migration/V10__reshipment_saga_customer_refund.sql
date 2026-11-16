-- 오배송 고객 잘못 + 환불(2026-11-16, ADR-0065 3단계): mode CUSTOMER_REFUND — 회수 후 상품값 − 9,000원 환불. V9 의 제약을 넓힌다.
ALTER TABLE reshipment_saga DROP CONSTRAINT ck_reshipment_saga_mode;
ALTER TABLE reshipment_saga ADD CONSTRAINT ck_reshipment_saga_mode CHECK (mode IN ('RESHIP', 'REFUND', 'CUSTOMER_REFUND'));
