-- 고객 문의를 주문으로 찾기(2026-11-16 백오피스 통합 조회, 계약 0.5.0 GET /inquiries?orderId=).
CREATE INDEX ix_cs_inquiry_order ON cs_inquiry (order_id, created_at DESC, inquiry_id DESC) WHERE order_id IS NOT NULL;
