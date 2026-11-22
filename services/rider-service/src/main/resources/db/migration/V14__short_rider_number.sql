-- 짧은 라이더 번호(2026-11-22, ADR-0085 — 사용자 "라이더 R4785 좀 이렇게 간단하게", 목록엔 번호만).
-- 'R' + 숫자 4자리(1000~9999, 무작위·겹치지 않음·바뀌지 않음). 4자리를 50번 뽑아도 비어 있는 번호를 못 찾으면
-- (거의 다 찼을 때) 5자리(10000~99999). 기존 6자리 번호(V10)는 모두 새 번호로 바꾼다 — 다른 서비스는 번호를 사본으로
-- 들지 않고 rider 조회로 읽는다(tracking·backoffice-bff). rider_id 는 그대로 시스템 키.
ALTER TABLE rider ALTER COLUMN rider_number TYPE varchar(8);

CREATE OR REPLACE FUNCTION rider_next_number() RETURNS varchar AS $$
DECLARE
  n varchar(8);
  tries int := 0;
BEGIN
  LOOP
    IF tries < 50 THEN
      n := 'R' || (1000 + floor(random() * 9000))::int::text;
    ELSE
      n := 'R' || (10000 + floor(random() * 90000))::int::text;
    END IF;
    EXIT WHEN NOT EXISTS (SELECT 1 FROM rider WHERE rider_number = n);
    tries := tries + 1;
  END LOOP;
  RETURN n;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION rider_assign_number() RETURNS trigger AS $$
BEGIN
  IF NEW.rider_number IS NULL THEN
    NEW.rider_number := rider_next_number();
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

UPDATE rider SET rider_number = NULL;

DO $$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT rider_id FROM rider ORDER BY joined_at, rider_id LOOP
    UPDATE rider SET rider_number = rider_next_number() WHERE rider_id = r.rider_id;
  END LOOP;
END $$;
