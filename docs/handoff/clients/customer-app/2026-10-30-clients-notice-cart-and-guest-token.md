# [pmart-backend → pmart-clients] ④ 장바구니·비회원 토큰 안내 (2026-10-30)

- 관련: `../archive/2026-10-30-clients-reply-customer-app-priority.md` 의 ④(요청 2-2: 비회원 장바구니 → (a) 게스트 토큰, 사용자 승인), ADR-0022
- 계약 정본: `contracts/openapi/cart-service.yaml`, `contracts/openapi/auth-service.yaml`(guest-sessions)
- 모든 변경 요청: `Idempotency-Key`(재시도 때 같은 값), `X-Source-System: customer-app`

## 1. 비회원 토큰 (auth)
| 작업 | API | 메모 |
|---|---|---|
| 비회원 세션 시작 | `POST /api/v1/auth/guest-sessions` + `X-Device-Id` | 토큰 없이. 201 `{guestId, tokens}`. `tokens` 는 회원과 같은 TokenPair(`typ=GUEST`) |
| 갱신 | `POST /api/v1/auth/tokens` `{refreshToken}` + 같은 `X-Device-Id` | 회원과 같다(refresh 는 한 번만, 회전) |
| 종료 | `POST /api/v1/auth/logouts` | |

- access 1시간, refresh·세션 30일(장바구니 보존과 같음). 토큰을 잃으면 새로 시작 — 이전 비회원 장바구니는 되찾지 않는다.
- **비회원 토큰은 장바구니(`/api/v1/carts/**`)와 자기 세션 조회·종료에서만 통한다.** 다른 API(주소록·주문 등)에 보내면 403. 상품 둘러보기는 토큰 없이(②·③).

## 2. 장바구니 (회원 토큰·비회원 토큰 같은 API)
| 작업 | API | 메모 |
|---|---|---|
| 열기(없으면 만들기) | `POST /api/v1/carts` `{mfcId?, shoppingAddressId?}` | 201 `Cart`. 토큰 주체마다 하나. `X-Device-Id` 는 선택(기록용) |
| 조회 | `GET /api/v1/carts/{cartId}` | 품목·`cartVersion`·표시 사본 |
| 담기 | `POST /api/v1/carts/{cartId}/items` `{sku, quantity, selected?, expectedVersion}` | **같은 SKU 면 수량을 그 값으로 설정**(더하기 아님) |
| 수량·선택 변경 | `PATCH /api/v1/carts/{cartId}/items/{itemId}` `{quantity?, selected?, expectedVersion}` | 수량 0 불가(삭제는 DELETE) |
| 삭제 | `DELETE /api/v1/carts/{cartId}/items/{itemId}` (+ `X-Expected-Version` 권장) | 이미 없으면 성공 |
| 결과 불명 해소 | `GET /api/v1/carts/{cartId}/change-requests/{Idempotency-Key}` | 404 면 반영 안 됨 |

- `expectedVersion` = 마지막으로 받은 `cartVersion`. 다르면 409 `CART_VERSION_CONFLICT` → 장바구니를 다시 읽고 고객이 다시 확인. 응답 `cartVersion` 으로 갱신한다.
- 같은 `Idempotency-Key` 재전송은 같은 결과(수량이 두 번 들어가지 않음). 본문을 바꿔 같은 키로 보내면 409 `IDEMPOTENCY_CONFLICT`.
- 오류: 422 `SKU_NOT_SALEABLE`(없는 SKU·일시품절·판매중지), 422 `QUANTITY_LIMIT_EXCEEDED`(같은 기초상품 합계 10개 초과 — 장바구니 전체 기준), 403 `FORBIDDEN_OWNER`(남의 장바구니), 404 `CART_EXPIRED`(30일 지남 → `POST /carts` 로 새로), 503 `DEPENDENCY_UNAVAILABLE`(반영 안 됨, 같은 키로 재시도).
- 표시(`items[].display`): 이름·이미지·단가·판매 상태는 사본. 출처 장애면 비어 있고 `sources[].asOf.freshness=UNAVAILABLE`. **`availability` 는 지금 늘 `UNKNOWN`**(재고 전) — "확인 중"으로 표시하고 0·품절로 그리지 않는다. 담기는 막지 않는다.
- `selectedItemAmount`: 선택 품목 단가×수량 합(표시용, 견적 아님). 가격 모르는 선택 품목이 있으면 null.

## 3. 로그인 후 병합 (주문서 진입 때만, 고객 확인 후)
- `POST /api/v1/carts/{회원 cartId}/merges` — **회원 토큰**으로 부르고, 헤더 **`X-Guest-Token: <로그인 전 비회원 access token>`** 으로 비회원 장바구니 소유를 증명한다(만료 전이어야 함 — 필요하면 비회원 refresh 로 먼저 갱신).
- 본문 `{sourceCartId, expectedVersion(회원 cartVersion), items:[{sourceItemId, sku, quantity}]}` — 고객이 고른 항목만.
- 결과 `state`: `MERGE_CONFIRMED`(모두) / `MERGE_PARTIAL`(일부) / `MERGE_REJECTED`(없음). 항목별 `result`·`reasonCode`(`QUANTITY_LIMIT_EXCEEDED`·`SKU_NOT_SALEABLE`·`SOURCE_ITEM_CHANGED`). 성공분은 회원 장바구니에 **더해지고**(합산) 비회원 장바구니에서 빠진다. 실패분은 비회원 장바구니에 남는다.
- 같은 키 재전송은 같은 결과. 결과 재조회 `GET /carts/{cartId}/merges/{mergeId}`.
- 일반 로그인은 병합하지 않는다(자동 병합 금지).

## 4. 계약에서 바뀐 것
- 병합 헤더 `X-Device-Id` → **`X-Guest-Token`**(필수). 장바구니 열기의 `X-Device-Id` 는 기록용(선택), 소유는 토큰으로 판정.
- auth `Session.principalType` 에 `GUEST` 추가.

## 5. LAN
- 배포됨(2026-10-30, auth·cart·gateway 5415fad, product 구매 상한 수정 이후 커밋). 게이트웨이 `http://192.168.0.40:8080` 확인: 비회원 세션 201 → 장바구니 201(GUEST) → 담기 APPLIED·같은 키 재전송 같은 결과 → 조회(이름·단가 1,900·가용 UNKNOWN·합계) → 11개 422 `QUANTITY_LIMIT_EXCEEDED`·10개 통과. 비회원 토큰으로 `/me/addresses` 403, 토큰 없이 `/carts` 401.
- 병합은 실제 고객(Google 로그인) 토큰이 있어야 해서 LAN 에서는 아직 확인하지 못했다(통합 시험으로 확인). 앱에서 로그인 후 확인 부탁.
- 시험 SKU: ②의 `E-00000001`~`E-00000005`. **`B-00000006`(화장지 30롤)은 구성 낱개 30 이라 1개만 담아도 상한(10)을 넘는다** — 상한 거절 확인용. 담을 수 있는 묶음은 새로 넣은 **`B-00000007`(화장지 6롤, 4,900원, productId `PRD-ec6557bf-f8a5-4386-8a1f-87f35fe5fa87`)** — 1개는 되고 2개(12롤)는 상한 초과.
