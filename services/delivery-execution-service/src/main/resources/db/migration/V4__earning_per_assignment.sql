-- 수행료를 배정마다 하나로(PC 1 PR #8 검토 후속, 2026-11-14).
-- 배송·라이더당 하나였더니, 운영자 배차 취소(산정 불가 기록) 뒤 같은 라이더가 다시 배정되어 완료하면
-- 완료 수행료가 유일 키에 막혀 생기지 않았다. 배정 버전(dispatch assignmentVersion)을 키에 넣는다.
-- 완료 중복·사건 재전달은 같은 배정 버전이라 여전히 한 번만.
ALTER TABLE rider_earning ADD COLUMN assignment_version INT NOT NULL DEFAULT 0; -- 이 파일 전 기록은 0(배정 버전 모름)

DROP INDEX rider_earning_delivery_rider;
CREATE UNIQUE INDEX rider_earning_assignment ON rider_earning (delivery_id, rider_id, assignment_version);
CREATE INDEX rider_earning_delivery ON rider_earning (delivery_id, rider_id, recorded_at DESC);
