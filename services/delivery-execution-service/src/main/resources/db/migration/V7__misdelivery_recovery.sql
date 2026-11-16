-- 오배송 회수 수행(DD-2, 2026-11-16 — 사용자 "추천대로"). 회수 배송 = type RETURN + purpose MISDELIVERY_RECOVERY(dispatch DeliveryCreated).
-- 상태: ASSIGNED → RETURN_PICKED_UP(회수지에서 받음, ReturnPickedUp) → RETURNED_TO_STORE(MFC 도착 = 반납, ReturnDelivered, 수행료).
-- origin_delivery_id: 오배송된 원래 배송(회수 라이더에게 원래 완료 증빙 — 놓은 곳·사진 참조를 보여 준다), case_id: 오배송 확인 사건.
ALTER TABLE execution_delivery ADD COLUMN purpose            varchar(24);
ALTER TABLE execution_delivery ADD COLUMN case_id            varchar(64);
ALTER TABLE execution_delivery ADD COLUMN origin_delivery_id varchar(64);
