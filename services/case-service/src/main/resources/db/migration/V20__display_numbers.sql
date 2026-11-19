-- 화면 표기(ADR-0070 덧붙임 2026-11-19, 사용자 "날짜+일련"): 접수 건 C-MMDD-0042, 문의 Q-MMDD-0012.
-- case_id·inquiry_id 는 그대로 시스템 키. 번호는 DB 가 정한다(겹치지 않게) — 한국 날짜(Asia/Seoul)·종류마다 1부터, 9999 를 넘으면 5자리.
-- 날짜에 연도가 없어 해가 바뀌면 같은 번호가 다시 나온다 — 유일성은 (번호, 그 날짜)로 지킨다.

CREATE TABLE display_number_counter (
  kind     varchar(8) NOT NULL,   -- 'C' 접수 건, 'Q' 문의
  day      date       NOT NULL,   -- 한국 날짜
  last_seq integer    NOT NULL,
  PRIMARY KEY (kind, day)
);

-- 그 날의 다음 일련(행 잠금 — 같은 날 동시에 와도 겹치지 않음)
CREATE FUNCTION next_display_number(p_kind varchar, p_day date) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE n integer;
BEGIN
  INSERT INTO display_number_counter (kind, day, last_seq) VALUES (p_kind, p_day, 1)
  ON CONFLICT (kind, day) DO UPDATE SET last_seq = display_number_counter.last_seq + 1
  RETURNING last_seq INTO n;
  RETURN p_kind || '-' || to_char(p_day, 'MMDD') || '-'
         || CASE WHEN n < 10000 THEN lpad(n::text, 4, '0') ELSE n::text END;
END $$;

-- 접수 건(사건함 종류). 문의의 사건 행(CS_INQUIRY·RIDER_INQUIRY)은 문의 번호(Q-)를 쓰므로 비운다.
ALTER TABLE case_record ADD COLUMN case_number varchar(16);
ALTER TABLE case_record ADD COLUMN case_number_day date;
ALTER TABLE cs_inquiry ADD COLUMN inquiry_number varchar(16);
ALTER TABLE cs_inquiry ADD COLUMN inquiry_number_day date;

-- 지금 있는 행 채우기 — 날짜마다 생성순
WITH s AS (
  SELECT case_id, (created_at AT TIME ZONE 'Asia/Seoul')::date AS d,
         row_number() OVER (PARTITION BY (created_at AT TIME ZONE 'Asia/Seoul')::date ORDER BY created_at, case_id) AS n
  FROM case_record WHERE case_type NOT IN ('CS_INQUIRY', 'RIDER_INQUIRY')
)
UPDATE case_record c
   SET case_number = 'C-' || to_char(s.d, 'MMDD') || '-' || CASE WHEN s.n < 10000 THEN lpad(s.n::text, 4, '0') ELSE s.n::text END,
       case_number_day = s.d
  FROM s WHERE c.case_id = s.case_id;

WITH s AS (
  SELECT inquiry_id, (created_at AT TIME ZONE 'Asia/Seoul')::date AS d,
         row_number() OVER (PARTITION BY (created_at AT TIME ZONE 'Asia/Seoul')::date ORDER BY created_at, inquiry_id) AS n
  FROM cs_inquiry
)
UPDATE cs_inquiry q
   SET inquiry_number = 'Q-' || to_char(s.d, 'MMDD') || '-' || CASE WHEN s.n < 10000 THEN lpad(s.n::text, 4, '0') ELSE s.n::text END,
       inquiry_number_day = s.d
  FROM s WHERE q.inquiry_id = s.inquiry_id;

-- 채운 수에서 이어서 매기게
INSERT INTO display_number_counter (kind, day, last_seq)
SELECT 'C', case_number_day, count(*) FROM case_record WHERE case_number_day IS NOT NULL GROUP BY case_number_day;
INSERT INTO display_number_counter (kind, day, last_seq)
SELECT 'Q', inquiry_number_day, count(*) FROM cs_inquiry WHERE inquiry_number_day IS NOT NULL GROUP BY inquiry_number_day;

CREATE UNIQUE INDEX ux_case_record_number ON case_record (case_number_day, case_number) WHERE case_number IS NOT NULL;
CREATE INDEX ix_case_record_number ON case_record (case_number) WHERE case_number IS NOT NULL;
CREATE UNIQUE INDEX ux_cs_inquiry_number ON cs_inquiry (inquiry_number_day, inquiry_number) WHERE inquiry_number IS NOT NULL;
CREATE INDEX ix_cs_inquiry_number ON cs_inquiry (inquiry_number) WHERE inquiry_number IS NOT NULL;

-- 새 행에 번호 — 넣는 쪽 코드는 모른다(빈 값이면 트리거가 매김)
CREATE FUNCTION assign_case_number() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.case_number IS NULL AND NEW.case_type NOT IN ('CS_INQUIRY', 'RIDER_INQUIRY') THEN
    NEW.case_number_day := (NEW.created_at AT TIME ZONE 'Asia/Seoul')::date;
    NEW.case_number := next_display_number('C', NEW.case_number_day);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_case_record_number BEFORE INSERT ON case_record
  FOR EACH ROW EXECUTE FUNCTION assign_case_number();

CREATE FUNCTION assign_inquiry_number() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.inquiry_number IS NULL THEN
    NEW.inquiry_number_day := (NEW.created_at AT TIME ZONE 'Asia/Seoul')::date;
    NEW.inquiry_number := next_display_number('Q', NEW.inquiry_number_day);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_cs_inquiry_number BEFORE INSERT ON cs_inquiry
  FOR EACH ROW EXECUTE FUNCTION assign_inquiry_number();
