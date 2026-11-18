-- 접속·대기 시간(2026-11-18 — 라이더 백오피스 관제, 사용자 "접속 시간 따로 더함", 대기 = 운행 중 + 신규배차 켬 + 배송 0건)
-- on_duty_since: 지금 운행(ON_DUTY) 구간 시작, available_since: 운행 중 + 신규배차 켬이 된 시각(꺼지면 NULL)
ALTER TABLE rider
    ADD COLUMN on_duty_since   timestamptz,
    ADD COLUMN available_since timestamptz;

-- 운행 구간: (운행 여부, 신규배차) 가 바뀔 때마다 앞 구간을 닫고 운행 중이면 새 구간을 연다. ended_at NULL = 지금 열려 있음
CREATE TABLE rider_duty_segment (
    segment_id  bigserial    PRIMARY KEY,
    rider_id    varchar(64)  NOT NULL,
    started_at  timestamptz  NOT NULL,
    ended_at    timestamptz,
    available   boolean      NOT NULL
);
CREATE INDEX ix_rider_duty_segment_rider ON rider_duty_segment (rider_id, started_at);
CREATE UNIQUE INDEX ux_rider_duty_segment_open ON rider_duty_segment (rider_id) WHERE ended_at IS NULL;

-- 지금 운행 중인 라이더는 마지막 상태 변경 시각부터로 본다(이 마이그레이션 전 기록이 없어 근사)
UPDATE rider SET on_duty_since = state_changed_at,
                 available_since = CASE WHEN new_dispatch_enabled THEN state_changed_at END
 WHERE operating_status = 'ON_DUTY';
INSERT INTO rider_duty_segment (rider_id, started_at, available)
SELECT rider_id, state_changed_at, new_dispatch_enabled FROM rider WHERE operating_status = 'ON_DUTY';
