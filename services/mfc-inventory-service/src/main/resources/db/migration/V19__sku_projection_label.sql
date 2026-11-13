-- 현장 화면 표시용 상품명·바코드(0.15.0, 클라이언트 BR-53 R8). ProductVersionPublished 의 internalName·skus[].barcodes 사본.
-- 이 열 추가 전에 받은 상품은 다음 상품 버전 발행까지 비어 있다(null / 빈 문자열).
ALTER TABLE sku_projection ADD COLUMN product_name TEXT;
ALTER TABLE sku_projection ADD COLUMN barcodes TEXT NOT NULL DEFAULT '';
