-- CDC 재고 장부(0.2.0, 2026-11-25): 초기 보유·안전량·할당(예약 R)·TOUT(Transit)·원결과(commit).
-- 근거 docs/business/WMS-business-logic.md CDC-F-003(초기 보유)·F-008(가용 max(0, E−R−S))·F-009(FEFO)·F-016(할당)·F-021(출발 전기).
-- 위치 코드는 cdc-location 정본(아직 없음) — 여기서는 문자열 사본으로만 둔다. 적용된 파일은 수정하지 않는다.

-- LOT(센터·SKU·LOT). 소비기한·식별 신뢰도·최초 정상 인수시각(FEFO 동률 순서 — 초기 보유는 반영 시각).
CREATE TABLE stock_lot (
    cdc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    expires_at          date,                           -- NULL = 기한 미관리(정렬 맨 뒤)
    identification      varchar(16)  NOT NULL,          -- UNVERIFIED / VERIFIED / INVALID — VERIFIED 만 적격
    first_received_at   timestamptz  NOT NULL,
    origin              varchar(32)  NOT NULL,          -- INITIAL_BALANCE (입고가 생기면 늘어난다)
    CONSTRAINT pk_stock_lot PRIMARY KEY (cdc_id, sku, lot),
    CONSTRAINT ck_stock_lot_identification CHECK (identification IN ('UNVERIFIED', 'VERIFIED', 'INVALID'))
);

-- 위치·품질 상태별 현물과 예약. 예약은 정상(RELEASED) 현물에만 걸린다: 0 ≤ reserved ≤ on_hand.
CREATE TABLE stock_balance (
    cdc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    quality_state       varchar(16)  NOT NULL,          -- RELEASED / HELD / QUARANTINED
    on_hand             integer      NOT NULL,
    reserved            integer      NOT NULL DEFAULT 0,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_stock_balance PRIMARY KEY (cdc_id, sku, lot, location_code, quality_state),
    CONSTRAINT ck_stock_balance_qty CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand),
    CONSTRAINT ck_stock_balance_reserved_released CHECK (quality_state = 'RELEASED' OR reserved = 0),
    CONSTRAINT ck_stock_balance_quality CHECK (quality_state IN ('RELEASED', 'HELD', 'QUARANTINED'))
);
CREATE INDEX ix_stock_balance_sku ON stock_balance (cdc_id, sku);

-- 승인 안전량 S(센터·SKU 합계 보호량). 행이 없으면 미설정(0 이 아니다 — CONFIG_REQUIRED).
CREATE TABLE safety_stock (
    cdc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    safety_qty          integer      NOT NULL,
    policy_version      integer      NOT NULL,
    approval_ref        varchar(128) NOT NULL,
    effective_from      timestamptz  NOT NULL,
    CONSTRAINT pk_safety_stock PRIMARY KEY (cdc_id, sku),
    CONSTRAINT ck_safety_stock_qty CHECK (safety_qty >= 0)
);

-- 승인 TRF 할당(trfId·trfVersion 당 1회).
CREATE TABLE allocation (
    allocation_id       varchar(64)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    trf_version         integer      NOT NULL,
    state               varchar(16)  NOT NULL,          -- ALLOCATED / PARTIAL / RELEASED / DEPARTED / FAILED
    row_version         integer      NOT NULL,
    commit_id           varchar(64)  NOT NULL,
    allocated_at        timestamptz  NOT NULL,
    CONSTRAINT pk_allocation PRIMARY KEY (allocation_id),
    CONSTRAINT uq_allocation_trf UNIQUE (trf_id, trf_version)
);
CREATE INDEX ix_allocation_trf ON allocation (trf_id);

CREATE TABLE allocation_line (
    allocation_id       varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    requested_qty       integer      NOT NULL,
    line_no             integer      NOT NULL,
    CONSTRAINT pk_allocation_line PRIMARY KEY (allocation_id, sku)
);

-- LOT·위치별 할당 수량. 남은 할당 = allocated − released − departed.
CREATE TABLE allocation_lot (
    allocation_id       varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    expires_at          date,
    fefo_rank           integer      NOT NULL,          -- 할당 때의 FEFO 순서(해제는 역순)
    allocated_qty       integer      NOT NULL,
    released_qty        integer      NOT NULL DEFAULT 0,
    departed_qty        integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_allocation_lot PRIMARY KEY (allocation_id, sku, lot, location_code),
    CONSTRAINT ck_allocation_lot_qty CHECK (allocated_qty > 0 AND released_qty >= 0 AND departed_qty >= 0
        AND released_qty + departed_qty <= allocated_qty)
);

-- Transit(TOUT 1회 — TRF 당 하나). q = r + l + t.
CREATE TABLE transit (
    trf_id              varchar(64)  NOT NULL,
    trf_version         integer      NOT NULL,
    departure_id        varchar(64)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    to_mfc_id           varchar(32),
    allocation_id       varchar(64)  NOT NULL,
    state               varchar(32)  NOT NULL,          -- TRANSIT_OPEN / RECEIPT_POSTED_AWAITING_ACK / TRANSIT_RECONCILED
    departure_source    varchar(16)  NOT NULL,          -- REAL / SIMULATED
    shipment_manifest_id varchar(64),
    commit_id           varchar(64)  NOT NULL,
    departed_at         timestamptz  NOT NULL,
    reconciled_at       timestamptz,
    CONSTRAINT pk_transit PRIMARY KEY (trf_id)
);
CREATE INDEX ix_transit_departure ON transit (departure_id);
CREATE INDEX ix_transit_state ON transit (state, trf_id);

CREATE TABLE transit_line (
    departure_line_id   varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    line_no             integer      NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    q                   integer      NOT NULL,
    normal_qty          integer      NOT NULL DEFAULT 0,
    quarantined_qty     integer      NOT NULL DEFAULT 0,
    loss_qty            integer      NOT NULL DEFAULT 0,
    lpns                text,                           -- 쉼표 구분(조회·사건용 사본)
    CONSTRAINT pk_transit_line PRIMARY KEY (departure_line_id),
    CONSTRAINT ck_transit_line_qty CHECK (q > 0 AND normal_qty + quarantined_qty + loss_qty <= q)
);
CREATE INDEX ix_transit_line_trf ON transit_line (trf_id, line_no);

-- 원결과(모든 전기 명령의 짝 조회 — UNKNOWN_RESULT 해소).
CREATE TABLE inventory_commit (
    commit_id           varchar(64)  NOT NULL,
    request_id          varchar(128) NOT NULL,
    posting_kind        varchar(32)  NOT NULL,          -- ALLOCATION / RELEASE / TOUT / INITIAL_BALANCE / ...
    status              varchar(16)  NOT NULL,
    source_system       varchar(64)  NOT NULL,
    source_ref          varchar(128),
    cdc_id              varchar(32)  NOT NULL,
    applied_at          timestamptz  NOT NULL,
    CONSTRAINT pk_inventory_commit PRIMARY KEY (commit_id)
);

CREATE TABLE inventory_commit_delta (
    commit_id           varchar(64)  NOT NULL,
    seq                 integer      NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40),
    bucket              varchar(16)  NOT NULL,          -- ON_HAND / RESERVED / HELD / TRANSIT
    delta               integer      NOT NULL,
    CONSTRAINT pk_inventory_commit_delta PRIMARY KEY (commit_id, seq)
);
