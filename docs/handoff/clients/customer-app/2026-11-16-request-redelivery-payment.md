# [pmart-backend → pmart-clients] 요청: 오배송 다시 배송비 9,000원 결제 화면 (2026-11-16)

- 보낸 영역: delivery(PC 5 — 플랫폼 영역 PR 로 PC 1 검토)
- 받는 앱: customer_app (주문 상세·알림에서 들어가는 결제 화면)
- 근거: ADR-0065 결정 3(사용자 2026-11-16 — "200m 이상 차이나면 추가금 발생하고 원래 집에 배송", 결제 대기 30분), 계약 `order-orchestration-service.yaml` 0.3.0, `payment-service.yaml`(기존 결제 승인 그대로)

## 1. 언제 생기나
고객이 주소를 잘못 넣어 상품이 다른 곳에 배달되고, 상담원이 실제 주소를 기록했는데 **잘못 둔 곳과 실제 집이 200m 이상** 떨어져 있으면 고객이 **9,000원**을 내야 같은 상품을 실제 집으로 다시 배송한다. **30분** 안에 결제하지 않으면 상품을 회수하고 **상품값 − 9,000원**을 환불한다. 고객은 결제 대신 바로 "회수 후 환불"을 고를 수도 있다.

## 2. API
| 할 일 | 호출 |
|---|---|
| 결제할 건 보기 | `GET /api/v1/redelivery-payments?orderId=<주문>` → `items[]`: `redeliveryPaymentId`, `orderId`, `amount`(9000), `orderName`("오배송 다시 배송비"), `paymentAttemptId`, `status`(WAITING_PAYMENT·PAID·DECLINED·TIMED_OUT), `deadlineAt` |
| 결제 | 토스 결제위젯을 **`orderId = paymentAttemptId`**, `amount`, `orderName` 으로 띄움(clientKey·customerKey 는 기존 `GET /payment-widget-config`) → 성공 리다이렉트의 `paymentKey` 로 기존 `POST /api/v1/payment-confirmations` `{orderId: paymentAttemptId, paymentKey, amount}` → 결과는 `GET /api/v1/payments/{paymentAttemptId}` |
| 결제 실패 뒤 다시 | `POST /api/v1/redelivery-payments/{id}/payment-attempts`(Idempotency-Key·X-Source-System) → 새 `paymentAttemptId` 로 위젯 다시. 기한이 지났거나 대기 중이 아니면 409 |
| 결제 안 하고 환불 | `POST /api/v1/redelivery-payments/{id}/declines` → status DECLINED. 이미 결제됐으면 409 ALREADY_PAID |

- `paymentAttemptId` 가 비어 있으면 결제 시도를 준비 중 — 잠시 뒤 목록을 다시 읽는다.
- 화면에는 `deadlineAt` 까지 남은 시간과 "결제하지 않으면 상품을 회수하고 상품값에서 9,000원을 뺀 금액을 환불해 드려요"를 보여 주기를 권한다(문구는 앱에서 정함).
- 결제됨(PAID) 뒤에는 기존 배송 추적 화면 그대로(다시 배송도 같은 주문의 배송으로 보인다).

## 3. 아직 없는 것
- 기한 임박 알림은 없다. "결제 필요" 알림은 알림함에 들어간다(N-3 — 템플릿 `REDELIVERY_PAYMENT_REQUIRED`, 분류 PAYMENT, 목적지 ORDER = orderId). 그 알림을 열면 주문 상세에서 이 결제 화면으로 들어갈 수 있게 해 주세요.
