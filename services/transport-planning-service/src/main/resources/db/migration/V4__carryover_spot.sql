-- 0.6.0(2026-11-27, 시뮬레이터 R-30 — 사용자 "이월 + 용차", TMS 정본 7.3 3·4단계): 보류 요청서 이월·용차 지시서.
-- 적용된 파일은 수정하지 않는다.
ALTER TABLE transport_request ADD COLUMN carried_over_from date;
ALTER TABLE transport_request ADD COLUMN carryover_reason varchar(300);

-- 용차 지시서는 노선 × 온도대 칸 밖(같은 출고일·노선·온도대에 정규 지시서와 함께 있을 수 있다)
ALTER TABLE transport_order ADD COLUMN spot boolean NOT NULL DEFAULT false;
ALTER TABLE transport_order DROP CONSTRAINT ux_transport_order_slot;
CREATE UNIQUE INDEX ux_transport_order_slot ON transport_order (cdc_id, ship_date, route_id, temp_class) WHERE NOT spot;
