-- 고객 반품 회수 요청 번호(DD-3 맞춤, 2026-11-17 — PC 1 요청서): claim ReturnPickupRequested 의 pickupRequestId(<claimId>-P<n>,
-- 시도마다 하나). 배송 하나 = 회수 요청 하나(shipment_id 'CLR-<pickupRequestId>'), DeliveryCreated 에 실어 claim 이 배송 번호를 잇는다.
-- 이 열 전(V11) 배송은 사건 ID 를 썼다(비어 있음).
ALTER TABLE delivery ADD COLUMN pickup_request_id varchar(96);
