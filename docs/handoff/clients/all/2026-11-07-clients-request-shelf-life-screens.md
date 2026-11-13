# [pmart-backend → pmart-clients] 상품별 소비기한 보장·LOT 제조일 화면 요청 — 상품 등록·수정, 품질검사 제조일, 센터 재고 상세 (2026-11-07)

- 상태: **LAN 배포 2026-11-07**(backend `6250332` product·mfc-inventory·mfc-inbound, `9c2cfec` product). 기존 상품 503개의 보장 값 재적재·시드 LOT 정정은 진행 중(seed-v3).
- 계약 정본: `contracts/openapi/product-service.yaml`(`ShelfLifeGuarantee`, `ProductDraftInput.targetProductId`, `SkuDetail.volumeMl`), `mfc-inbound-service.yaml`(0.4.0), `mfc-inventory-service.yaml`(0.4.0)
- 결정: ADR-0041(상품별 소비기한 보장), MFC 업무 문서 §6 Q-05 결정 기록
- 원 요청서: 없음(백엔드 요청). 앞선 요청 `2026-11-07-clients-request-mfc-inbound-screens.md` 의 B3 를 넓힌다.

## 1. 무엇이 바뀌나
- 상품 고시 "소비기한 또는 품질유지기한" 의 약속(예: `주문일 기준 91일 이상 남은 상품`, `주문일 기준 3일 이내 제조된 상품`)이 **SKU 의 값**이 됐다 — `shelfLifeGuarantee.minRemainingDays`(최소 잔여일), `maxDaysSinceManufacture`(제조 후 최대 경과일). 둘 다 선택, 0~3650.
- MFC 재고는 고객 주문에 이 값을 지키는 LOT 만 잡는다. 값이 없는 상품은 지금처럼 보관 유형 기본값(냉장 2·냉동 30·상온 30일).
- **제조일 보장 상품은 제조일이 입력된 LOT 만** 주문에 잡힌다. 그래서 품질검사에서 제조일을 넣어야 한다.
- 원천 키(묶음 seedProductId) 없이 개별 등록한 상품도 **승인을 거쳐 속성을 고칠 수 있다**(`targetProductId`). 지금까지는 개별 등록 상품을 고칠 길이 없었다.

## 2. 백오피스가 할 일

| # | 화면 | API | 내용 |
|---|---|---|---|
| S1 | **상품 등록 초안**(개별·일괄 행 편집) | `POST /api/v1/product-drafts`, `PUT /product-drafts/{id}` — `skus[0].shelfLifeGuarantee` | 입력 두 칸: "주문일 기준 최소 잔여일(일)", "주문일 기준 제조 후 최대 경과일(일)". 비워 두면 보장 없음(0 을 넣지 않는다 — 0 은 "당일 기한도 가능" 이라는 다른 뜻). 범위 밖이면 초안 문제 `SHELF_LIFE_INVALID`(필드 `skus[0].shelfLifeGuarantee.minRemainingDays` 등). 상품 고시 칸 옆에 두면 운영자가 문구를 보고 옮겨 적기 쉽다 |
| S2 | **상품 수정**(새 기능) | `GET /api/v1/products/{productId}` 로 현재 값 → `POST /product-drafts` 에 `targetProductId` + 고친 전체 입력 → `POST /product-drafts/{id}/submissions` → 승인 | 상품 상세에 "수정" 버튼. 현재 승인 버전을 초안에 채워 보여 주고 고친 값만 바꿔 제출한다(**보내지 않은 값은 지워진다** — 바코드·카테고리·필터 옵션·용량 `volumeMl` 모두 현재 값을 같이 보낸다. `SkuDetail.volumeMl` 을 이번에 조회에 넣었다). `product.saleState` 는 보내지 않으면 현재 판매 상태 유지. 초안 응답 `registration=UPDATE`·`existingProductId`·`existingSkus` 로 "기존 상품 수정" 임을 표시. 오류: `TARGET_PRODUCT_NOT_FOUND`, `TARGET_CONFLICT`(원천 키가 다른 상품에 연결됨), 반영 때 판매 단위·구성 수가 다르면 `SKU_IDENTITY_CHANGED` |
| S3 | **승인 화면**(상품) | 승인 행 attributes `registration`(NEW/UPDATE)·`existingProductId` | 이미 있는 표시 그대로. 수정 승인도 같은 PRODUCT 승인이다 |
| S4 | **품질검사**(앞 요청 B3 확장) | `POST /api/v1/inbound-tasks/{taskId}/inspections` — 결과 줄 `manufactureDate` | 결과 줄마다 "제조일" 칸 추가(선택). 상품이 제조일 보장 상품이면(`GET /api/v1/skus/{sku}` 의 `shelfLifeGuarantee.maxDaysSinceManufacture` 가 있음) **필수처럼 강조**하고 "제조일 없으면 주문에 쓰이지 않습니다" 안내. 400: 제조일이 오늘 이후, 제조일이 소비기한보다 늦음. 같은 제조 LOT 표기·소비기한·제조일은 같은 LOT 으로 합쳐진다 |
| S5 | **센터 재고 상세** | `GET /api/v1/mfcs/{mfcId}/stock?sku=` — `lots[].manufactureDate` | LOT 표에 제조일 열(없으면 "-"). SKU 머리에 보장 값(S4 와 같은 조회)을 보여 주면 "왜 이 LOT 이 주문에 안 잡히나" 를 현장이 이해할 수 있다 |
| S6 | **재고 정정**(있다면) | `POST /api/v1/mfcs/{mfcId}/stock-corrections` — 줄 `toManufactureDate` | 대상 LOT 을 바꿀 때 제조일도 함께. `toSourceLotRef`·`toExpiryDate`·`toManufactureDate` 중 하나라도 있으면 그 LOT 으로 옮긴다(총량 불변) |

## 3. 고객 앱이 할 일
- **변경 없음.** 상품 상세의 고시 문구는 지금처럼 product-content 의 `notices` 를 보여 준다.
- 결제 진입 때 `INSUFFICIENT_STOCK` + `details[].reason` 이 `NO_ELIGIBLE_LOT;…` 인 거절이 조금 늘 수 있다(보장을 못 지키는 재고는 잡지 않으므로). 안내 문구는 지금의 재고 부족과 같게 둔다.

## 4. 다음에 올 요청
- 일괄 처리 화면(엑셀 업로드 → 행 검증 → 미리보기 편집·제외 → 제출 → 행별 결과): 입고 수령·품질검사·적치, 재고 정정, 위치 등록. API 를 만든 뒤 별도 요청서로 보낸다. S1 의 보장 값도 상품 일괄 등록 행 편집에서 고칠 수 있으면 좋다.
