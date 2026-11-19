-- DD-4 정시 배송률(2026-11-19): 전달 완료 방법(DeliveryCompleted.method) — 고객 수령 확인·자동 완료는 전달 시각이 실제가 아니라 판정 불가.
ALTER TABLE delivery ADD COLUMN completion_method varchar(24);
