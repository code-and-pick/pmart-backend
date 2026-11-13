-- 라이더 위치(2026-11-13, DD-1 첫 묶음 — delivery-tracking-service.yaml 0.2.0).
-- rider_location_point: 받은 점 전부(정확도 불량·시간 역전도 폐기하지 않고 quality 로 표시 — 계약). 보존 기간은 아직 정하지 않음.
-- rider_latest_location: 라이더당 마지막 위치(단말 시각이 더 늦은 GOOD/LOW_ACCURACY 점만 바꾼다). dispatch 반경 판정의 원천.
CREATE TABLE rider_location_point (
    point_id     bigserial        NOT NULL,
    rider_id     varchar(64)      NOT NULL,
    lat          double precision NOT NULL,
    lng          double precision NOT NULL,
    accuracy_m   double precision,
    speed_mps    double precision,
    heading_deg  double precision,
    delivery_id  varchar(64),
    recorded_at  timestamptz      NOT NULL,  -- 단말 시각
    received_at  timestamptz      NOT NULL,  -- 서버 수신 시각
    quality      varchar(16)      NOT NULL,
    CONSTRAINT pk_rider_location_point PRIMARY KEY (point_id)
);
CREATE INDEX ix_rider_location_point_rider ON rider_location_point (rider_id, recorded_at);

CREATE TABLE rider_latest_location (
    rider_id     varchar(64)      NOT NULL,
    lat          double precision NOT NULL,
    lng          double precision NOT NULL,
    accuracy_m   double precision,
    recorded_at  timestamptz      NOT NULL,
    received_at  timestamptz      NOT NULL,
    quality      varchar(16)      NOT NULL,
    CONSTRAINT pk_rider_latest_location PRIMARY KEY (rider_id)
);
