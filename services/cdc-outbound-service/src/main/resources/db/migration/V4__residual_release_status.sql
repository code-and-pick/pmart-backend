-- 출발 뒤 잔여 할당 해제(RESIDUAL_CLOSED) 결과(2026-11-25, #454 검토): DONE / PENDING(불명 — 같은 출발 재전송이 다시 부른다).
-- TOUT 이 APPLIED 인데 비어 있으면 아직 부르지 않은 것. 적용된 파일은 수정하지 않는다.
ALTER TABLE departure_transfer ADD COLUMN residual_status varchar(16);
