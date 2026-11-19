-- TR-4 곧 도착(2026-11-19 — 사용자 "응 해줘", 300m): 배송당 한 번 알림 표시 + 고객 배송인지 가를 목적(DeliveryCreated.purpose 사본).
ALTER TABLE rider_active_delivery ADD COLUMN approach_notified_at timestamptz;
ALTER TABLE delivery_progress ADD COLUMN purpose varchar(32);
