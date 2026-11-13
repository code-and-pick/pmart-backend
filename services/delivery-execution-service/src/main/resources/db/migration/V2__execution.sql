-- 배송 수행(0.2.0, PR #8 H2 — 2026-11-13): 배정 사건 → 가게 도착 → 픽업 완료(모든 포장 PackageHandedOver, DE-1) → 고객 도착 → 증빙 사진 → 전달 완료.
-- 배정·포장·인계는 다른 서비스 사건으로만 받는다(서비스 간 DB 접근 금지). 주소·좌표는 두지 않는다.

CREATE TABLE execution_delivery (
    delivery_id         VARCHAR(64)  PRIMARY KEY,
    version             INT          NOT NULL DEFAULT 0,
    type                VARCHAR(16)  NOT NULL DEFAULT 'FORWARD',
    order_id            VARCHAR(64),
    shipment_id         VARCHAR(64),
    mfc_id              VARCHAR(64),
    state               VARCHAR(24)  NOT NULL CHECK (state IN ('UNASSIGNED', 'ASSIGNED', 'ARRIVED_AT_STORE',
        'PICKED_UP', 'ARRIVED_AT_CUSTOMER', 'COMPLETED', 'FAILED', 'SUSPENDED', 'RETURN_IN_PROGRESS',
        'RETURN_PICKED_UP', 'RETURNED_TO_STORE', 'CANCELLED')),
    rider_id            VARCHAR(64),
    assignment_version  INT          NOT NULL DEFAULT 0,
    assigned_at         TIMESTAMPTZ,
    picked_up_at        TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    method              VARCHAR(16),
    evidence            JSONB,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX execution_delivery_rider ON execution_delivery (rider_id, assigned_at DESC);
CREATE INDEX execution_delivery_shipment ON execution_delivery (shipment_id);

-- 출고의 포장 목록(ShipmentReady·PackingFinished — 배송 생성보다 먼저 올 수 있어 출고 기준으로 따로 둔다)
CREATE TABLE shipment_package (
    shipment_id   VARCHAR(64)  PRIMARY KEY,
    package_ids   JSONB        NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- 인계 사실(PackageHandedOver). 같은 handoverId 는 한 번. 배송 사건보다 먼저 와도 남긴다(실물 사실 — 폐기하지 않음)
CREATE TABLE package_handover (
    handover_id   VARCHAR(64)  PRIMARY KEY,
    delivery_id   VARCHAR(64)  NOT NULL,
    package_id    VARCHAR(64)  NOT NULL,
    rider_id      VARCHAR(64),
    lines         JSONB        NOT NULL,
    handed_at     TIMESTAMPTZ  NOT NULL,
    recorded_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX package_handover_delivery ON package_handover (delivery_id);

-- 상태 이력(새 사실만 더한다)
CREATE TABLE execution_history (
    id            BIGSERIAL    PRIMARY KEY,
    delivery_id   VARCHAR(64)  NOT NULL,
    state         VARCHAR(24)  NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    recorded_at   TIMESTAMPTZ  NOT NULL,
    actor         VARCHAR(16)  NOT NULL CHECK (actor IN ('RIDER', 'SYSTEM_EVENT', 'OPERATOR')),
    source_ref    VARCHAR(128)
);
CREATE INDEX execution_history_delivery ON execution_history (delivery_id, id);

-- 전달 증빙 사진(원본은 비공개 S3 호환 저장소, 여기는 참조만)
CREATE TABLE evidence_photo (
    photo_ref     VARCHAR(64)  PRIMARY KEY,
    delivery_id   VARCHAR(64)  NOT NULL,
    rider_id      VARCHAR(64)  NOT NULL,
    object_key    VARCHAR(256) NOT NULL,
    content_type  VARCHAR(32)  NOT NULL,
    size_bytes    INT          NOT NULL,
    uploaded_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX evidence_photo_delivery ON evidence_photo (delivery_id);
