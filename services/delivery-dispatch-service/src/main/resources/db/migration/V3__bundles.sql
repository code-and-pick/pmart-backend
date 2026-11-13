-- 알뜰배달 묶음 배정(2026-11-13, 사용자 "묶음 배정으로 진행" — 원문 §5.3 최대 3건, ADR-0058 결정 3).
-- 묶음의 제안은 배송마다 한 줄, 같은 bundle_id 는 한 번에 수락·거절. stop_seq = 고객 방문 순서(1부터).
ALTER TABLE dispatch_offer ADD COLUMN bundle_id   varchar(64);
ALTER TABLE dispatch_offer ADD COLUMN bundle_size integer NOT NULL DEFAULT 1;
ALTER TABLE dispatch_offer ADD COLUMN stop_seq    integer NOT NULL DEFAULT 1;
CREATE INDEX ix_dispatch_offer_bundle ON dispatch_offer (bundle_id) WHERE bundle_id IS NOT NULL;

ALTER TABLE delivery ADD COLUMN bundle_id varchar(64);
ALTER TABLE delivery ADD COLUMN stop_seq  integer;
