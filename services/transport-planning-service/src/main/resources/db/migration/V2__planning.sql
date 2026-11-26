-- transport-planning-service 0.2.0(TMS 1단계 — 정본 2.3): 노선·정규차 계약, 이관요청서, 운송지시서.
-- 차량·기사 정본은 fleet(여기는 사본), TRF 정본은 cdc-outbound(여기는 가져온 사본).

CREATE TABLE route (
    route_id        varchar(16)  NOT NULL,
    cdc_id          varchar(32)  NOT NULL,
    name            varchar(60)  NOT NULL,
    departure_time  varchar(5)   NOT NULL,   -- HH:mm Asia/Seoul — 12:00 이후면 출고일 D, 앞이면 D+1
    return_minutes  integer      NOT NULL,
    active          boolean      NOT NULL DEFAULT true,
    version         integer      NOT NULL DEFAULT 1,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_route PRIMARY KEY (route_id)
);

CREATE TABLE route_stop (
    route_id     varchar(16)  NOT NULL,
    seq          integer      NOT NULL,
    mfc_id       varchar(32)  NOT NULL,
    leg_minutes  integer      NOT NULL,
    CONSTRAINT pk_route_stop PRIMARY KEY (route_id, seq)
);
CREATE INDEX ix_route_stop_mfc ON route_stop (mfc_id);

-- 노선 × 온도대 정규차·기본 기사(fleet 사본)
CREATE TABLE route_vehicle (
    route_id       varchar(16)  NOT NULL,
    temp_class     varchar(16)  NOT NULL,
    vehicle_id     varchar(32)  NOT NULL,
    driver_id      varchar(32)  NOT NULL,
    plate_no       varchar(20),
    driver_name    varchar(30),
    max_rt         integer      NOT NULL,
    max_weight_kg  integer      NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_route_vehicle PRIMARY KEY (route_id, temp_class)
);

-- 이관요청서(TRF 하나) — cdc-outbound transport-feed 사본 + 검증 결과
CREATE TABLE transport_request (
    trf_id            varchar(64)  NOT NULL,
    trf_number        varchar(16),
    trf_version       integer      NOT NULL,
    cdc_id            varchar(32)  NOT NULL,
    mfc_id            varchar(32)  NOT NULL,
    ship_date         date         NOT NULL,
    due_at            timestamptz  NOT NULL,
    temp_classes      varchar(64)  NOT NULL,   -- 쉼표로 이은 온도대(없으면 빈 문자열)
    temp_class        varchar(16),
    allocation_state  varchar(32)  NOT NULL,
    approved_qty      integer      NOT NULL,
    accepted_qty      integer      NOT NULL,
    box_count         integer      NOT NULL,
    rt_count          integer,
    weight_kg         numeric(10,3) NOT NULL,
    state             varchar(16)  NOT NULL,
    reason            varchar(40),
    late_receipt      boolean      NOT NULL DEFAULT false,
    order_id          varchar(32),
    received_at       timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT pk_transport_request PRIMARY KEY (trf_id)
);
CREATE INDEX ix_transport_request_day ON transport_request (cdc_id, ship_date, state);
CREATE INDEX ix_transport_request_order ON transport_request (order_id);

-- 운송지시서 = 차량 1대 × 운행 1회(정본 8장). 출고일 × 노선 × 온도대 하나
CREATE TABLE transport_order (
    order_id                  varchar(32)  NOT NULL,
    cdc_id                    varchar(32)  NOT NULL,
    ship_date                 date         NOT NULL,
    route_id                  varchar(16)  NOT NULL,
    temp_class                varchar(16)  NOT NULL,
    contract_type             varchar(16)  NOT NULL,
    vehicle_id                varchar(32),
    driver_id                 varchar(32),
    plate_no                  varchar(20),
    driver_name               varchar(30),
    max_rt                    integer      NOT NULL,
    max_weight_kg             integer      NOT NULL,
    return_minutes            integer      NOT NULL,
    planned_loading_start_at  timestamptz  NOT NULL,
    planned_departure_at      timestamptz  NOT NULL,
    planned_return_at         timestamptz  NOT NULL,
    state                     varchar(16)  NOT NULL,
    reservation_id            varchar(64),
    trip_state                varchar(16),
    trip_version              integer,
    reason                    varchar(300),
    version                   integer      NOT NULL DEFAULT 1,
    created_at                timestamptz  NOT NULL,
    updated_at                timestamptz  NOT NULL,
    CONSTRAINT pk_transport_order PRIMARY KEY (order_id),
    CONSTRAINT ux_transport_order_slot UNIQUE (cdc_id, ship_date, route_id, temp_class)
);

CREATE TABLE transport_order_stop (
    order_id                varchar(32)  NOT NULL,
    seq                     integer      NOT NULL,
    mfc_id                  varchar(32)  NOT NULL,
    leg_minutes             integer      NOT NULL,
    planned_arrival_at      timestamptz  NOT NULL,
    planned_departure_at    timestamptz  NOT NULL,
    CONSTRAINT pk_transport_order_stop PRIMARY KEY (order_id, seq)
);

CREATE TABLE transport_order_history (
    order_id   varchar(32)   NOT NULL,
    seq        bigserial     NOT NULL,
    at         timestamptz   NOT NULL,
    action     varchar(32)   NOT NULL,
    actor_id   varchar(128),
    detail     varchar(500),
    version    integer       NOT NULL,
    CONSTRAINT pk_transport_order_history PRIMARY KEY (seq)
);
CREATE INDEX ix_transport_order_history_order ON transport_order_history (order_id, seq);
