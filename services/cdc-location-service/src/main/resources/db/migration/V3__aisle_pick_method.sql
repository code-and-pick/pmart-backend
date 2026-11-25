-- 피킹 선반 통로별 피킹 방식 설정값(0.5.0, 2026-11-25) — 묶음 피킹 설계 §7 결정 3(사용자 "선반 통로 설정값").
-- 통로 = (센터·구역·층·통로) — location 의 area·floor·aisle 구간. 행이 없으면 미설정(기본값으로 채우지 않는다). 적용된 파일은 수정하지 않는다.
CREATE TABLE aisle_pick_method (
    center_id           varchar(32)  NOT NULL,
    area                varchar(4)   NOT NULL,
    floor               varchar(4)   NOT NULL,
    aisle               varchar(2)   NOT NULL,
    pick_method         varchar(16)  NOT NULL,          -- DPS / BOX_PICK / FC / DISTRIBUTION / LINE
    reason              varchar(500),
    updated_by          varchar(128) NOT NULL,
    request_id          varchar(128) NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_aisle_pick_method PRIMARY KEY (center_id, area, floor, aisle),
    CONSTRAINT ck_aisle_pick_method CHECK (pick_method IN ('DPS', 'BOX_PICK', 'FC', 'DISTRIBUTION', 'LINE'))
);
