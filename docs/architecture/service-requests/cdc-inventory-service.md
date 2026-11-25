# cdc-inventory-service 에 대한 요청

## CI-1. DC 백오피스 응답에 상품 이름·실사 화면 번호 (2026-11-26, 보낸 영역 platform — PC 5 DC 백오피스 P-1·화면 번호)
- **요청한 곳**: DC 백오피스 화면(PC 5). 사용자 "품목별 수량에 B-0000006 이런 거 적혀있는데 무슨 상품인지는 안 적히나?", "관리자가 볼 땐 직관적으로 … 짧게 붙인 번호로".
- **지금**: CDC 재고 응답은 SKU 만 온다. MFC 는 mfc-inventory 0.23.0 `CenterStock.productName`(SKU 사본의 product `internalName`). 실사는 `sessionId` 시스템 ID 만.
- **할 것**:
  1. `productName`(nullable, product `internalName` — MFC 와 같은 기준): Availability(stock-snapshot), location-stocks `items[]`, SkuHandling, CountSession `sheet`/`lines[]`, RecountHold.
     - 방법은 서비스가 정한다. 이미 있는 `sku_projection` 에 `product_name` 을 두고 `ProductVersionPublished` 의 `internalName` 으로 채우는 것이 MFC 와 같다(기존 행은 비어 있으니 채우는 방법 필요). cdc-outbound 0.21.0 은 product `GET /internal/skus?skus=`(최대 100건 — SkuDetail 에는 이름이 없어 productId·version 만) → `GET /internal/products/{id}/versions/{v}` 의 internalName, 10분 캐시·응답당 2초(`infrastructure/ProductNames`)로 했다 — 그대로 옮겨 써도 된다.
  2. 실사 화면 번호 `sessionNumber` = **C + 숫자 6자리**(무작위·겹치지 않음·바뀌지 않음, 기존 행도 채움) + 목록 찾기 `number`(글자 있든 없든). ADR-0070 덧붙임(2026-11-26) 규칙. cdc-outbound V14 의 `pmart_display_number` 함수·열 기본값 방식을 그대로 써도 된다.
- **누가**: cdc-inventory 는 CDC 세션이 고친다(열린 PR #508·#509 0.16.x 와 겹치지 않게 — 2026-11-26 PC1 백엔드가 맡기지 않고 요청으로 둠).
- **화면 쪽**: 필드가 오기 전까지는 앱이 product `GET /skus/{sku}` → `/products/{productId}.internalName` 으로 몇 화면만 찾아 보인다.
- **회신(cdc 세션, 2026-11-26)**: 구현 — cdc-inventory 0.17.0(Flyway V10), PR `cdc/inventory-names-numbers`. productName 은 cdc-outbound 의 ProductNames(7bfecb89 — 승인 스냅샷 internalName) 를 옮김, 주소 `pmart.inventory.product-base-url`(pmart-config 에 LAN product 주소 필요 — PC1, 비면 null). sessionNumber C+6·목록 `?number=`(C 있든 없든).
