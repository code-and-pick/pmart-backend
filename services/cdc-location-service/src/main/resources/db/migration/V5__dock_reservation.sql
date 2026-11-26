-- 0.8.0 도크 예약(DC 백오피스 TR-3 — 사용자 "계획완료 때 자동"): DOCK 칸 × [시작, 끝) 겹침 금지(살아 있는 RESERVED 끼리).
CREATE TABLE dock_reservation (
    dock_reservation_id  varchar(64)  NOT NULL,
    center_id            varchar(32)  NOT NULL,
    dock_code            varchar(40)  NOT NULL,
    trip_id              varchar(64)  NOT NULL,
    purpose              varchar(24)  NOT NULL,
    temperature_zone     varchar(16),
    vehicle_id           varchar(32),
    route_id             varchar(16),
    plate_no             varchar(20),
    window_start         timestamptz  NOT NULL,
    window_end           timestamptz  NOT NULL,
    state                varchar(16)  NOT NULL,   -- RESERVED / RELEASED
    release_reason       varchar(16),
    version              integer      NOT NULL DEFAULT 1,
    created_at           timestamptz  NOT NULL,
    released_at          timestamptz,
    CONSTRAINT pk_dock_reservation PRIMARY KEY (dock_reservation_id),
    CONSTRAINT ck_dock_reservation_window CHECK (window_start < window_end)
);
CREATE INDEX ix_dock_reservation_dock ON dock_reservation (dock_code, state, window_start);
CREATE INDEX ix_dock_reservation_center ON dock_reservation (center_id, window_start);
CREATE UNIQUE INDEX ux_dock_reservation_trip ON dock_reservation (trip_id, purpose) WHERE state = 'RESERVED';
