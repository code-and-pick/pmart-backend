-- 0.29.0 CDC 근무(DC 백오피스 WF-2 — 사용자 2026-11-26 "시뮬레이션에서 정한대로 해").
-- 근무조·층 × 직무 계획 인원은 pmart-cdc-simulation src/config.ts(SHIFT·WORKFORCE·FLOW.dps·OUTBOUND, main 021021d)의 값 그대로 — [시뮬]·[가정] 값이다(업무 원문에 규칙 없음).
-- 계획 인원은 참고값(배치·출근을 막지 않는다).

CREATE TABLE shift_template (
    cdc_id       varchar(32)  NOT NULL,
    shift_code   varchar(16)  NOT NULL,   -- DAY / EVENING / NIGHT_DOCK
    name         varchar(30)  NOT NULL,
    start_time   varchar(5)   NOT NULL,   -- HH:mm Asia/Seoul
    end_time     varchar(5)   NOT NULL,   -- 시작보다 이르면 다음 날
    breaks       varchar(200) NOT NULL DEFAULT '',  -- "HH:mm-HH:mm" 쉼표
    source       varchar(120) NOT NULL,
    CONSTRAINT pk_shift_template PRIMARY KEY (cdc_id, shift_code)
);

CREATE TABLE staffing_plan (
    cdc_id      varchar(32)  NOT NULL,
    shift_code  varchar(16)  NOT NULL,
    floor       varchar(4)   NOT NULL,   -- 층(5·6·7) 또는 ALL(층을 나누지 않는 도크 상차)
    role        varchar(16)  NOT NULL,   -- FC / SORT / JOCKEY / REPLENISH / DPS / INSPECTOR / NIGHT_JOCKEY / LOADER
    headcount   integer      NOT NULL,
    CONSTRAINT pk_staffing_plan PRIMARY KEY (cdc_id, shift_code, floor, role)
);

CREATE TABLE shift_assignment (
    assignment_id  varchar(64)  NOT NULL,
    cdc_id         varchar(32)  NOT NULL,
    work_date      date         NOT NULL,
    shift_code     varchar(16)  NOT NULL,
    worker_id      varchar(128) NOT NULL,
    floor          varchar(4)   NOT NULL,
    role           varchar(16)  NOT NULL,
    assigned_by    varchar(128) NOT NULL,
    check_in_at    timestamptz,
    check_out_at   timestamptz,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_shift_assignment PRIMARY KEY (assignment_id),
    CONSTRAINT ux_shift_assignment_worker UNIQUE (cdc_id, work_date, shift_code, worker_id)
);
CREATE INDEX ix_shift_assignment_day ON shift_assignment (cdc_id, work_date);

-- 칠곡 CDC01 시드(시뮬레이터 값)
INSERT INTO shift_template (cdc_id, shift_code, name, start_time, end_time, breaks, source) VALUES
  ('CDC01', 'DAY', '주간조', '08:00', '17:00', '', 'sim SHIFT.day [가정] 주간조·08:00 입고 시작'),
  ('CDC01', 'EVENING', '석간조', '13:00', '22:00', '15:30-15:45,18:00-19:00,20:30-20:45', 'sim SHIFT.evening·breaks [현장]'),
  ('CDC01', 'NIGHT_DOCK', '야간 도크', '22:00', '04:30', '', 'sim OUTBOUND.loaderShift [가정] 도크 상차·R/T 하강');

INSERT INTO staffing_plan (cdc_id, shift_code, floor, role, headcount) VALUES
  -- 5F 냉장·냉동(WORKFORCE[5], FLOW.dps 5구역 × 3)
  ('CDC01', 'EVENING', '5', 'FC', 11), ('CDC01', 'EVENING', '5', 'SORT', 13), ('CDC01', 'EVENING', '5', 'JOCKEY', 8),
  ('CDC01', 'DAY', '5', 'REPLENISH', 20), ('CDC01', 'EVENING', '5', 'REPLENISH', 3), ('CDC01', 'EVENING', '5', 'DPS', 15),
  ('CDC01', 'EVENING', '5', 'INSPECTOR', 4), ('CDC01', 'NIGHT_DOCK', '5', 'NIGHT_JOCKEY', 4),
  -- 6F 상온(WORKFORCE[6])
  ('CDC01', 'EVENING', '6', 'FC', 14), ('CDC01', 'EVENING', '6', 'SORT', 9), ('CDC01', 'EVENING', '6', 'JOCKEY', 4),
  ('CDC01', 'DAY', '6', 'REPLENISH', 14), ('CDC01', 'EVENING', '6', 'REPLENISH', 3),
  ('CDC01', 'EVENING', '6', 'INSPECTOR', 4), ('CDC01', 'NIGHT_DOCK', '6', 'NIGHT_JOCKEY', 4),
  -- 7F 생활용품(WORKFORCE[7])
  ('CDC01', 'EVENING', '7', 'FC', 19), ('CDC01', 'EVENING', '7', 'SORT', 6), ('CDC01', 'EVENING', '7', 'JOCKEY', 7),
  ('CDC01', 'DAY', '7', 'REPLENISH', 16), ('CDC01', 'EVENING', '7', 'REPLENISH', 3),
  ('CDC01', 'EVENING', '7', 'INSPECTOR', 4), ('CDC01', 'NIGHT_DOCK', '7', 'NIGHT_JOCKEY', 4),
  -- 도크 상차(OUTBOUND.loaders 22 — 도크 층마다 나눔)
  ('CDC01', 'NIGHT_DOCK', 'ALL', 'LOADER', 22);
