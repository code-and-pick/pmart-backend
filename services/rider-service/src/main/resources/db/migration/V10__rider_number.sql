-- 화면용 라이더 번호(2026-11-18 — 사용자 "라이더 번호 6자리 + 가린 이름", ADR-0070 화면 번호 규칙): 숫자 6자리, 첫 자리 1~9.
-- 전체에서 겹치지 않는 무작위 번호(가입 순서·라이더 수를 짐작할 수 없게), 한 번 정하면 바뀌지 않는다. rider_id 는 그대로 시스템 키.
-- 새 라이더는 INSERT 때 트리거가 채운다(겹치면 다시 뽑음). 기존 라이더는 여기서 채운다.
ALTER TABLE rider ADD COLUMN rider_number varchar(6);
CREATE UNIQUE INDEX ux_rider_number ON rider (rider_number);

CREATE OR REPLACE FUNCTION rider_assign_number() RETURNS trigger AS $$
DECLARE
  n varchar(6);
BEGIN
  IF NEW.rider_number IS NULL THEN
    LOOP
      n := (100000 + floor(random() * 900000))::int::text;
      EXIT WHEN NOT EXISTS (SELECT 1 FROM rider WHERE rider_number = n);
    END LOOP;
    NEW.rider_number := n;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rider_number BEFORE INSERT ON rider
  FOR EACH ROW EXECUTE FUNCTION rider_assign_number();

DO $$
DECLARE
  r record;
  n varchar(6);
BEGIN
  FOR r IN SELECT rider_id FROM rider WHERE rider_number IS NULL ORDER BY joined_at LOOP
    LOOP
      n := (100000 + floor(random() * 900000))::int::text;
      EXIT WHEN NOT EXISTS (SELECT 1 FROM rider WHERE rider_number = n);
    END LOOP;
    UPDATE rider SET rider_number = n WHERE rider_id = r.rider_id;
  END LOOP;
END $$;
