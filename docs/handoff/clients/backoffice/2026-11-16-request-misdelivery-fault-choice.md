# 요청 — 오배송 확인에 귀책·고객 선택·실제 주소 받기 (case 0.11.0 · order 0.9.0, ADR-0065)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(지원센터 — 오배송 확인 화면, 상담원·지원센터·관리자)
상태: **백엔드는 모두 LAN 배포됨**(2026-11-16 — case 0.11.0 #44, 고객 잘못 3·4단계 #49~#53). **이 화면이 없으면 귀책별 처리 흐름이 시작되지 않는다**(PC 1 배포 기록). 화면만 남음.

> 화면 용어(2026-11-16 사용자 결정): 사건(case) = "접수 건". 아래 API 이름은 백엔드 이름 그대로.
> 고침(2026-11-16 저녁): 처음 판에는 "고객 잘못은 준비 중"이라 적었는데, 같은 날 #49~#53 으로 구현·배포됐다 — 고객 잘못도 이제 쓴다.

## 1. 정한 것 (ADR-0065, 사용자 2026-11-16)
오배송을 확인할 때 **누구 잘못인지(귀책)**와 **고객이 고른 처리**를 함께 기록한다.

| 귀책 `fault` | 처리 `resolution` | 일어나는 일 |
|---|---|---|
| `RIDER` 라이더 실수 | `RESHIP` 재발송(기본) | 회수 → MFC, 같은 상품 다시 피킹해 원래 주소로(재고 없는 품목만 환불). **라이더 원래 배달료 미지급** |
| | `REFUND` 전 품목 환불 | 회수 → MFC, 전 품목 환불. 라이더 원래 배달료 미지급 |
| `SYSTEM` 지도·시스템 오류 | `RESHIP`·`REFUND` | 라이더 잘못과 같고 **배달료는 지급** |
| `CUSTOMER` 고객 주소 오입력 등 | `REDELIVER` 다시 배송(기본) | 시스템이 실제 집과 전달 위치의 거리를 잼 — **200m 안**: 원래 라이더가 가져가 실제 집으로(고객 0원). **200m 이상**: 고객이 고객 앱에서 **9,000원 결제**하면 근처 라이더가 실제 집으로, **30분 안에 결제 안 하거나 거절**하면 회수 후 상품값 − 9,000원 환불 |
| | `REFUND` 취소·환불 | 회수 → MFC, **상품값 − 9,000원** 환불 |

- 귀책을 보내지 않으면 예전과 같다(회수 + 재발송, 배달료 지급). **화면이 귀책을 고르게 바뀌면 늘 보낸다.**

## 2. API (모두 있음)
**고객 잘못 + 다시 배송이면 먼저 실제 주소를 기록**하고, 받은 ID 로 오배송을 기록한다.

1. (CUSTOMER·REDELIVER 만) `POST /api/v1/orders/lookups/{orderId}/redelivery-destinations` — 상담원·지원센터·관리자
   ```json
   { "deliveryId": "DLV-…", "roadAddress": "…", "detailAddress": "…", "postalCode": "…", "reason": "고객 통화 — 동 호수 잘못 입력" }
   ```
   → 201 `{ destinationId, orderId, deliveryId, recordedAt }`. `reason` 필수(2~200자). **일반 주소 변경이 아니다**(주문 배송지는 그대로). 같은 Idempotency-Key 는 같은 ID.
2. `POST /api/v1/cases`(kind `MISDELIVERY`)에 `misdelivery`:
   ```json
   { "kind": "MISDELIVERY", "subjectType": "DELIVERY", "subjectRef": "DLV-…", "summary": "확인 근거",
     "misdelivery": { "fault": "CUSTOMER", "resolution": "REDELIVER", "redeliveryDestinationId": "<1번의 destinationId>" } }
   ```
- 422 `INVALID_MISDELIVERY_RESOLUTION` — 맞지 않는 조합(RIDER·SYSTEM 은 RESHIP·REFUND, CUSTOMER 는 REDELIVER·REFUND, 귀책 없이 resolution).
- 422 `REDELIVERY_DESTINATION_REQUIRED` — CUSTOMER + REDELIVER 인데 주소 ID 없음.
- 기록 뒤 접수 건 `refs` 와 `MisdeliveryConfirmed` 에 귀책·선택이 실린다. 9,000원 결제는 고객 앱 몫(customer-app 요청 `2026-11-16-request-redelivery-payment`), 결제 필요 알림은 notification(N-3).

## 3. 개인정보
- 실제 주소는 개인정보다 — **입력만** 하고, 기록 뒤 화면에 다시 보여 주지 않는다(응답의 주소 필드는 내부 조회용 — 직원 응답에 기대지 않는다). 입력란에 "고객에게 확인한 실제 주소만, 기록자·사유가 남습니다" 안내.
- `reason` 에는 주소·전화번호를 적지 않는다.

## 4. 화면에 바라는 것
- 오배송 확인 입력: "누구 잘못인가요?"(라이더 실수 / 지도·시스템 오류 / 고객 주소 오입력) → 고른 귀책에 맞는 "고객이 고른 처리"만 보이기.
  - 라이더 실수: "라이더 원래 배달료가 지급되지 않습니다" 안내.
  - 고객 주소 오입력 + 다시 배송: 실제 주소 입력(도로명·상세·우편번호·사유) → 주소 기록(1번) → 오배송 기록(2번)을 한 흐름으로. "200m 이상이면 고객이 9,000원을 결제해야 다시 배송됩니다(30분)" 안내.
  - 고객 주소 오입력 + 환불: "상품값에서 9,000원을 뺀 금액이 환불됩니다" 안내.
- 접수 건 상세에 귀책·선택 표시(`refs`).

## 5. 계약
- case `contracts/openapi/case-service.yaml` 0.11.x — `CreateCaseRequest.misdelivery`(`MisdeliveryDecision`).
- order `contracts/openapi/order-service.yaml` 0.9.0 — `POST /orders/lookups/{orderId}/redelivery-destinations`(`RedeliveryDestinationRequest`).
- ADR `docs/adr/0065-misdelivery-handling-by-fault.md`.
