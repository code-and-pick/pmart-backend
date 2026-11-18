-- 화면용 배송 번호(2026-11-18 — 사용자 "주문번호-순번", ADR-0070 화면 번호 규칙): 같은 주문의 몇 번째 배송인지(1부터).
-- 화면 배송 번호 = <화면용 주문번호 8자리>-<delivery_seq>(예 74939255-1). delivery_id 는 그대로 시스템 키.
-- 새 배송은 INSERT 때 트리거가 그 주문의 다음 번호를 준다(주문마다 advisory lock — 회수·재발송이 같이 생겨도 겹치지 않게). 기존 배송은 생성순으로 채운다.
ALTER TABLE delivery ADD COLUMN delivery_seq integer;

WITH s AS (
  SELECT delivery_id, row_number() OVER (PARTITION BY order_id ORDER BY created_at, delivery_id) AS n
    FROM delivery
)
UPDATE delivery d SET delivery_seq = s.n FROM s WHERE d.delivery_id = s.delivery_id;

CREATE UNIQUE INDEX ux_delivery_order_seq ON delivery (order_id, delivery_seq);

CREATE OR REPLACE FUNCTION delivery_assign_seq() RETURNS trigger AS $$
BEGIN
  IF NEW.delivery_seq IS NULL THEN
    PERFORM pg_advisory_xact_lock(hashtext('delivery-seq:' || NEW.order_id));
    SELECT COALESCE(max(delivery_seq), 0) + 1 INTO NEW.delivery_seq FROM delivery WHERE order_id = NEW.order_id;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_delivery_seq BEFORE INSERT ON delivery
  FOR EACH ROW EXECUTE FUNCTION delivery_assign_seq();
