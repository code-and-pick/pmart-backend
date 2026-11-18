-- 화면 배송 번호(2026-11-18, ADR-0070 — 관제 진행 중 배송에 "74939255-1"): dispatch DeliveryCreated.deliverySeq·DeliveryAssigned.orderNumber 사본.
ALTER TABLE delivery_progress ADD COLUMN delivery_seq integer, ADD COLUMN order_number varchar(8);
