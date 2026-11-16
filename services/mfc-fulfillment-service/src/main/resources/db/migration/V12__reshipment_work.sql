-- 오배송 재발송(2026-11-16 — ADR-0052 결정 4, 사용자 "새상품 재배차 서비스 넣어"): 같은 주문에 작업 지시를 하나 더 받는다.
-- kind ORIGINAL(주문 확정 작업, 주문당 하나) / RESHIPMENT(오배송 재발송 — source_ref = 오배송 확인 사건 caseId, 사건당 하나).
ALTER TABLE work_order ADD COLUMN kind       varchar(16) NOT NULL DEFAULT 'ORIGINAL';
ALTER TABLE work_order ADD COLUMN source_ref varchar(64);
ALTER TABLE work_order ADD CONSTRAINT ck_work_order_kind CHECK (kind IN ('ORIGINAL', 'RESHIPMENT'));
ALTER TABLE work_order ADD CONSTRAINT ck_work_order_reshipment_ref CHECK (kind = 'ORIGINAL' OR source_ref IS NOT NULL);
ALTER TABLE work_order DROP CONSTRAINT ux_work_order_order;
CREATE UNIQUE INDEX ux_work_order_original ON work_order (order_id) WHERE kind = 'ORIGINAL';
CREATE UNIQUE INDEX ux_work_order_source ON work_order (source_ref) WHERE source_ref IS NOT NULL;
CREATE INDEX ix_work_order_order ON work_order (order_id);
