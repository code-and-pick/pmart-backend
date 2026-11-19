-- DE-6 "주문하신 곳으로 가고 있어요"(2026-11-19 — 사용자 "응 해줘"): 묶음 전달 순서 사본(DeliveryAssigned.bundleId·stopSequence)과 알림 한 번 표시.
ALTER TABLE execution_delivery ADD COLUMN bundle_id varchar(64);
ALTER TABLE execution_delivery ADD COLUMN stop_seq integer;
ALTER TABLE execution_delivery ADD COLUMN heading_notified_at timestamptz;
