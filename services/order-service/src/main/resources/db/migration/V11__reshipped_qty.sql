-- 오배송 재발송 수량(2026-11-16 — ADR-0052 결정 4·5). 재발송하면 행마다 미이행 +q·재발송 +q.
-- 불변식: 원수량 + 재발송 = 미이행 + 인계 + 출고 전 취소, 보낼(전달) 수량 = 원수량 − 출고 전 취소 + 재발송.
ALTER TABLE customer_order_line ADD COLUMN reshipped_qty integer NOT NULL DEFAULT 0;
