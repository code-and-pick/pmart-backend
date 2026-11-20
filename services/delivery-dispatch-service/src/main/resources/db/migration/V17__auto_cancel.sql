-- 2026-11-20 사용자 "배차 신청 계속 하다가 많이 지연되면 기사 없다고 취소", "쿠폰 안 되면 자동취소만", "약속+30분":
-- 배송 약속 시각 + auto-cancel-after(30분)가 지나도 라이더가 배정되지 않은(배차 대기) 고객 배송 → claim 시스템 취소(DISPATCH_UNAVAILABLE).
-- 요청 결과(ACCEPTED 또는 거절 코드)를 남겨 한 번만 부른다. 일시 장애면 retry_at 뒤 다시.
ALTER TABLE delivery ADD COLUMN auto_cancel_at timestamptz;
ALTER TABLE delivery ADD COLUMN auto_cancel_result varchar(64);
ALTER TABLE delivery ADD COLUMN auto_cancel_retry_at timestamptz;
