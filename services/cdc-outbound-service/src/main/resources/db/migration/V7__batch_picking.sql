-- 묶음 피킹(0.9.0, 2026-11-25) — docs/cdc/design/2026-11-25-batch-picking.md, 사용자 결정 §7. 적용된 파일은 수정하지 않는다.
-- 작업 하나가 TRF 여럿의 몫을 가질 수 있다: 묶음 작업이면 task.trf_id 는 비우고, 줄마다 TRF 몫(task_line_share)을 남긴다(WMS CDC-F-017 "원요청행별 배분 근거").
ALTER TABLE task ALTER COLUMN trf_id DROP NOT NULL;
ALTER TABLE task ADD COLUMN pick_mode varchar(16) NOT NULL DEFAULT 'DISCRETE';   -- DISCRETE / REGION_BATCH / SKU_BATCH
ALTER TABLE task ADD COLUMN pick_method varchar(16);                             -- cdc-location 통로 방식(DPS·BOX_PICK·FC·DISTRIBUTION·LINE), 없으면 NULL
ALTER TABLE task ADD COLUMN region_code varchar(16);                             -- REGION_BATCH 의 지역
ALTER TABLE task ADD CONSTRAINT ck_task_pick_mode CHECK (pick_mode IN ('DISCRETE', 'REGION_BATCH', 'SKU_BATCH'));

-- 줄의 TRF 별 몫. 줄 실적(task_line)을 기한 이른 TRF 부터 나눈 값(부족은 기한 늦은 몫부터 — 사용자 결정 §7-2).
CREATE TABLE task_line_share (
    task_id             varchar(64)  NOT NULL,
    line_no             integer      NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    mfc_id              varchar(64)  NOT NULL,
    due_at              timestamptz  NOT NULL,
    planned_qty         integer      NOT NULL,
    picked_qty          integer      NOT NULL DEFAULT 0,
    short_qty           integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_task_line_share PRIMARY KEY (task_id, line_no, trf_id),
    CONSTRAINT ck_task_line_share_qty CHECK (planned_qty > 0 AND picked_qty >= 0 AND short_qty >= 0 AND picked_qty + short_qty <= planned_qty)
);
CREATE INDEX ix_task_line_share_trf ON task_line_share (trf_id, task_id);

-- 이미 있는 단건 작업도 몫 한 줄씩(작업 TRF 가 줄 전량)
INSERT INTO task_line_share (task_id, line_no, trf_id, mfc_id, due_at, planned_qty, picked_qty, short_qty)
SELECT l.task_id, l.line_no, t.trf_id, tr.mfc_id, tr.due_at, l.planned_qty, l.picked_qty, l.short_qty
FROM task_line l
JOIN task t ON t.task_id = l.task_id
JOIN transfer tr ON tr.trf_id = t.trf_id;

-- 지역 ↔ MFC 대응표(chilgok-dc.md CDC-6). 지역 코드 = PDA 지역 바코드 값. MFC 하나 = 지역 하나.
CREATE TABLE region_mfc (
    cdc_id              varchar(32)  NOT NULL,
    mfc_id              varchar(64)  NOT NULL,
    region_code         varchar(16)  NOT NULL,
    region_name         varchar(64)  NOT NULL,
    updated_by          varchar(128) NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_region_mfc PRIMARY KEY (cdc_id, mfc_id)
);

-- 칠곡 처음 값 — 공급 대상 5곳·4지역(사용자 2026-11-25 "지금은 일단 공급대상 MFC 5곳", "4지역 묶음(부산권·호남권·충청권·강원권)")
INSERT INTO region_mfc (cdc_id, mfc_id, region_code, region_name, updated_by, updated_at) VALUES
    ('CDC01', 'MFC-BJG-01',   'BUSAN',       '부산권', 'flyway-v7', now()),
    ('CDC01', 'MFC-BNG-01',   'BUSAN',       '부산권', 'flyway-v7', now()),
    ('CDC01', 'MFC-MOKPO-01', 'HONAM',       '호남권', 'flyway-v7', now()),
    ('CDC01', 'MFC-SEJ-01',   'CHUNGCHEONG', '충청권', 'flyway-v7', now()),
    ('CDC01', 'MFC-CHC-01',   'GANGWON',     '강원권', 'flyway-v7', now());
