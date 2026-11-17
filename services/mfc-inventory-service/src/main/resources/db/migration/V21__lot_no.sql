-- LOT 번호(2026-11-17, 계약 0.21.0, 요청 IV-4 — 사용자 결정 "GS1-128"). GS1-128 AI (10) 에 넣는 짧은 번호(20자 이하, 전체 유일).
-- 형식 L + 만든 날짜(Asia/Seoul, YYMMDD) + 일련(6자리 이상, 시퀀스 — 날마다 처음부터가 아니다). 뜻은 표시용, 판정은 lot_id.
-- 새 LOT 은 기본값(시퀀스)으로 — 코드 밖에서 넣는 행(LAN 시드)도 채워진다. 기존 LOT 은 만든 순서대로 한 번 채운다.
CREATE SEQUENCE stock_lot_no_seq;

ALTER TABLE stock_lot ADD COLUMN lot_no varchar(20);

UPDATE stock_lot l
   SET lot_no = 'L' || to_char(l.created_at AT TIME ZONE 'Asia/Seoul', 'YYMMDD')
                || lpad(s.n::text, greatest(6, length(s.n::text)), '0')
  FROM (SELECT lot_id, nextval('stock_lot_no_seq') AS n
          FROM (SELECT lot_id FROM stock_lot ORDER BY created_at, lot_id) o) s
 WHERE s.lot_id = l.lot_id;

-- 기본값은 함수로(일련이 6자리를 넘어도 자르지 않게 — 한 번만 nextval).
CREATE FUNCTION next_stock_lot_no() RETURNS varchar LANGUAGE sql VOLATILE AS $$
    SELECT 'L' || to_char(now() AT TIME ZONE 'Asia/Seoul', 'YYMMDD') || lpad(n::text, greatest(6, length(n::text)), '0')
      FROM (SELECT nextval('stock_lot_no_seq') AS n) s
$$;

ALTER TABLE stock_lot
    ALTER COLUMN lot_no SET DEFAULT next_stock_lot_no(),
    ALTER COLUMN lot_no SET NOT NULL;

CREATE UNIQUE INDEX uq_stock_lot_no ON stock_lot (lot_no);
