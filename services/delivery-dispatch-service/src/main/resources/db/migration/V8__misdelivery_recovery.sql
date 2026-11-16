-- 확인된 오배송 회수 배차(DD-2, 2026-11-16 — 사용자 "추천대로": 회수지 = 원래 라이더가 완료를 누른 곳, 수행료 = 일반 표(회수지 → MFC),
-- 회수 실패는 도움요청 → 지원센터, MFC 도착 = 반납).
-- 회수 배송은 type RETURN + purpose MISDELIVERY_RECOVERY. shipment_id = 'RCV-<caseId>'(유일 — 같은 사건은 회수 하나).
--   pickup_*  : 회수지(원래 라이더의 완료 위치 — tracking, 없으면 원래 배송지 좌표). 사건에 싣지 않는다.
--   drop_*    : 도착지 = MFC(drop_distance_m·drop_duration_s = 회수지 → MFC 도로 거리·시간 — 예상 배달료 기준).
-- completed_at: 전달 완료 시각(execution DeliveryCompleted.deliveredAt) — 회수지 위치 점을 찾는 기준. 이 열 전 완료분은 비어 있다(updated_at 으로 대신).
ALTER TABLE delivery ADD COLUMN purpose            varchar(24);
ALTER TABLE delivery ADD COLUMN case_id            varchar(64);
ALTER TABLE delivery ADD COLUMN origin_delivery_id varchar(64);
ALTER TABLE delivery ADD COLUMN pickup_lat         double precision;
ALTER TABLE delivery ADD COLUMN pickup_lng         double precision;
ALTER TABLE delivery ADD COLUMN pickup_source      varchar(32);
ALTER TABLE delivery ADD COLUMN pickup_recorded_at timestamptz;
ALTER TABLE delivery ADD COLUMN completed_at       timestamptz;

CREATE INDEX ix_delivery_origin ON delivery (origin_delivery_id) WHERE origin_delivery_id IS NOT NULL;
