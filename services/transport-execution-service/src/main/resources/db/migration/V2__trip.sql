-- transport-execution-service 0.2.0(TMS 1단계 — 정본 9·10·11장): 운행·정차·이관요청서별 상차·이벤트·인계 증빙·예외.
-- 운행 = planning 계획완료 운송지시서의 실행(trip_id = order_id). 재고는 바꾸지 않는다(TOUT 은 cdc-outbound 출발 검증 → cdc-inventory).

CREATE TABLE trip (
    trip_id                   varchar(32)  NOT NULL,
    order_version             integer      NOT NULL,
    cdc_id                    varchar(32)  NOT NULL,
    ship_date                 date         NOT NULL,
    route_id                  varchar(16)  NOT NULL,
    temp_class                varchar(16)  NOT NULL,
    vehicle_id                varchar(32)  NOT NULL,
    plate_no                  varchar(20),
    driver_id                 varchar(32)  NOT NULL,
    driver_name               varchar(30),
    reservation_id            varchar(64),
    planned_loading_start_at  timestamptz  NOT NULL,
    planned_departure_at      timestamptz  NOT NULL,
    planned_return_at         timestamptz  NOT NULL,
    return_minutes            integer      NOT NULL,
    state                     varchar(16)  NOT NULL,
    checked_in_at             timestamptz,
    loaded_at                 timestamptz,
    departed_at               timestamptz,
    ended_at                  timestamptz,
    departure_id              varchar(64),
    departure_source          varchar(16),
    evidence_ref              varchar(128),
    outbound_linkage          varchar(16),   -- PENDING / ACKNOWLEDGED / FAILED / UNKNOWN_RESULT
    outbound_result           varchar(300),
    planning_synced_version   integer      NOT NULL DEFAULT 0,
    reservation_released      boolean      NOT NULL DEFAULT false,
    input_hash                varchar(64)  NOT NULL,
    version                   integer      NOT NULL DEFAULT 1,
    created_at                timestamptz  NOT NULL,
    updated_at                timestamptz  NOT NULL,
    CONSTRAINT pk_trip PRIMARY KEY (trip_id)
);
CREATE INDEX ix_trip_cdc_day ON trip (cdc_id, ship_date, state);
CREATE UNIQUE INDEX ux_trip_departure ON trip (departure_id) WHERE departure_id IS NOT NULL;

CREATE TABLE trip_stop (
    trip_id               varchar(32)  NOT NULL,
    seq                   integer      NOT NULL,
    mfc_id                varchar(32)  NOT NULL,
    leg_minutes           integer      NOT NULL,
    planned_arrival_at    timestamptz  NOT NULL,
    planned_departure_at  timestamptz,
    state                 varchar(16)  NOT NULL,   -- PLANNED / ARRIVED / HANDED_OVER / DEPARTED
    arrived_at            timestamptz,
    handed_over_at        timestamptz,
    departed_at           timestamptz,
    CONSTRAINT pk_trip_stop PRIMARY KEY (trip_id, seq)
);
CREATE INDEX ix_trip_stop_mfc ON trip_stop (mfc_id, planned_arrival_at);

-- 이관요청서별 상차(상차 스캔 = 출발 구성). lpns·seal_nos 는 쉼표로 이은 목록
CREATE TABLE trip_transfer (
    trip_id    varchar(32)  NOT NULL,
    trf_id     varchar(64)  NOT NULL,
    stop_seq   integer      NOT NULL,
    loaded     boolean      NOT NULL DEFAULT false,
    lpns       text         NOT NULL DEFAULT '',
    rt_count   integer,
    seal_nos   text         NOT NULL DEFAULT '',
    CONSTRAINT pk_trip_transfer PRIMARY KEY (trip_id, trf_id)
);

CREATE TABLE trip_event (
    seq            bigserial     NOT NULL,
    trip_id        varchar(32)   NOT NULL,
    at             timestamptz   NOT NULL,
    type           varchar(24)   NOT NULL,
    stop_seq       integer,
    location       varchar(120),
    temperature_c  numeric(5,1),
    actor_id       varchar(128),
    source         varchar(64),
    CONSTRAINT pk_trip_event PRIMARY KEY (seq)
);
CREATE INDEX ix_trip_event_trip ON trip_event (trip_id, seq);

-- 인계 증빙(정본 10.2) — 정차 하나에 하나
CREATE TABLE handover (
    handover_id     varchar(64)   NOT NULL,
    trip_id         varchar(32)   NOT NULL,
    stop_seq        integer       NOT NULL,
    result          varchar(16)   NOT NULL,   -- MATCHED / MISMATCHED
    shipped_boxes   integer       NOT NULL,
    received_boxes  integer       NOT NULL,
    missing_lpns    text          NOT NULL DEFAULT '',
    extra_lpns      text          NOT NULL DEFAULT '',
    damaged_lpns    text          NOT NULL DEFAULT '',
    receiver_id     varchar(128),
    receiver_name   varchar(30),
    input_hash      varchar(64)   NOT NULL,
    at              timestamptz   NOT NULL,
    CONSTRAINT pk_handover PRIMARY KEY (handover_id),
    CONSTRAINT ux_handover_stop UNIQUE (trip_id, stop_seq)
);

CREATE TABLE trip_incident (
    incident_id  varchar(64)   NOT NULL,
    trip_id      varchar(32)   NOT NULL,
    type         varchar(32)   NOT NULL,
    stop_seq     integer,
    state        varchar(16)   NOT NULL,   -- OPEN / RESOLVED
    description  varchar(500)  NOT NULL,
    resolution   varchar(500),
    raised_at    timestamptz   NOT NULL,
    resolved_at  timestamptz,
    CONSTRAINT pk_trip_incident PRIMARY KEY (incident_id)
);
CREATE INDEX ix_trip_incident_trip ON trip_incident (trip_id);
