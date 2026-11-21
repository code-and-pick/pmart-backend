-- 2026-11-21 라이더 백오피스 대시보드 분석(요청서 pmart-clients docs/handoff/rider-backoffice/2026-11-21-backend-staff-delivery-times.md):
-- 배송별 지연을 보려고 고객 약속 끝(dispatch promisedAt)을 배정 사건(DeliveryAssigned.promisedAt)에서 받아 둔다. 옛 배송은 null.
ALTER TABLE execution_delivery ADD COLUMN promised_at TIMESTAMPTZ;
