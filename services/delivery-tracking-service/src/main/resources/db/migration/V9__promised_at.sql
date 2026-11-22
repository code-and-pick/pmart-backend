-- 관제 지연 표시(2026-11-23 — 사용자 "지연 기준 시간은 이미 있잖아" = 약속 + 20분, dispatch delay-alert-after): 배송 약속 시각 사본.
-- dispatch DeliveryAssigned.promisedAt(2026-11-21 부터 실림). 이 배포 전 배정은 null(지연 판정 없음).
ALTER TABLE delivery_progress ADD COLUMN promised_at timestamptz;
