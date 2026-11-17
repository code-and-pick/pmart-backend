-- 배송 신고(2026-11-17 사용자 결정 "기존 문의로, 배송 연결" — 고객앱 빠진 기능 B "배달 지연 제보·오배송 확인"):
-- 고객 문의에 배송 ID(참고값 — 주문 소유만 확인)와 신고 종류. 배송 분류(DELIVERY)·주문이 있는 고객 문의에서만.
ALTER TABLE cs_inquiry ADD COLUMN delivery_id varchar(64);
ALTER TABLE cs_inquiry ADD COLUMN delivery_issue varchar(20);
ALTER TABLE cs_inquiry ADD CONSTRAINT ck_cs_inquiry_delivery_issue CHECK (delivery_issue IS NULL OR delivery_issue IN ('DELAYED', 'NOT_RECEIVED', 'MISDELIVERED'));
