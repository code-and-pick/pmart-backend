-- S4 예약 시간 변경 — 작업 여력 교체 점유(2026-11-16, OMS §4 "새 시간대 … MFC 작업 여력을 검증하고 임시 확보", 실패하면 새 것만 해제·기존 유지).
-- 새 Hold(HELD)의 replaces_hold_id = 기존 확정 Hold. 새 Hold 를 확정할 때 같은 트랜잭션에서 기존을 RELEASED(RESCHEDULED).
-- 주문당 활성 1건 → HELD 1건 + CONFIRMED 1건(교체 중에는 기존 CONFIRMED 와 새 HELD 가 함께, 두 창 모두 단위를 잡는다).
ALTER TABLE capacity_hold ADD COLUMN replaces_hold_id VARCHAR(64);
DROP INDEX ux_capacity_hold_active_order;
CREATE UNIQUE INDEX ux_capacity_hold_held_order ON capacity_hold (order_id) WHERE state = 'HELD';
CREATE UNIQUE INDEX ux_capacity_hold_confirmed_order ON capacity_hold (order_id) WHERE state = 'CONFIRMED';
