-- 위치 끊김 감지·위치 보존 기간(2026-11-14, 사용자 결정 — "배달 중 3분", "보존기간은 30일").
-- rider_active_delivery: 진행 중 배송(배정 ~ 완료·실패·취소 전) — delivery.delivery.v1 사건으로 만든다(dispatch 배정·배정 변경·취소, execution 완료·실패).
--   state: ACTIVE 감시 중 / RELEASED 배정 해제(그 배정 버전 이하의 늦은 배정 사건은 무시 — 더 높은 버전 배정만 다시 연다) /
--   EXPIRED 배정 뒤 watch-max-age(5시간, 사용자 2026-11-14 "5시간이 지나면 감시 종료") 지나 감시 끝(더 높은 버전 배정은 다시 연다) /
--   CLOSED 완료·실패·픽업 전 취소(다시 열지 않는다). ACTIVE 가 아닌 행은 보존 기간 지나면 지움.
--   since = 사건에 적힌 배정 시각(acceptedAt·changedAt) — 소비한 시각이 아니다(옛 사건을 다시 읽어도 지금 배정된 것처럼 되지 않게, PC 1 PR #15 검토).
-- rider_signal: 라이더마다 마지막으로 점을 받은 서버 시각(품질과 무관 — 통신이 살아 있는지)과 끊김 알림을 이미 낸 기준 시각(끊김 한 번에 사건 한 번).
CREATE TABLE rider_active_delivery (
    delivery_id         varchar(64)  NOT NULL,
    rider_id            varchar(64)  NOT NULL,
    assignment_version  integer      NOT NULL,
    state               varchar(8)   NOT NULL,
    since               timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_rider_active_delivery PRIMARY KEY (delivery_id),
    CONSTRAINT ck_rider_active_delivery_state CHECK (state IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'CLOSED'))
);
CREATE INDEX ix_rider_active_delivery_rider ON rider_active_delivery (rider_id) WHERE state = 'ACTIVE';

CREATE TABLE rider_signal (
    rider_id          varchar(64)  NOT NULL,
    last_received_at  timestamptz,
    alerted_for       timestamptz,
    alert_count       integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_rider_signal PRIMARY KEY (rider_id)
);

-- 보존 기간 삭제용(서버 수신 시각 기준)
CREATE INDEX ix_rider_location_point_received ON rider_location_point (received_at);
