-- CDC 위치 마스터(0.2.0, 2026-11-25). 7구간 코드 = 정본 키, 나눈 구간은 거르기용 사본.
-- 칠곡(CDC01) 문법은 docs/simulator/cdc/chilgok-dc.md CDC-3~5 — 애플리케이션이 검사한다(층 배치는 데이터).
-- 점유 수량은 cdc-inventory 정본이라 여기 두지 않는다. 적용된 파일은 수정하지 않는다.
CREATE TABLE location (
    location_code       varchar(40)  NOT NULL,
    center_id           varchar(32)  NOT NULL,
    area                varchar(4)   NOT NULL,          -- 둘째 구간(칠곡 구역 글자 A~H)
    floor               varchar(4)   NOT NULL,          -- 셋째 구간(칠곡 5·6·7·B1)
    aisle               varchar(2)   NOT NULL,
    bay                 varchar(2)   NOT NULL,
    level_no            integer      NOT NULL,
    slot_no             integer      NOT NULL,
    temperature_zone    varchar(16)  NOT NULL,          -- AMBIENT / CHILLED / FROZEN
    location_type       varchar(16)  NOT NULL,          -- PICK_FACE(칠곡 선반) / RESERVE(칠곡 팔레트 랙) / QUARANTINE / STAGING / DOCK / TEMPORARY
    capacity_unit       varchar(8),                     -- EACH / BOX / PLT
    max_qty             integer,                        -- NULL = 미등록(0 이 아니다)
    max_weight_g        bigint,
    restrictions        varchar(500),                   -- 쉼표 구분
    pick_face_min_qty   integer,                        -- 설정값, NULL = 미설정
    pick_face_target_qty integer,
    state               varchar(16)  NOT NULL,          -- DRAFT / ACTIVE / BLOCKED / INACTIVE / RETIRED
    row_version         integer      NOT NULL,
    approval_ref        varchar(128),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_location PRIMARY KEY (location_code),
    CONSTRAINT ck_location_state CHECK (state IN ('DRAFT', 'ACTIVE', 'BLOCKED', 'INACTIVE', 'RETIRED')),
    CONSTRAINT ck_location_qty CHECK ((max_qty IS NULL OR max_qty >= 0) AND (max_weight_g IS NULL OR max_weight_g >= 0))
);
CREATE INDEX ix_location_center ON location (center_id, area, floor, state, location_code);
CREATE INDEX ix_location_center_type ON location (center_id, location_type, location_code);

CREATE TABLE location_state_history (
    history_id          bigint       GENERATED ALWAYS AS IDENTITY,
    location_code       varchar(40)  NOT NULL,
    action              varchar(16)  NOT NULL,          -- CREATE / ACTIVATE / BLOCK / UNBLOCK / DEACTIVATE / RETIRE
    from_state          varchar(16),
    to_state            varchar(16)  NOT NULL,
    reason              varchar(500),
    approval_ref        varchar(128),
    changed_by          varchar(128) NOT NULL,
    request_id          varchar(128) NOT NULL,
    row_version         integer      NOT NULL,
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_location_state_history PRIMARY KEY (history_id)
);
CREATE INDEX ix_location_state_history_code ON location_state_history (location_code, changed_at);
