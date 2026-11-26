-- 공간 예약(0.7.0, 2026-11-26 — cdc-inbound 적치·보충, 사가 S7 1단계 — WMS CDC-F-010 "목적지 용량 예약"). 적용된 파일은 수정하지 않는다.
-- 검사: 부르는 쪽이 준 그 칸 현물(occupiedQty — 점유 정본은 cdc-inventory) + 그 칸 미완료 예약 + 신규 ≤ location.max_qty. 자동 만료 없음.
CREATE TABLE space_reservation (
    reservation_id      varchar(40)  NOT NULL,
    center_id           varchar(32)  NOT NULL,
    source_ref          varchar(64)  NOT NULL,
    body_hash           varchar(64)  NOT NULL,
    state               varchar(24)  NOT NULL,          -- RESERVED / PARTIALLY_CONSUMED / CONSUMED / RELEASED
    version             integer      NOT NULL,
    expires_at          timestamptz,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_space_reservation PRIMARY KEY (reservation_id),
    CONSTRAINT uq_space_reservation_source UNIQUE (center_id, source_ref)
);

CREATE TABLE space_reservation_line (
    reservation_id      varchar(40)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64),
    reserved_qty        integer      NOT NULL,
    consumed_qty        integer      NOT NULL DEFAULT 0,
    released_qty        integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_space_reservation_line PRIMARY KEY (reservation_id, location_code),
    CONSTRAINT ck_space_reservation_line CHECK (reserved_qty > 0 AND consumed_qty >= 0 AND released_qty >= 0
        AND consumed_qty + released_qty <= reserved_qty)
);
CREATE INDEX ix_space_reservation_line_loc ON space_reservation_line (location_code);
