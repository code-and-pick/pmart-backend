-- 오배송 정산 차감은 오배송된 배송 하나당 한 번(2026-11-24 사용자 — 같은 배송에 오배송 사건이 두 번 와도, LAN 28767701).
CREATE UNIQUE INDEX ux_rider_deduction_origin ON rider_deduction (origin_delivery_id);
