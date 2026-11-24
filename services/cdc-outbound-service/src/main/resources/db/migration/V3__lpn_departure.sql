-- cdc-outbound 0.4.0(2026-11-25): 분류·검품·상차(LPN)와 출발 검증·매니페스트. 근거 WMS CDC-F-019~021, TMS 정본 D-07(R/T 적재·박스 검증).
-- 수량은 BOX 의 contents 만 가진다. RT(롤테이너)·PLT 는 담는 그릇이고 하위 BOX 는 parent_lpn 으로 잇는다. 적용된 파일은 수정하지 않는다.
CREATE TABLE lpn (
    lpn                 varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    wave_id             varchar(64)  NOT NULL,
    unit_type           varchar(8)   NOT NULL,          -- BOX / PLT / RT
    parent_lpn          varchar(64),
    seal_state          varchar(8)   NOT NULL,          -- SEALED / READY
    seal_no             varchar(64),
    temperature_zone    varchar(16),
    weight_g            integer,
    sorted_by           varchar(128) NOT NULL,
    sorted_at           timestamptz  NOT NULL,
    inspection          varchar(8),                     -- PASSED / FAILED (BOX 에 남는다)
    fail_reason         varchar(32),
    inspected_at        timestamptz,
    loaded              boolean      NOT NULL DEFAULT false,
    trip_id             varchar(64),
    vehicle_id          varchar(64),
    dock_code           varchar(40),
    compartment         varchar(32),
    load_seal_no        varchar(64),
    loaded_at           timestamptz,
    departure_id        varchar(64),
    CONSTRAINT pk_lpn PRIMARY KEY (lpn)
);
CREATE INDEX ix_lpn_trf ON lpn (trf_id, unit_type);
CREATE INDEX ix_lpn_parent ON lpn (parent_lpn);
CREATE INDEX ix_lpn_trip ON lpn (trip_id) WHERE loaded;

CREATE TABLE lpn_content (
    lpn                 varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    qty                 integer      NOT NULL,
    CONSTRAINT pk_lpn_content PRIMARY KEY (lpn, sku, lot),
    CONSTRAINT ck_lpn_content_qty CHECK (qty > 0)
);

-- 분류·검품·상차 결과(명령 짝 조회).
CREATE TABLE work_result (
    work_result_id      varchar(64)  NOT NULL,
    kind                varchar(16)  NOT NULL,          -- SORTING / INSPECTION / LOADING
    trf_id              varchar(64)  NOT NULL,
    status              varchar(16)  NOT NULL,
    reject_reason       varchar(500),
    recorded_at         timestamptz  NOT NULL,
    CONSTRAINT pk_work_result PRIMARY KEY (work_result_id)
);

-- 출발 검증(departureId 당 1회).
CREATE TABLE departure (
    departure_id        varchar(64)  NOT NULL,
    trip_id             varchar(64)  NOT NULL,
    vehicle_id          varchar(64)  NOT NULL,
    source              varchar(16)  NOT NULL,          -- REAL / SIMULATED
    departed_at         timestamptz  NOT NULL,
    evidence_ref        varchar(200),
    body_hash           varchar(64)  NOT NULL,
    overall_result      varchar(24)  NOT NULL,          -- MATCHED / PARTIALLY_MATCHED / MISMATCHED
    validated_at        timestamptz  NOT NULL,
    CONSTRAINT pk_departure PRIMARY KEY (departure_id)
);

CREATE TABLE departure_transfer (
    departure_id        varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    line_no             integer      NOT NULL,
    mfc_id              varchar(32),
    result              varchar(16)  NOT NULL,          -- MATCHED / MISMATCHED
    departed_qty        integer      NOT NULL DEFAULT 0,
    posting_status      varchar(16),                    -- PENDING / APPLIED / REJECTED / UNKNOWN_RESULT (일치 TRF 만)
    commit_id           varchar(64),
    reject_reason       varchar(500),
    manifest_id         varchar(64),
    CONSTRAINT pk_departure_transfer PRIMARY KEY (departure_id, trf_id),
    CONSTRAINT uq_departure_transfer_trf UNIQUE (trf_id)     -- 같은 TRF 2회 출발 금지(1요청·1이관·1출발)
);

CREATE TABLE departure_mismatch (
    departure_id        varchar(64)  NOT NULL,
    seq                 integer      NOT NULL,
    kind                varchar(32)  NOT NULL,
    lpn                 varchar(64),
    trf_id              varchar(64),
    detail              varchar(500),
    CONSTRAINT pk_departure_mismatch PRIMARY KEY (departure_id, seq)
);

-- 매니페스트 줄(BOX 마다, TOUT 반영 뒤 고정).
CREATE TABLE manifest_line (
    manifest_id         varchar(64)  NOT NULL,
    seq                 integer      NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    lpn                 varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    expires_at          date,
    qty                 integer      NOT NULL,
    CONSTRAINT pk_manifest_line PRIMARY KEY (manifest_id, seq)
);
CREATE INDEX ix_manifest_line_trf ON manifest_line (trf_id);
