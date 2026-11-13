# [pmart-backend → pmart-clients] ⑤ 배송 방식·예약 슬롯·주문 초안 안내 (2026-10-31)

- 관련: `../archive/2026-10-30-clients-reply-customer-app-priority.md` 의 ⑤, BR-13·14·21
- 계약 정본: `contracts/openapi/order-service.yaml`(delivery-options·order-drafts), `delivery-capacity-service.yaml`(slots), `delivery-serviceability-service.yaml`(delivery-conditions), `center-master-service.yaml`(centers·calendar)
- 범위: **주문 초안까지**(사용자 결정). 결제 진입 `POST /order-drafts/{draftId}/checkouts`·주문 조회는 아직 501 — 자원 확보 사가 단계(⑥)에서 연다.
- 모든 변경 요청: `Idempotency-Key`(재시도 때 같은 값), `X-Source-System: customer-app`

## 1. 사용자 결정 값 (LAN 시험값)
| 항목 | 값 | 메모 |
|---|---|---|
| MFC 고객 주문 운영 시간 | 매일 08:00~24:00 | center-master 승인 경로로 넣음(`MFC-SEED-01`). 00~08 실제 배송 없음은 확정 정책 |
| 예상 도착 시간(ETA) | **미설정** — `NOT_SET` | 화면에 표시하지 않는다(0분·"곧" 으로 그리지 않음) |
| 예약 슬롯 정원 | 슬롯당 20건 | 정책값 미설정이면 슬롯이 `CONFIG_REQUIRED`(선택 불가) |

## 2. 조회 API (회원·비회원 토큰 모두)
| 작업 | API | 메모 |
|---|---|---|
| 배송 방식 안내 | `GET /api/v1/delivery-options?mfcId=&itemAmount=` | 방식별(SINGLE 한집·BUNDLED 알뜰·SCHEDULED 예약) 가능 여부·불가 사유·ETA(`NOT_SET`)·배달팁(기본/적용/무료 사유)·가장 빠른 예약 슬롯. `itemAmount` 는 장바구니 `selectedItemAmount` 를 넣는다 |
| 예약 슬롯 | `GET /api/v1/slots?mfcId=&date=` | **오늘·내일만**(그 밖 400). 1시간 단위, 08:00 전 없음. `availability`: `AVAILABLE`·`FULL`·`CLOSED`·`CONFIG_REQUIRED`. 마감 = 슬롯 시작 시각. 잔여 정원은 조회 사본(선택 성공 보장 아님) |
| 배송 가능 조건 | `GET /api/v1/delivery-conditions?mfcId=` | 방식 3개, 배송 1건당 최대 10kg, 00~08 한집·알뜰 불가. 부피·보냉은 `NOT_SET`, 차량 종류 `[]`(승인 대기) |
| 센터·운영 시간표 | `GET /api/v1/centers/{centerId}`, `/calendar` | 운영자용(비회원 403). 고객 앱은 위 세 API 로 충분 |

- 방식 불가 사유: `NIGHT_TIME`(00~08), `CENTER_CLOSED`, `NO_SLOT`, `CONFIG_REQUIRED`, `LOOKUP_FAILED`(조회 실패 — "확인 중"으로 표시).
- 배달팁(BR-14, 확정 정책): 한집 4,000원·알뜰/예약 3,000원. 상품 금액 30,000원 이상이면 무료(`OVER_30000`), 첫 구매면 무료(`FIRST_PURCHASE`). 비회원은 첫 구매를 판정하지 않는다.

## 3. 주문 초안 (회원 토큰만)
`POST /api/v1/order-drafts`
```json
{ "purchaseIntentId": "장바구니→주문서 진입 1회마다 새 값",
  "items": [{ "cartItemId": "...", "sku": "E-00000001", "quantity": 2 }],
  "shoppingAddressId": "①의 쇼핑 주소", "expectedMfcId": "MFC-SEED-01",
  "deliveryMode": "SCHEDULED", "arrivalWindow": { "date": "2026-10-31", "start": "13:00", "end": "14:00" },
  "outOfStockPreference": "CANCEL_OOS_ONLY", "deliveryNote": "...", "bagOptOut": false }
```
- 서버가 정본을 다시 확인한다(계정 상태·쇼핑 주소 권역·SKU 판매 상태·구매 상한·가격). 화면 값과 다르면 초안 값이 맞다.
- **거절(422)**: `ACCOUNT_NOT_PURCHASABLE`, `OUT_OF_SERVICE_AREA`, `SERVICEABILITY_UNKNOWN`, `SKU_NOT_SALEABLE`, `QUANTITY_LIMIT_EXCEEDED`, `PRICE_NOT_SET`, `WEIGHT_UNSPLITTABLE`(한 개가 10kg 초과), `CONFIG_REQUIRED`. **409 `MFC_CHANGED`**: 주소의 담당 MFC 가 `expectedMfcId` 와 다름 → 장바구니·방식을 다시 확인.
- **차단 사유(초안은 만든다, `blockers[]`)**: `MIN_ORDER_AMOUNT`(최소주문 15,000원, 부족액 `minOrderShortfall`), `DELIVERY_MODE_UNAVAILABLE`, `SLOT_REQUIRED`(예약인데 슬롯 미선택), `SLOT_UNAVAILABLE`. 모든 사유를 한 번에 준다 — 모두 표시하고 결제 버튼을 막는다.
- 금액(`quote`): 상품 금액·배달팁(기본/적용)·봉투 100원(미사용 선택해도 청구)·`deliveryTipBase`·`firstPurchaseBenefit`(`EXPECTED`·`CHECKING_OTHER_ORDER`·`CONFIRMED`·`NOT_ELIGIBLE` — 자격 확보는 결제 진입 때) · `plannedShipments`(10kg 단위 분할 배송 수). `arrivalWindow` 는 슬롯의 날짜·시작·끝을 그대로.
- **견적은 10분 고정**(`quoteExpiresAt`). 지나면 `status=EXPIRED`, `nextAction=RE_QUOTE` → 새 `purchaseIntentId` 로 다시 만든다. 유효한 초안은 같은 `purchaseIntentId` 로 다시 보내면 그대로 돌려준다.
- 조회 `GET /api/v1/order-drafts/{draftId}`.

## 4. LAN (게이트웨이 `http://192.168.0.40:8080`)
- 배포·확인(비회원 토큰): 내일 슬롯 08:00부터 16개 모두 AVAILABLE · 배송 조건(방식 3, 10,000g) · 배송 방식(itemAmount 16,000: 한집 4,000/알뜰 3,000/예약 3,000, 가장 빠른 슬롯 오늘 23:00~24:00) · 비회원으로 센터·주문 초안 403.
- **주문 초안 생성은 실제 고객(Google 로그인) 토큰이 필요해서 LAN 에서 아직 확인하지 못했다**(통합 시험으로 확인). 앱에서 로그인 후 확인 부탁.
- 시험 상품 추가(내부 확인용): 국·탕·찌개 10개, SKU `E-00000008`~`E-00000017`, 실제 상품 이미지(대표·상세·고시정보 3장). **외부 공개·데모·스크린샷 배포 금지**(저작권 — 캡처 자료). 앱 저장소·문서에 이미지를 넣지 말 것.

## 5. 열린 것
- 슬롯 마감 리드타임(지금은 슬롯 시작 시각까지 선택 가능) — 업무 확정 필요.
- 부피·보냉 조건·차량 종류 값 — 승인 대기(`NOT_SET`).
- 결제 진입·슬롯/재고 확보는 ⑥.
