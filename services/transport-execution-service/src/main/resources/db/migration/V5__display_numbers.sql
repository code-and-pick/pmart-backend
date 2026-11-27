-- 0.6.0(2026-11-27, DC 백오피스 DN-3·MFC 시뮬레이터 R-52): 화면 번호 사본 — TRF 번호는 planning 이 운행을 만들 때 준 값,
-- 출발 번호는 cdc-outbound 출발 검증 응답 값. 없으면 null(옛 운행). 적용된 파일은 수정하지 않는다.
ALTER TABLE trip_transfer ADD COLUMN trf_number varchar(16);
ALTER TABLE trip ADD COLUMN departure_number varchar(16);
