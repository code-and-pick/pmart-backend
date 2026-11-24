-- 내부 이동 전기(0.3.0, 2026-11-25): 할당 예약을 현물과 함께 다른 위치로 옮긴다(팔레트 랙 → 피킹 선반 보충, 칠곡 CDC-7).
-- 원위치 행은 moved_out 을, 도착 위치 행은 moved_in 을 올린다(도착 행은 처음 할당량 0 으로 새로 생길 수 있다).
-- 남은 예약 = allocated + moved_in − moved_out − released − departed. 처음 할당량(allocated)은 이력으로 그대로 둔다.
ALTER TABLE allocation_lot ADD COLUMN moved_in_qty  integer NOT NULL DEFAULT 0;
ALTER TABLE allocation_lot ADD COLUMN moved_out_qty integer NOT NULL DEFAULT 0;
ALTER TABLE allocation_lot DROP CONSTRAINT ck_allocation_lot_qty;
ALTER TABLE allocation_lot ADD CONSTRAINT ck_allocation_lot_qty CHECK (
    allocated_qty >= 0 AND released_qty >= 0 AND departed_qty >= 0 AND moved_in_qty >= 0 AND moved_out_qty >= 0
    AND allocated_qty + moved_in_qty > 0
    AND released_qty + departed_qty + moved_out_qty <= allocated_qty + moved_in_qty);
