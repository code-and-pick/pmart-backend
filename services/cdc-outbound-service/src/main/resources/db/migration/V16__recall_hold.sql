-- 리콜로 막힌 출고대기 물량(0.23.0, 2026-11-26 — WMS CDC-F-028·정책 16.13, 사용자 "리콜은 출고대기분도 막음").
-- cdc-inventory 가 리콜 보류로 출고대기 91 의 예약을 풀면 AllocationShortfall(RECALL) — 이미 집은 물건이라 재할당하지 않고 여기 적는다.
-- 출발 준비 blockers RECALL_HOLD. 그 TRF 출발 전기는 재고가 거절한다(보류 현물). 적용된 파일은 수정하지 않는다.
CREATE TABLE recall_hold (
    trf_id              varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    source_ref          varchar(128) NOT NULL,          -- 리콜 보류 ID(cdc-quality)
    allocation_id       varchar(64)  NOT NULL,
    qty                 integer      NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_recall_hold PRIMARY KEY (trf_id, sku, lot, location_code, source_ref),
    CONSTRAINT ck_recall_hold_qty CHECK (qty > 0)
);
