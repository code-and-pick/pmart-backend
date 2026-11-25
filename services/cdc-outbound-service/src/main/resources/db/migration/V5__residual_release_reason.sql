-- 잔여 할당 해제가 업무 거절(409 INVALID_STATE 밖의 4xx)로 끝났을 때 사유(2026-11-25, #455 검토 후속).
-- residual_status 에 FAILED 가 더해진다(DONE / PENDING / FAILED). FAILED·PENDING 은 같은 출발 재전송이 같은 키로 다시 부른다.
ALTER TABLE departure_transfer ADD COLUMN residual_reason varchar(300);
