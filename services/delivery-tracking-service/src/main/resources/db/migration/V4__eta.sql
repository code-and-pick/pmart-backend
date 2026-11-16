-- 라이더 ETA(TR-1, 2026-11-16): 진행 중 배송마다 픽업 여부·픽업지/도착지 좌표(dispatch 내부 조회 사본 — 배송이 끝나면 행과 함께 보존 기간 뒤 지움)
-- ·마지막으로 낸 도착 예정(eta_drop)·마지막 계산 시각·사건 버전(Delivery aggregate, 이 서비스가 따로 센다).
ALTER TABLE rider_active_delivery ADD COLUMN picked_up_at     timestamptz;
ALTER TABLE rider_active_delivery ADD COLUMN pickup_lat       double precision;
ALTER TABLE rider_active_delivery ADD COLUMN pickup_lng       double precision;
ALTER TABLE rider_active_delivery ADD COLUMN drop_lat         double precision;
ALTER TABLE rider_active_delivery ADD COLUMN drop_lng         double precision;
ALTER TABLE rider_active_delivery ADD COLUMN pickup_to_drop_s integer;
ALTER TABLE rider_active_delivery ADD COLUMN eta_drop         timestamptz;
ALTER TABLE rider_active_delivery ADD COLUMN eta_checked_at   timestamptz;
ALTER TABLE rider_active_delivery ADD COLUMN eta_version      integer NOT NULL DEFAULT 0;
