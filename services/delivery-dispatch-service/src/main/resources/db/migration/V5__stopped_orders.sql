-- 주문 작업 중지 기록(PC 1 검토 H1, 2026-11-13). WorkStopResult(키 = 주문)가 DispatchRequested(키 = 출고)보다 먼저 올 수 있다
-- (토픽 파티션이 다름) — 멈춘 주문을 남겨 두고 나중에 온 배차 요청은 배차하지 않는다(BLOCKED).
CREATE TABLE stopped_order (
    order_id        varchar(64)  NOT NULL,
    stop_request_id varchar(128) NOT NULL,
    stopped_at      timestamptz  NOT NULL,
    CONSTRAINT pk_stopped_order PRIMARY KEY (order_id)
);
