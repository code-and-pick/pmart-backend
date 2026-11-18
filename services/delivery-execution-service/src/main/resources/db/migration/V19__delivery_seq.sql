-- 화면 배송 번호(2026-11-18, ADR-0070) = 화면용 주문번호-delivery_seq. 순번은 dispatch 가 정해 DeliveryCreated.deliverySeq 로 보낸다.
ALTER TABLE execution_delivery ADD COLUMN delivery_seq integer;
