-- 0.33.0 R-12(2026-11-23, 사용자 "주문에 붙은 것만"): 라이더 컴플레인 센터 — 기록 때 dispatch 배정의 mfcId.
-- 이전 기록은 NULL 로 둔다(다른 서비스를 다시 부르지 않는다 — 센터를 고르면 빠진다).
ALTER TABLE rider_complaint ADD COLUMN center_id varchar(64);
CREATE INDEX ix_rider_complaint_center ON rider_complaint (center_id, recorded_at DESC, complaint_id DESC);
