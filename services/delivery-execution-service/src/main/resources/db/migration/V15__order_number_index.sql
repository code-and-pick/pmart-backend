-- 라이더 백오피스 배송 찾기(2026-11-17): 화면용 주문번호 8자리로 찾기.
CREATE INDEX ix_execution_delivery_order_number ON execution_delivery (order_number) WHERE order_number IS NOT NULL;
