-- 0.26.0 TMS 1단계: TRF 인수 조건의 온도대(conditions TEMPERATURE_ZONE — 요청서 1건 = 온도대 하나, TMS 정본 D-06).
-- 본문 지문(body_hash)에는 넣지 않는다(이미 받은 TRF 재전송이 충돌하지 않게). transport-feed 가 검품 BOX 전에 쓴다.
ALTER TABLE transfer ADD COLUMN temperature_zone varchar(16);
