-- 배송·배차 시도·제안(2026-11-13, DD-1 첫 묶음 — delivery-dispatch-service.yaml 0.2.0, ADR-0058).
-- 배송 1 ↔ 출고 1(D6-2): shipment_id 유일 — 같은 출고의 배차 요청이 두 번 와도 배송은 하나(kickoff §6).
-- 배송지 주소 원문은 저장하지 않는다 — 좌표와 동 이름만(order O-4 → serviceability 정확 일치 변환).
CREATE TABLE delivery (
    delivery_id          varchar(64)      NOT NULL,
    version              integer          NOT NULL DEFAULT 0,
    type                 varchar(16)      NOT NULL,
    order_id             varchar(64)      NOT NULL,
    shipment_id          varchar(96)      NOT NULL,
    mfc_id               varchar(64)      NOT NULL,
    state                varchar(32)      NOT NULL,
    delivery_mode        varchar(16),
    arrival_date         date,
    arrival_start        varchar(5),
    arrival_end          varchar(5),
    request_trigger      varchar(16),                 -- MANUAL / AUTO_ON_READY (DispatchRequested)
    estimated_ready_at   timestamptz,
    ready_at             timestamptz,                 -- ShipmentReady
    packages             jsonb            NOT NULL DEFAULT '[]'::jsonb,
    mfc_lat              double precision,
    mfc_lng              double precision,
    drop_lat             double precision,
    drop_lng             double precision,
    drop_area_label      varchar(100),
    drop_distance_m      integer,                     -- MFC → 고객 도로 거리(카카오모빌리티)
    drop_duration_s      integer,
    geo_error            varchar(64),                 -- 좌표·거리 준비 실패 사유(다시 시도)
    rider_id             varchar(64),
    assignment_id        varchar(64),
    assignment_version   integer          NOT NULL DEFAULT 0,
    assigned_at          timestamptz,
    accepted_offer_id    varchar(64),
    picked_up_at         timestamptz,
    handed_over_packages jsonb            NOT NULL DEFAULT '[]'::jsonb,
    cancelled_at         timestamptz,
    cancel_reason        varchar(32),
    created_at           timestamptz      NOT NULL,
    updated_at           timestamptz      NOT NULL,
    CONSTRAINT pk_delivery PRIMARY KEY (delivery_id),
    CONSTRAINT uq_delivery_shipment UNIQUE (shipment_id),
    CONSTRAINT ck_delivery_state CHECK (state IN ('CREATED', 'DISPATCHING', 'ASSIGNED', 'PICKED_UP',
        'CANCELLED_BEFORE_PICKUP', 'DISPATCH_FAILED', 'CLOSED', 'BLOCKED'))
);
CREATE INDEX ix_delivery_order ON delivery (order_id);
CREATE INDEX ix_delivery_rider_active ON delivery (rider_id) WHERE state = 'ASSIGNED';
CREATE INDEX ix_delivery_dispatching ON delivery (mfc_id) WHERE state = 'DISPATCHING';

-- 배차 시도(원문 §3.2 배차시도번호). 배송마다 진행 중 시도 하나.
CREATE TABLE dispatch_attempt (
    attempt_id       varchar(64)  NOT NULL,
    delivery_id      varchar(64)  NOT NULL,
    trigger          varchar(16)  NOT NULL,           -- AUTO / RETRY
    state            varchar(16)  NOT NULL,           -- SEARCHING / OFFERING / ASSIGNED / FAILED / CANCELLED
    stage            integer      NOT NULL DEFAULT 1, -- 반경 단계 1·2·3
    stage_started_at timestamptz  NOT NULL,
    retry_count      integer      NOT NULL DEFAULT 0,
    round            integer      NOT NULL DEFAULT 0, -- 재요청 회차(같은 회차 안에서 이미 제안한 라이더는 빼고 찾는다)
    next_run_at      timestamptz  NOT NULL,
    started_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    failure_reason   varchar(32),
    had_candidate    boolean      NOT NULL DEFAULT false,
    had_rejection    boolean      NOT NULL DEFAULT false,
    had_expiry       boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_dispatch_attempt PRIMARY KEY (attempt_id),
    CONSTRAINT fk_dispatch_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (delivery_id)
);
CREATE UNIQUE INDEX uq_dispatch_attempt_open ON dispatch_attempt (delivery_id)
    WHERE state IN ('SEARCHING', 'OFFERING');
CREATE INDEX ix_dispatch_attempt_due ON dispatch_attempt (next_run_at) WHERE state = 'SEARCHING';

-- 제안(제안번호) — 거절·미응답 만료·전송 실패를 구분해 남긴다(원문 §3.2·§4.4).
CREATE TABLE dispatch_offer (
    offer_id          varchar(64)  NOT NULL,
    attempt_id        varchar(64)  NOT NULL,
    delivery_id       varchar(64)  NOT NULL,
    rider_id          varchar(64)  NOT NULL,
    round             integer      NOT NULL,
    source            varchar(16)  NOT NULL,          -- AI_RECOMMENDED / DELIVERY_LIST
    radius_km         numeric(4,1) NOT NULL,
    state             varchar(16)  NOT NULL,
    sent_at           timestamptz  NOT NULL,
    expires_at        timestamptz  NOT NULL,
    viewed_at         timestamptz,
    decided_at        timestamptz,
    reject_reason     varchar(16),
    late_reason       varchar(24),
    pickup_distance_m integer,
    pickup_duration_s integer,
    fee               integer      NOT NULL,
    vehicle_type      varchar(16),
    CONSTRAINT pk_dispatch_offer PRIMARY KEY (offer_id),
    CONSTRAINT fk_dispatch_offer_attempt FOREIGN KEY (attempt_id) REFERENCES dispatch_attempt (attempt_id)
);
CREATE INDEX ix_dispatch_offer_rider ON dispatch_offer (rider_id, sent_at DESC);
CREATE INDEX ix_dispatch_offer_open ON dispatch_offer (expires_at) WHERE state = 'SENT';
CREATE INDEX ix_dispatch_offer_attempt ON dispatch_offer (attempt_id, round);

-- 라이더별 연속 거절(원문 §4.4 — 5회면 신규배차 꺼 달라는 안내). 수락하면 0.
CREATE TABLE rider_rejection (
    rider_id     varchar(64) NOT NULL,
    consecutive  integer     NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT pk_rider_rejection PRIMARY KEY (rider_id)
);
