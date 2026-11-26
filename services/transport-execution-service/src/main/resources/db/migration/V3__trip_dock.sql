-- 0.3.0 도크 배정(DK-2): planning 이 준 도크 사본
ALTER TABLE trip ADD COLUMN dock_code varchar(40);
ALTER TABLE trip ADD COLUMN dock_reservation_id varchar(64);
