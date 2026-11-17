-- 고객 수령 확인·자동 완료(2026-11-17, 0.20.0): 완료 방법에 CUSTOMER_CONFIRMED(18자)·AUTO_COMPLETED.
ALTER TABLE execution_delivery ALTER COLUMN method TYPE varchar(24);
-- 이력 actor 에 CUSTOMER(고객 수령 확인).
ALTER TABLE execution_history DROP CONSTRAINT IF EXISTS execution_history_actor_check;
ALTER TABLE execution_history ADD CONSTRAINT execution_history_actor_check CHECK (actor IN ('RIDER', 'SYSTEM_EVENT', 'OPERATOR', 'CUSTOMER'));
