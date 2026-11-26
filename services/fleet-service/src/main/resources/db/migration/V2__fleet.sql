-- fleet-service 0.2.0(TMS 1단계): 운송사·차량·기사·점유 예약. 정본 TMS 4.4·4.5·7.5.
CREATE TABLE carrier (
    carrier_id   varchar(32)  NOT NULL,
    name         varchar(60)  NOT NULL,
    active       boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT pk_carrier PRIMARY KEY (carrier_id)
);

CREATE TABLE vehicle (
    vehicle_id     varchar(32)  NOT NULL,
    carrier_id     varchar(32)  NOT NULL,
    plate_no       varchar(20),
    contract_type  varchar(16)  NOT NULL,   -- REGULAR / SPOT
    temp_class     varchar(16)  NOT NULL,   -- AMBIENT / CHILLED / FROZEN (한 대에 하나 — 정본 D-06)
    tonnage        numeric(5,1) NOT NULL,
    max_rt         integer      NOT NULL,
    max_weight_kg  integer      NOT NULL,
    active         boolean      NOT NULL DEFAULT true,
    version        integer      NOT NULL DEFAULT 1,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_vehicle PRIMARY KEY (vehicle_id)
);

-- 기사: 표시 이름만(실명·연락처·면허 번호 같은 개인정보는 두지 않는다)
CREATE TABLE driver (
    driver_id     varchar(32)  NOT NULL,
    carrier_id    varchar(32)  NOT NULL,
    display_name  varchar(30)  NOT NULL,
    active        boolean      NOT NULL DEFAULT true,
    version       integer      NOT NULL DEFAULT 1,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT pk_driver PRIMARY KEY (driver_id)
);

-- 점유: [window_start, window_end) — 차량·기사 따로 겹침 검사(행 잠금 대신 권고 잠금으로 줄 세움)
CREATE TABLE fleet_reservation (
    reservation_id  varchar(64)  NOT NULL,
    vehicle_id      varchar(32)  NOT NULL,
    driver_id       varchar(32)  NOT NULL,
    window_start    timestamptz  NOT NULL,
    window_end      timestamptz  NOT NULL,
    purpose_ref     varchar(64)  NOT NULL,
    state           varchar(16)  NOT NULL,  -- HELD / RELEASED
    release_reason  varchar(16),
    created_at      timestamptz  NOT NULL,
    released_at     timestamptz,
    CONSTRAINT pk_fleet_reservation PRIMARY KEY (reservation_id),
    CONSTRAINT ck_fleet_reservation_window CHECK (window_start < window_end)
);
CREATE INDEX ix_fleet_reservation_vehicle ON fleet_reservation (vehicle_id, state);
CREATE INDEX ix_fleet_reservation_driver ON fleet_reservation (driver_id, state);
CREATE UNIQUE INDEX ux_fleet_reservation_purpose_held ON fleet_reservation (purpose_ref) WHERE state = 'HELD';
