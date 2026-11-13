# [pmart-backend → pmart-clients] ⑥ 결제 진입(재고·작업 여력·슬롯 확보 → 결제창 정보) 안내 (2026-10-31)

- 관련: `../customer-app/2026-10-31-clients-notice-delivery-and-order-draft.md`(주문 초안), `2026-10-30-clients-notice-customer-payment.md`(결제 승인 요청)
- 계약 정본: `contracts/openapi/order-service.yaml`(`POST /order-drafts/{draftId}/checkouts`, `OrderDraft.payment`·`checkoutFailure`)
- 범위: **결제 시작까지**. 결제 승인 뒤 주문 확정·자원 확정은 다음 단계(그 전까지 결제 승인이 되어도 주문은 확정 상태로 바뀌지 않는다).

## 1. 흐름
1. 주문서(초안) 확인 화면에서 결제 버튼 → `POST /api/v1/order-drafts/{draftId}/checkouts`
   - 헤더: `Idempotency-Key`(버튼 1회 = 키 1개, 재시도는 같은 키), `X-Source-System: customer-app`, `X-Expected-Version`(선택, 초안 version)
   - 본문: `{ "acceptedQuoteVersion": <초안 quote.quoteVersion>, "paymentMethod": "TOSS_PAYMENTS" }`
   - 202 `{status: ACCEPTED, resultId: <orderId>}` — "시작"이지 성공이 아니다
2. `GET /api/v1/order-drafts/{draftId}` 를 1초 간격으로(최대 30초) 조회
   | status | nextAction | 화면 |
   |---|---|---|
   | `RESOURCE_PENDING` | `WAIT` | "재고 확인 중" |
   | `PAYMENT_PENDING` | `PAY` | `payment` 로 토스 결제위젯을 연다 |
   | `PAYMENT_PENDING`·`PAYMENT_UNKNOWN` | `WAIT` | "결제 확인 중"(다시 결제창을 열지 않는다) |
   | `DRAFT` | `REVIEW` + `checkoutFailure` | 실패 사유 표시 후 주문서로. 견적이 유효하면 다시 결제 버튼 가능(새 키) |
   | `EXPIRED` | `RE_QUOTE` | 주문서를 새로 만든다 |
3. `payment` = `{paymentAttemptId, amount, orderName, provider, pgOrderId, clientKey, expiresAt}` — 결제위젯의 orderId 는 **`pgOrderId`**, 금액은 `amount`. **`expiresAt` 이 지나면 결제창을 열지 않는다**(재고·슬롯 점유 만료 — 그 뒤 승인은 거절된다).
4. 결제창 성공 후 승인 요청은 기존 안내대로 `POST /api/v1/payment-confirmations`.

## 2. 거절·실패
- 결제 진입 즉시(422): `RE_QUOTE_REQUIRED`(10분 지남·견적 버전 다름), `CHECKOUT_BLOCKED`(초안 `blockers` 가 남음 — 최소주문·배송 방식·슬롯).
- 확보 실패(초안 `checkoutFailure.code`): `INSUFFICIENT_STOCK`(재고 부족), `SKU_NOT_HANDLED`(센터 취급 안 함), `CAPACITY_EXCEEDED`(작업 여력·슬롯 정원 소진), `SLOT_NOT_SELECTABLE`(마감 지난 슬롯), `CONFIG_REQUIRED`, `RESOURCE_EXPIRED`(결제 안 하고 시간 초과). 확보했던 것은 자동으로 풀린다.
- 같은 초안에 진행 중인 결제 진입이 있으면 새로 시작하지 않고 같은 `orderId` 를 돌려준다.
- `resources`: `inventoryReservation`·`capacityHold`·`slotHold`(예약배달만)·`payment` 상태와 `reservationExpiresAt`.

## 3. LAN (2026-10-31 배포, 게이트웨이 `http://192.168.0.40:8080`)
- 시험 정책값: MFC 작업 여력 시간당 30건(주문 1건=1), 슬롯 정원 20, 재고 예약 10분.
- 시험 재고: `MFC-SEED-01` 에 SKU 17개(E-00000001~5, B-00000006·7, 내부 확인용 E-00000008~17) 각 **10개**(초기 보유 반입 승인 경로). 결제 진입마다 10분 동안 재고가 잡혀 있다가 결제하지 않으면 풀린다.
- 확인한 것: 배포·기동, 초기 재고 반영, 슬롯 목록에 점유 수(`heldCount`·`confirmedCount`·`remaining`), 비회원 토큰으로 결제 진입 403.
- **실제 결제 진입은 고객(Google 로그인) 토큰이 있어야 해서 LAN 에서 아직 끝까지 확인하지 못했다**(통합 시험으로 확인). 앱에서 로그인 → 주문서 → 결제 버튼 → `PAY` → 결제위젯까지 확인 부탁. 결과(특히 `checkoutFailure` 가 나오면 code)를 알려 주면 백엔드에서 사가 기록으로 추적한다.
- 결제창 주문명은 지금 "PMart 장보기 N개 상품"(초안에 상품명 사본이 없음).
