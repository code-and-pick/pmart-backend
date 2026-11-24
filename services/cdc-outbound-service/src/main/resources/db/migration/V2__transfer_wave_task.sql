-- cdc-outbound 0.3.0(2026-11-25): 승인 TRF 인수·Wave·피킹 Task. 근거 WMS-business-logic CDC-F-016~018, DC Wave 문서.
-- 수량 정본은 cdc-inventory(할당·해제·TOUT) — 여기의 할당 수량·LOT·위치는 그 응답의 사본이다. 적용된 파일은 수정하지 않는다.

-- 승인 TRF(1요청·1이관·1출발). 같은 trfId 의 버전은 하나만 유지한다(대체는 아직 없음 — 0.3.0).
CREATE TABLE transfer (
    trf_id              varchar(64)  NOT NULL,
    trf_version         integer      NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    approval_ref        varchar(128) NOT NULL,
    deadline_meaning    varchar(16)  NOT NULL,          -- ARRIVAL / HANDOVER / READY
    due_at              timestamptz  NOT NULL,
    body_hash           varchar(64)  NOT NULL,          -- 같은 버전·다른 본문 판정
    state               varchar(24)  NOT NULL,          -- RECEIVED / ALLOCATING / ALLOCATED / PARTIALLY_ALLOCATED / ALLOCATION_FAILED / IN_WAVE / PICKED / ...
    allocation_id       varchar(64),
    allocation_error    varchar(500),                   -- 재고 쪽 거절 사유(코드: 설명)
    wave_id             varchar(64),
    row_version         integer      NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_transfer PRIMARY KEY (trf_id)
);
CREATE INDEX ix_transfer_wave ON transfer (wave_id);

CREATE TABLE transfer_line (
    trf_id              varchar(64)  NOT NULL,
    line_no             integer      NOT NULL,
    sku                 varchar(10)  NOT NULL,
    approved_qty        integer      NOT NULL,
    allocated_qty       integer      NOT NULL DEFAULT 0,
    picked_qty          integer      NOT NULL DEFAULT 0,
    short_qty           integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_transfer_line PRIMARY KEY (trf_id, sku),
    CONSTRAINT ck_transfer_line_qty CHECK (approved_qty > 0 AND allocated_qty >= 0 AND picked_qty >= 0 AND short_qty >= 0
        AND allocated_qty <= approved_qty AND picked_qty + short_qty <= allocated_qty)
);

-- Wave(DRAFT→PLANNED→RELEASED→IN_PROGRESS→WORK_DONE→CLOSED). 0.3.0 은 PLANNED 로 만든다.
CREATE TABLE wave (
    wave_id             varchar(64)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    state               varchar(16)  NOT NULL,
    row_version         integer      NOT NULL,
    temperature_zone    varchar(16),
    window_start        timestamptz,
    window_end          timestamptz,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    released_at         timestamptz,
    CONSTRAINT pk_wave PRIMARY KEY (wave_id)
);

-- 피킹 Task(TRF × 구역 × 층). CREATED → (다음 작업 받기) ACCEPTED → (착수) IN_PROGRESS → DONE / PARTIAL.
CREATE TABLE task (
    task_id             varchar(64)  NOT NULL,
    wave_id             varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    kind                varchar(8)   NOT NULL,          -- PICK / SORT / INSPECT / LOAD
    zone                varchar(4),
    floor               varchar(4),
    due_at              timestamptz  NOT NULL,
    state               varchar(16)  NOT NULL,
    row_version         integer      NOT NULL,
    assignee_id         varchar(128),
    created_at          timestamptz  NOT NULL,
    seq                 bigint       GENERATED ALWAYS AS IDENTITY, -- 같은 기한이면 만든 순
    accepted_at         timestamptz,
    started_at          timestamptz,
    completed_at        timestamptz,
    CONSTRAINT pk_task PRIMARY KEY (task_id)
);
CREATE INDEX ix_task_queue ON task (cdc_id, state, due_at, seq) WHERE assignee_id IS NULL;
CREATE INDEX ix_task_assignee ON task (assignee_id, state);
CREATE INDEX ix_task_wave ON task (wave_id, state);

CREATE TABLE task_line (
    task_id             varchar(64)  NOT NULL,
    line_no             integer      NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    planned_qty         integer      NOT NULL,
    picked_qty          integer      NOT NULL DEFAULT 0,
    short_qty           integer      NOT NULL DEFAULT 0,
    short_reason        varchar(32),
    CONSTRAINT pk_task_line PRIMARY KEY (task_id, line_no),
    CONSTRAINT uq_task_line_key UNIQUE (task_id, sku, lot, location_code),
    CONSTRAINT ck_task_line_qty CHECK (planned_qty > 0 AND picked_qty >= 0 AND short_qty >= 0 AND picked_qty + short_qty <= planned_qty)
);
