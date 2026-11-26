-- 적치·보충 작업(cdc-inbound 0.2.0, 2026-11-26 — WMS CDC-F-010~013, 사가 S7, 사용자 "입고·적치·보충"·"적치 지시까지"·"박스 입수"). 적용된 파일은 수정하지 않는다.
-- 수량 정본은 cdc-inventory(이동 전기), 공간 예약 정본은 cdc-location — 여기는 작업·결과·판단 기록.
CREATE TABLE putaway_task (
    task_id             varchar(40)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    kind                varchar(16)  NOT NULL,          -- PUTAWAY / REPLENISHMENT
    state               varchar(24)  NOT NULL,          -- CREATED / RESERVED / DISPATCHED / IN_PROGRESS / PARTIALLY_COMPLETED / COMPLETED / FAILED / CANCELLED
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    quality_state       varchar(16)  NOT NULL,
    from_location       varchar(40)  NOT NULL,
    to_location         varchar(40)  NOT NULL,
    qty                 integer      NOT NULL,
    completed_qty       integer      NOT NULL DEFAULT 0,
    reservation_id      varchar(40),
    device_id           varchar(64),
    created_by          varchar(128) NOT NULL,
    reason              varchar(300),
    failure_reason      varchar(500),
    space_release_pending boolean  NOT NULL DEFAULT false, -- 남은 공간 예약 해제를 다시 해야 함
    request_key         varchar(300),                   -- 운영자 지시의 요청 키(출처·Idempotency-Key·운영자) — 한 번
    request_hash        varchar(64),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_putaway_task PRIMARY KEY (task_id),
    CONSTRAINT uq_putaway_task_request UNIQUE (request_key),
    CONSTRAINT ck_putaway_task_qty CHECK (qty > 0 AND completed_qty >= 0 AND completed_qty <= qty)
);
CREATE INDEX ix_putaway_task_queue ON putaway_task (cdc_id, state, created_at);
CREATE INDEX ix_putaway_task_to ON putaway_task (to_location, sku, state);

-- 가상 설비 결과 — eventId 한 번(같은 본문 재전송 = 같은 응답)
CREATE TABLE work_result (
    event_id            varchar(64)  NOT NULL,
    task_id             varchar(40)  NOT NULL,
    device_id           varchar(64)  NOT NULL,
    status              varchar(16)  NOT NULL,          -- SUCCESS / PARTIAL / FAILED
    completed_qty       integer      NOT NULL,
    failure_reason      varchar(300),
    body_hash           varchar(64)  NOT NULL,
    state               varchar(16)  NOT NULL,          -- PENDING(이동 전) / MOVED(이동 됨·예약 소비 전) / APPLIED / REJECTED
    commit_id           varchar(64),
    reject_reason       varchar(500),
    received_at         timestamptz  NOT NULL,
    CONSTRAINT pk_work_result PRIMARY KEY (event_id)
);
CREATE INDEX ix_work_result_task ON work_result (task_id);
CREATE INDEX ix_work_result_pending ON work_result (received_at) WHERE state = 'PENDING';

-- 자동 보충 판단 — 피킹 선반마다 마지막 계산(지시량 또는 0 의 사유)
CREATE TABLE replenishment_check (
    location_code       varchar(40)  NOT NULL,
    cdc_id              varchar(32)  NOT NULL,
    sku                 varchar(10),
    current_qty         integer,
    min_qty             integer      NOT NULL,
    target_qty          integer      NOT NULL,
    open_qty            integer,
    need_qty            integer,
    ordered_qty         integer,
    unit_qty            integer,
    reason              varchar(24)  NOT NULL,
    task_id             varchar(40),
    checked_at          timestamptz  NOT NULL,
    CONSTRAINT pk_replenishment_check PRIMARY KEY (location_code)
);
CREATE INDEX ix_replenishment_check_cdc ON replenishment_check (cdc_id, location_code);
