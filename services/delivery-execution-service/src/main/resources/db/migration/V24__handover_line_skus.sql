-- 2026-11-20 PC 3 요청(MFC PDA BR-52 R13): 인계 줄의 SKU — ReturnDelivered 줄에 sku 를 싣는다(MFC 반납 바코드 대조).
-- 인계 사건(PackageHandedOver)에는 원래 sku 가 있었는데 줄·수량만 저장했다. 이 파일 전 인계는 NULL(그 줄은 sku 없이 나간다).
ALTER TABLE package_handover ADD COLUMN line_skus jsonb;
