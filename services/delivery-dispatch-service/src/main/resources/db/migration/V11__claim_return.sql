-- 고객 반품 회수 배차(CL-1 배송 쪽, 2026-11-17 — 사용자: 바로 배차, 수행료 일반 표(고객 집 → MFC), 실패마다 알림·다시 시도는 반품 쪽,
-- 고객 사정 실패도 수락 당시 금액). claim ReturnPickupRequested 한 건 = 회수 배송 하나(type RETURN, purpose CLAIM_RETURN,
-- shipment_id 'CLR-<eventId>' — 같은 사건은 하나). 같은 클레임에 아직 끝나지 않은 회수 배송이 있으면 새로 만들지 않는다.
--   claim_id     : 반품 클레임
--   return_lines : 회수할 줄·수량(사건 lines 그대로 — execution 이 ReturnPickedUp·ReturnDelivered 에 싣는다)
ALTER TABLE delivery ADD COLUMN claim_id     varchar(64);
ALTER TABLE delivery ADD COLUMN return_lines jsonb;

CREATE INDEX ix_delivery_claim ON delivery (claim_id) WHERE claim_id IS NOT NULL;
