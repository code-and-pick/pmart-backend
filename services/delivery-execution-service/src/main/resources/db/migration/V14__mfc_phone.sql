-- 픽업 지점 전화(2026-11-17 — 라이더 앱 "가게 전화"): 배정 사건(DeliveryAssigned.mfcPhone, center-master profile) 사본. 없으면 비어 있다.
ALTER TABLE execution_delivery ADD COLUMN mfc_phone varchar(30);
