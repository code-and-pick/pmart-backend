-- 라이더 프로필·운행 상태·배차 설정·계정 제한(2026-11-13, delivery DD-1 첫 묶음 — rider-service.yaml 0.2.0).
-- rider_id = 라이더 토큰 주체(rider-auth-service RD-…). 가입은 전화번호 중복만 확인(면허·주민번호는 받지 않음 — 사용자 결정 2026-11-10, RS-2).
-- 개인정보(이름·전화·계좌·거주지)는 이 표에만 — 사건·로그에 넣지 않는다.
CREATE TABLE rider (
    rider_id             varchar(64)  NOT NULL,
    version              integer      NOT NULL DEFAULT 0,
    signup_request_id    varchar(128) NOT NULL,
    name                 varchar(50),
    phone                varchar(20)  NOT NULL,
    phone_verified       boolean      NOT NULL DEFAULT false,  -- 본인확인 도입 전(2026-11-13 사용자 '본인확인 나중에')
    vehicle_type         varchar(16)  NOT NULL,
    bank_code            varchar(16),
    bank_account_number  varchar(40),
    bank_holder_name     varchar(50),
    residence_road       varchar(200),
    residence_detail     varchar(200),
    operating_status     varchar(16)  NOT NULL DEFAULT 'OFF_DUTY',
    new_dispatch_enabled boolean      NOT NULL DEFAULT false,
    operating_mfc_id     varchar(64),
    state_changed_at     timestamptz  NOT NULL,
    one_at_a_time        boolean      NOT NULL DEFAULT false,
    dispatch_mode        varchar(16)  NOT NULL DEFAULT 'AI_RECOMMENDED', -- 원문 §2.2 첫 가입 = AI 추천
    restriction          varchar(24)  NOT NULL DEFAULT 'NORMAL',
    restriction_reason   varchar(300),
    restriction_until    timestamptz,
    last_heartbeat_at    timestamptz,
    joined_at            timestamptz  NOT NULL,
    CONSTRAINT pk_rider PRIMARY KEY (rider_id),
    CONSTRAINT uq_rider_phone UNIQUE (phone),
    CONSTRAINT ck_rider_vehicle CHECK (vehicle_type IN ('MOTORCYCLE', 'BICYCLE', 'CAR', 'WALK')),
    CONSTRAINT ck_rider_operating CHECK (operating_status IN ('OFF_DUTY', 'ON_DUTY')),
    CONSTRAINT ck_rider_mode CHECK (dispatch_mode IN ('NORMAL', 'AI_RECOMMENDED')),
    CONSTRAINT ck_rider_restriction CHECK (restriction IN ('NORMAL', 'DISPATCH_RESTRICTED', 'SUSPENDED', 'TERMINATED'))
);

-- 배달지역(가입 때 고른 MFC·권역). 적격 라이더 조회는 mfc_id 로 찾는다.
CREATE TABLE rider_delivery_area (
    rider_id  varchar(64) NOT NULL,
    mfc_id    varchar(64) NOT NULL,
    zone_id   varchar(64) NOT NULL DEFAULT '',
    CONSTRAINT pk_rider_delivery_area PRIMARY KEY (rider_id, mfc_id, zone_id),
    CONSTRAINT fk_rider_delivery_area FOREIGN KEY (rider_id) REFERENCES rider (rider_id)
);
CREATE INDEX ix_rider_delivery_area_mfc ON rider_delivery_area (mfc_id);

CREATE TABLE rider_consent (
    rider_id      varchar(64) NOT NULL,
    consent_type  varchar(64) NOT NULL,
    version       varchar(32) NOT NULL,
    agreed_at     timestamptz NOT NULL,
    CONSTRAINT pk_rider_consent PRIMARY KEY (rider_id, consent_type),
    CONSTRAINT fk_rider_consent FOREIGN KEY (rider_id) REFERENCES rider (rider_id)
);
