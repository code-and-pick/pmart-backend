-- 0.20.0 재고 이동 이력(DC 백오피스 LG-1): 원결과 처리자, 이력 조회용 색인
ALTER TABLE inventory_commit ADD COLUMN actor_id varchar(128);

CREATE INDEX ix_inventory_commit_cdc_applied ON inventory_commit (cdc_id, applied_at DESC, commit_id DESC);
CREATE INDEX ix_inventory_commit_delta_sku ON inventory_commit_delta (sku, lot, commit_id);
CREATE INDEX ix_inventory_commit_delta_location ON inventory_commit_delta (location_code, commit_id);
