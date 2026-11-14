-- 컴플레인 누적 → 제한 검토 대상(RS-3, 사용자 2026-11-14 "비슷한 종류의 컴플레인이 일주일 이내에 3건 이상", "신고, 오배송, 파손 전부").
-- 원천: case RiderComplaintRecorded(platform.case.v1 — case 쪽 구현 전, service-requests case R-5). 자동 제재는 하지 않는다 —
-- 검토 대상만 올리고 운영자가 확인 뒤 계정 제한(RS-1 API). 사유·내용은 받지 않는다(종류·배송·시각만).
CREATE TABLE rider_complaint (
    complaint_id    varchar(128) NOT NULL,
    rider_id        varchar(64)  NOT NULL,
    delivery_id     varchar(64)  NOT NULL,
    complaint_type  varchar(16)  NOT NULL,
    case_id         varchar(128),
    recorded_at     timestamptz  NOT NULL,
    received_at     timestamptz  NOT NULL,
    review_id       varchar(64),            -- 묶인 검토(처리된 검토에 묶인 건은 다시 세지 않는다)
    CONSTRAINT pk_rider_complaint PRIMARY KEY (complaint_id),
    CONSTRAINT ck_rider_complaint_type CHECK (complaint_type IN ('REPORT', 'MISDELIVERY', 'DAMAGE'))
);
CREATE INDEX ix_rider_complaint_rider ON rider_complaint (rider_id, complaint_type, recorded_at);

CREATE TABLE rider_restriction_review (
    review_id       varchar(64)  NOT NULL,
    rider_id        varchar(64)  NOT NULL,
    complaint_type  varchar(16)  NOT NULL,
    state           varchar(16)  NOT NULL DEFAULT 'OPEN',
    opened_at       timestamptz  NOT NULL,
    decided_by      varchar(64),
    decided_at      timestamptz,
    decision_note   varchar(300),
    CONSTRAINT pk_rider_restriction_review PRIMARY KEY (review_id),
    CONSTRAINT ck_rider_restriction_review_state CHECK (state IN ('OPEN', 'RESTRICTED', 'DISMISSED'))
);
-- 라이더·종류마다 열린 검토는 하나 — 열려 있는 동안 온 같은 종류 컴플레인은 거기에 묶는다
CREATE UNIQUE INDEX uq_rider_restriction_review_open ON rider_restriction_review (rider_id, complaint_type) WHERE state = 'OPEN';
CREATE INDEX ix_rider_restriction_review_state ON rider_restriction_review (state, opened_at);
