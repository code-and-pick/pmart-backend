-- 재계수 대기(0.7.0, 2026-11-25 — WMS CDC-F-018, 사용자 "부족 칸은 새 할당에서 뺀다"). 적용된 파일은 수정하지 않는다.
-- 피킹 부족이 난 SKU·LOT·위치. HELD 동안 새 할당·추가 할당 후보에서 빠진다. 현물·이미 걸린 예약은 그대로(손실 차감 없음).
CREATE TABLE recount_hold (
    hold_id             varchar(64)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    state               varchar(16)  NOT NULL,          -- HELD / RELEASED
    short_qty           integer      NOT NULL,          -- 보고된 부족 합(대기 중 다시 부족이 나면 더한다)
    allocation_id       varchar(64),
    source_ref          varchar(128),
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    released_at         timestamptz,
    released_by         varchar(128),
    release_reason      varchar(300),
    CONSTRAINT pk_recount_hold PRIMARY KEY (hold_id),
    CONSTRAINT ck_recount_hold_state CHECK (state IN ('HELD', 'RELEASED')),
    CONSTRAINT ck_recount_hold_qty CHECK (short_qty >= 0)
);
CREATE UNIQUE INDEX uq_recount_hold_open ON recount_hold (cdc_id, sku, lot, location_code) WHERE state = 'HELD';
CREATE INDEX ix_recount_hold_center ON recount_hold (cdc_id, state, created_at);
