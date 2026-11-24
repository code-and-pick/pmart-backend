-- 0.40.0 R-17(2026-11-24 — 웹 요청, 사용자 "b 로 하고 라이더는 운행 센터로"): 문의 센터.
-- 고객 문의 = 주문 이행 센터(받을 때 order 고객 조회의 mfcId, 그 전 문의는 InquiryCenterBackfill 이 order 원장으로 한 번),
-- 라이더 문의 = 접수 때 운행 센터(rider GET /riders/me operating.mfcId). NULL = 센터 없음 — 센터를 골라도 늘 함께 보인다(결정 b).
ALTER TABLE cs_inquiry ADD COLUMN center_id varchar(64);
-- 채우기를 이미 해 본 행(주문 없음·라이더·주문에 센터 없음 포함) — 다시 부르지 않게
ALTER TABLE cs_inquiry ADD COLUMN center_checked boolean NOT NULL DEFAULT false;
UPDATE cs_inquiry SET center_checked = true WHERE order_id IS NULL OR requester_type <> 'CUSTOMER';
CREATE INDEX ix_cs_inquiry_center ON cs_inquiry (center_id, created_at DESC);
