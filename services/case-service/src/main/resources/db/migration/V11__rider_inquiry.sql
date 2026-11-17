-- 라이더 문의(2026-11-16 — 라이더 앱 요청 §11-4, 사용자 "추천대로": 배송과 상관없는 라이더 문의는 case-service 가 직접 받는다).
-- 같은 문의 표를 쓰고 남긴 사람 종류로 나눈다. customer_id 열에는 남긴 주체(고객 accountId 또는 라이더 RD-…)가 들어간다.
-- 사건 행 종류는 RIDER_INQUIRY(고객 문의 CS_INQUIRY 처럼 사건함 /cases 에는 나오지 않고 /inquiries 로 본다).
ALTER TABLE cs_inquiry ADD COLUMN requester_type varchar(10) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE cs_inquiry ADD CONSTRAINT ck_cs_inquiry_requester CHECK (requester_type IN ('CUSTOMER', 'RIDER'));
CREATE INDEX ix_cs_inquiry_requester ON cs_inquiry (requester_type, created_at DESC, inquiry_id DESC);
