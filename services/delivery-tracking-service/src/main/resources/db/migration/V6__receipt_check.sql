-- 고객에게 받았는지 묻기(2026-11-17) — 배송마다 한 번.
ALTER TABLE delivery_progress ADD COLUMN receipt_asked_at timestamptz;
