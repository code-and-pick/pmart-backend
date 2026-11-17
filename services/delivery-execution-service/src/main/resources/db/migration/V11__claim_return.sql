-- 고객 반품 회수 수행(CL-1 배송 쪽, 2026-11-17 — 사용자: 실패마다 알림·다시 시도는 반품 쪽, 고객 사정 실패도 수락 당시 금액).
-- 회수 배송 type RETURN + purpose CLAIM_RETURN(DeliveryCreated claimId·returnLines). 배정 → 고객 집에서 사진·받음(returns/pickups →
-- RETURN_PICKED_UP, ReturnPickedUp claimId) → MFC 반납(RETURNED_TO_STORE, ReturnDelivered). 받기 전 실패 등록(ASSIGNED → FAILURE_REPORTED)
-- → 지원센터 확정이면 FAILED + ReturnPickupFailed.
--   claim_id     : 반품 클레임
--   return_lines : 회수할 줄·수량(claim 회수 요청 lines 그대로)
ALTER TABLE execution_delivery ADD COLUMN claim_id     varchar(64);
ALTER TABLE execution_delivery ADD COLUMN return_lines jsonb;
