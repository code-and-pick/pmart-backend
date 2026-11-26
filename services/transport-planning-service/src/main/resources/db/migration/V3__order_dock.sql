-- 0.3.0 도크 배정(DK-2): 계획완료 때 cdc-location 도크 예약 사본
ALTER TABLE transport_order ADD COLUMN dock_code varchar(40);
ALTER TABLE transport_order ADD COLUMN dock_reservation_id varchar(64);
