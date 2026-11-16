-- 오배송 고객 잘못 다시 배송(2026-11-16 — ADR-0065). 배송 type RESHIPMENT + purpose MISDELIVERY_REDELIVERY:
-- 픽업 = 잘못 둔 곳(pickup_* — 회수와 같은 방법), 도착 = 상담원이 기록한 실제 집(order redelivery_destination → 좌표 drop_*).
-- straight_distance_m: 잘못 둔 곳 ↔ 실제 집 직선거리 — 200m 미만이면 원래 기사에게 먼저(배달료 0), 200m 이상이면 고객 앱 9,000원 결제 뒤에만
-- 배차(payment_required, released_at — 결제 확인 때 채움).
ALTER TABLE delivery ADD COLUMN redelivery_destination_id varchar(64);
ALTER TABLE delivery ADD COLUMN straight_distance_m       integer;
ALTER TABLE delivery ADD COLUMN payment_required          boolean NOT NULL DEFAULT false;
ALTER TABLE delivery ADD COLUMN released_at               timestamptz;
