-- 수령 정정(0.13.0, 2026-11-26 — WMS CDC-F-024 "확정 수령의 후속 정정", 설계 2026-11-25-receipt-transit.md §3-4). 적용된 파일은 수정하지 않는다.
-- 수령 하나의 SKU·LOT 별 확인 누계(정상·격리·미판정). 정정은 이 누계와의 차이(δr)만 Transit 에 반영한다.
CREATE TABLE transit_receipt_line (
    receipt_id          varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    normal_qty          integer      NOT NULL,
    quarantined_qty     integer      NOT NULL,
    pending_qty         integer      NOT NULL,
    CONSTRAINT pk_transit_receipt_line PRIMARY KEY (receipt_id, sku, lot),
    CONSTRAINT ck_transit_receipt_line CHECK (normal_qty >= 0 AND quarantined_qty >= 0 AND pending_qty >= 0)
);

-- 정정 하나 = correctionId 하나(한 번).
CREATE TABLE transit_receipt_correction (
    correction_id       varchar(64)  NOT NULL,
    receipt_id          varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    body_hash           varchar(64)  NOT NULL,
    delta_qty           integer      NOT NULL,          -- 반영한 δr 합(음수 = 미정산을 다시 엶)
    commit_id           varchar(64),
    conflicts           text,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_transit_receipt_correction PRIMARY KEY (correction_id)
);
CREATE INDEX ix_transit_receipt_correction_receipt ON transit_receipt_correction (receipt_id);
