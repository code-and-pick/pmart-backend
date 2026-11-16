-- 배차 실패 사건(2026-11-16 사용자 — 중간안): 시스템 장애는 첫 실패에, 그 밖(라이더 없음·반복 거절·응답 만료)은 같은 배송의 두 번째
-- 실패부터 사건을 연다. delivery-dispatch 의 DispatchFailed.attempts 는 재배차 시도마다 다시 세므로 배송별 실패 횟수를 여기서 센다.
CREATE TABLE dispatch_failure_watch (
    delivery_id      VARCHAR(80)  PRIMARY KEY,
    failures         INTEGER      NOT NULL,
    first_failed_at  TIMESTAMPTZ  NOT NULL,
    last_failed_at   TIMESTAMPTZ  NOT NULL,
    last_reason      VARCHAR(40)  NOT NULL
);
