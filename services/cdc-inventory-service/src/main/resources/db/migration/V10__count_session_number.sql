-- 실사 화면 번호(0.17.0, 2026-11-26 — CI-1, PC5 DC 백오피스, ADR-0070 덧붙임): C + 숫자 6자리, 무작위·겹치지 않음·바뀌지 않음.
-- 시스템 ID(session_id)는 그대로 — 화면 번호는 사람이 보는 값만. 새 행은 열 기본값이 정한다(코드가 넣지 않음). cdc-outbound V14 와 같은 함수.
-- 적용된 파일은 수정하지 않는다.
CREATE OR REPLACE FUNCTION pmart_display_number(p_table text, p_col text, p_prefix text, p_digits integer)
RETURNS text LANGUAGE plpgsql VOLATILE AS $$
DECLARE
  d integer := p_digits;
  tries integer := 0;
  c text;
  hit boolean;
BEGIN
  LOOP
    c := p_prefix || (floor(random() * (power(10, d) - power(10, d - 1)))::bigint + power(10, d - 1)::bigint)::text;
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE %I = $1)', p_table, p_col) INTO hit USING c;
    IF NOT hit THEN
      RETURN c;
    END IF;
    tries := tries + 1;
    IF tries >= 50 THEN
      d := d + 1;
      tries := 0;
    END IF;
  END LOOP;
END $$;

ALTER TABLE count_session ADD COLUMN session_number varchar(16);
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT session_id FROM count_session ORDER BY session_id LOOP
    UPDATE count_session SET session_number = pmart_display_number('count_session', 'session_number', 'C', 6)
     WHERE session_id = r.session_id;
  END LOOP;
END $$;
ALTER TABLE count_session ALTER COLUMN session_number SET DEFAULT pmart_display_number('count_session', 'session_number', 'C', 6);
ALTER TABLE count_session ALTER COLUMN session_number SET NOT NULL;
CREATE UNIQUE INDEX ux_count_session_number ON count_session (session_number);
