-- 오배송 고객 잘못 다시 배송(2026-11-16 — ADR-0065): type RESHIPMENT + purpose MISDELIVERY_REDELIVERY.
-- 배정 → 잘못 둔 곳에서 사진·받음(returns/pickups → PICKED_UP) → 고객 도착 → 전달 완료(DeliveryCompleted purpose — order 는 수량을 다시 세지 않음).
-- redelivery_destination_id: 상담원이 order 에 기록한 실제 집 주소 ID(DeliveryCreated dropAddressRef order:<id>:redelivery:<destinationId>) — 전달지에 쓴다.
ALTER TABLE execution_delivery ADD COLUMN redelivery_destination_id varchar(64);
