-- 라이더 내 배달 내역(2026-11-17 — 원문 §8.1 배달현황 "주문번호 … 픽업지 마트 이름"): 배정 사건(DeliveryAssigned)이 실어 준 값의 사본.
-- 이 열 전 배정은 비어 있다.
ALTER TABLE execution_delivery ADD COLUMN order_number varchar(16);
ALTER TABLE execution_delivery ADD COLUMN mfc_name     varchar(100);
