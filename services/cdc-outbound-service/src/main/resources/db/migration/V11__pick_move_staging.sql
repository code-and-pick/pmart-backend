-- 피킹 재고 출고대기 자리로 옮겨 적기(0.14.0, 2026-11-25 — 사용자 결정, 설계 2026-11-25-count-adjustment.md §5). 적용된 파일은 수정하지 않는다.
-- MFC 출고대기(통로 91) 자리 번호 — 칠곡 CDC-5 기본 배정.
ALTER TABLE region_mfc ADD COLUMN staging_bay varchar(2);
UPDATE region_mfc SET staging_bay = '01' WHERE cdc_id = 'CDC01' AND mfc_id = 'MFC-BJG-01';
UPDATE region_mfc SET staging_bay = '02' WHERE cdc_id = 'CDC01' AND mfc_id = 'MFC-BNG-01';
UPDATE region_mfc SET staging_bay = '03' WHERE cdc_id = 'CDC01' AND mfc_id = 'MFC-MOKPO-01';
UPDATE region_mfc SET staging_bay = '04' WHERE cdc_id = 'CDC01' AND mfc_id = 'MFC-SEJ-01';
UPDATE region_mfc SET staging_bay = '05' WHERE cdc_id = 'CDC01' AND mfc_id = 'MFC-CHC-01';

-- 실피킹 하나(TRF 몫·줄) = 옮겨 적기 하나. PENDING(재고 호출 전·불명) → DONE / FAILED(재고 거절·자리 없음 — 원위치에 남음).
CREATE TABLE pick_move (
    move_id             varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    task_id             varchar(64)  NOT NULL,
    allocation_id       varchar(64),
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    from_location       varchar(40)  NOT NULL,
    to_location         varchar(40),
    qty                 integer      NOT NULL,
    state               varchar(16)  NOT NULL,
    reason              varchar(300),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_pick_move PRIMARY KEY (move_id),
    CONSTRAINT ck_pick_move_state CHECK (state IN ('PENDING', 'DONE', 'FAILED')),
    CONSTRAINT ck_pick_move_qty CHECK (qty > 0)
);
CREATE INDEX ix_pick_move_open ON pick_move (created_at) WHERE state = 'PENDING';
CREATE INDEX ix_pick_move_trf ON pick_move (trf_id);
