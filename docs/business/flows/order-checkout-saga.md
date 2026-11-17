# S1·S2 주문 결제 진입 → 확정 → 이행 사가

버전 0.1 · 2026-10-23 · 설계안. 조정자: `order-orchestration-service`. 근거: OMS 4~9장, A.1.4·A.1.7·A.1.9·A.1.15, A.5.1~A.5.3; Commerce 10~12장; MFC IV-05/07/08, WK-14; Delivery 3·5장.

## 0. 참여자와 정본

| 참여자 | 확정하는 것 |
|---|---|
| order-service | 초안·견적·주문 상태·스냅샷·이행 계획 |
| order-orchestration | 사가 단계·타임아웃·보상 로그 |
| mfc-inventory | 재고 예약 R(HELD→CONFIRMED→CONSUMED) |
| mfc-capacity | 작업 여력 CapacityHold |
| delivery-capacity | 도착 슬롯 점유(예약배달) |
| payment | PG 승인·불명·실패·보상 취소 |
| mfc-fulfillment | 작업 수락·준비 시작·결품·포장·인계 |
| delivery-dispatch / execution | 배차·픽업·전달 |

## 1. 사가 S1: 초안 → 자원 확보 → PG → 확정

```
Commerce ─(1) POST drafts {purchaseIntentId, requestId}─► order-service
order-service: 회원·판매상태·구매상한(Σ qty×unitsPerSku ≤ 10)·권역/MFC·슬롯·중량 검증, pricing 견적, 배달팁/봉투값/최소 15,000원 판정
             → draft(quoteVersion, quoteExpiresAt = now+10m) 스냅샷 저장, OrderDraftCreated
Commerce ─(2) POST drafts/{id}/checkout─► order-service ─► order-orchestration.start(S1)

S1 단계 (각 단계 동기 명령 + 결과 이벤트/조회, 모두 requestId 멱등)
  a. 가격 동결·초안 유효 확인 (quoteExpiresAt)                          — 만료 → RE_QUOTE_REQUIRED (재동의)
  b. mfc-inventory.ReserveInventory(orderId, lines, ttl=10m)          — 전품목 성공만 통과. 일부 성공 → 성공분 해제 후 FAIL
  c. mfc-capacity.HoldCapacity(orderId, window)                       — 실패 → b 보상
  d. delivery-capacity.HoldSlot(orderId, window)  [예약배달만]           — 실패 → b,c 보상. 미설정/조회 실패 → CONFIG_REQUIRED 보류(0/무제한 아님)
  e. (3차) promotion.HoldCoupon / 행사 한도                              — 1차 생략. 첫 구매 자격 점유는 order-service 내부
  f. payment.StartPayment(orderId, amount, paymentAttemptId)           — PG 결제창 진입 정보 반환
  g. payment 승인 결과 대기: PaymentApproved | PaymentFailed | PaymentResultUnknown | PaymentMismatch
  h. 승인·금액·통화·주문 참조 일치 확인 → order-service.Confirm(orderId, version)
  i. 확정 성공 → inventory.ConfirmReservation, capacity.ConfirmHold, slot.ConfirmHold → OrderConfirmed
  j. 확정 실패(저장 실패·운영 제한 발생) → payment.CompensationCancel(원거래) 결과 확인 → 자원 해제
```

### 1.1 타이머 규칙

| 타이머 | 시작 사건 | 만료 처리 |
|---|---|---|
| 가격 동결 10분 | 초안 생성 | 재견적·고객 재확인. 자동 해제·환불 아님 |
| 재고 예약 10분 | ReserveInventory 성공 | 원거래 조회 → 미승인/실패 확정이면 해제·`OrderExpired`. **불명이면 `결제 확인 대기 점유`로 전환**(해제 금지) |
| PG 불명 조회 | 승인 불명 | 1분 간격 5회 → `case-service` 상신 → 15분 간격 후속 조회. 수동 종결도 PG 원거래 증거 필수 |

PG 진입 전 두 타이머 모두 유효해야 하며 더 이른 만료를 고객에게 안내한다.

### 1.2 분기

| 상황 | 처리 |
|---|---|
| 같은 purchaseIntentId+requestId 재전송 | 기존 결과 반환. 같은 키 다른 본문 → `IDEMPOTENCY_CONFLICT` |
| 다품목 중 1품목 예약 실패 | PG 차단, 성공분 예약 해제 확인(이벤트 `InventoryReleased`) |
| 마지막 1개에 두 주문 | 예약 성공 주문만 PG. 결제 완료 순으로 승자 변경 없음 |
| 승인 성공·주문 저장 실패 | 영속 결제시도·원거래로 복구 시도. 불가 시 승인취소 보상. 새 주문·재승인 금지 |
| 승인액 ≠ 청구액 | `PaymentMismatch` → 확정 차단, `ReconciliationRequired`, 강제 출고 금지 |
| 예약 소멸 후 지연 승인 | 재확보 가능성 확인 → 복구 또는 승인취소. 승인취소 성공 전 재판매 금지 |
| 운영 제한(접수 중지) 발생 | 확정 전이면 차단·보상. 확정 후면 기존 약속 유지, 준비 보류 조사 |
| 주류 포함 | 토스페이먼츠 결제 인증 결과 확인. 자체 성인인증·현장 신분증 없음 |

### 1.3 보상 원칙
- 이번 시도에서 **새로 확보한** 예약·Hold·Slot만 해제한다(보상 로그에 resourceId 기록).
- 해제 응답 불명은 원예약 조회로 확인. 확인 전 "해제 완료" 표시 금지.
- PG 승인 후 보상은 `CompensationCancel`이며 그 결과(`CompensationCancelResult`)까지 대사해야 사가 종료.

### 1.4 구현 상태 (2026-10-31, 슬라이스 4·5 — a~j)

| 단계 | 구현 | 비고 |
|---|---|---|
| (2) 결제 진입 | order `POST /order-drafts/{id}/checkouts` | 견적 버전·10분 동결·차단 사유 확인 → orderId 발급(같은 진입 요청은 같은 ID) → orchestration 시작. 진행 중이면 기존 주문 ID 반환 |
| 시작 | orchestration `POST /internal/checkout-sagas` 202 | 사가만 저장하고 단계는 비동기(동기 체인 2 hop 규칙). 같은 초안의 진행 중 사가는 하나 |
| a | VALIDATE_QUOTE | 동결 경과 → RE_QUOTE_REQUIRED |
| b | RESERVE_INVENTORY | mfc-inventory 전부 또는 전무, TTL 10분, 부족 → INSUFFICIENT_STOCK, 취급 OFF → SKU_NOT_HANDLED. LOT 단위 조각(FEFO, 줄당 LOT 수 제한 없음), 최소 잔여일 기준일 = `promisedDate`(예약 배송 = 슬롯 날짜, 즉시 = 예약 날짜) — 적격 LOT 부족도 INSUFFICIENT_STOCK(NO_ELIGIBLE_LOT), ADR-0040 |
| c | HOLD_CAPACITY | mfc-capacity 1시간 창(예약배달은 도착 슬롯, 즉시는 지금 시간 KST). LAN 시험값 주문 1건=1단위·시간당 30 |
| d | HOLD_SLOT | 예약배달만(그 밖 SKIPPED). delivery-capacity 정원 20 |
| e | (쿠폰) | 1차 생략 |
| f | START_PAYMENT | payment 결제 시도 생성, `resourceExpiresAt` = 자원 만료·가격 동결 중 이른 것 → 사가 WAITING_RESULT, 초안 `nextAction=PAY` 와 결제창 정보 |
| g | AWAIT_PAYMENT_RESULT | 결제 사건(order.payment.v1)은 깨우는 신호, 정본은 결제 시도 조회. 승인 → 보상 로그에 PAYMENT_APPROVAL. 실패 → 즉시 해제. 불명 → UNKNOWN_TRACKING. 불일치 → RECONCILIATION_REQUIRED |
| h | RECONCILE_PAYMENT | 승인액·통화 = 청구액(KRW). 다르면 RECONCILIATION_REQUIRED + `ReconciliationRequired`(확정·취소·해제 모두 멈춤, 강제 출고 금지) |
| i | CONFIRM_RESOURCES | 재고 예약·여력·슬롯 HELD → CONFIRMED(키 `sagaId:CONFIRM:자원`). **흐름 원문(h 주문 확정 → i 자원 확정)과 순서를 바꿨다**: `OrderConfirmed` 는 자원 확정 뒤 발행(사건 카탈로그 조건) |
| (h) | CONFIRM_ORDER | order `/internal/orders/{id}/confirmation` → 주문 저장·`OrderConfirmed` → 사가 COMPLETED. 금액 불일치 422 → 대사 |
| j | 보상 취소 | 확정 실패(점유 소멸 RESERVATION_LOST — 재고가 결제 중 격리·실사 손실로 보류돼 재확보 못 한 몫이 있으면 inventory 확정이 409 RESERVATION_SHORT 로 거절, 2026-11-08 — ·주문 확정 거절 ORDER_CONFIRM_FAILED) → payment 보상 취소(전액) → **CANCELLED 확인 뒤에만** 재고·여력·슬롯 해제 → COMPENSATED |

- 단계 명령 Idempotency-Key = `sagaId:단계`, 해제 = `sagaId:RELEASE:자원종류`. 중단·불명이면 sweep(10초)이 30초 멈춘 사가를 같은 키로 이어서 진행한다.
- 거절 → 보상 로그의 자원만 역순 해제 → 원예약 조회로 RELEASED·EXPIRED 확인 뒤에만 RELEASED → 모두 확인되면 COMPENSATED. 초안은 `DRAFT`·`REVIEW` + `checkoutFailure`(견적이 유효하면 다시 결제 진입 가능, 새 주문 ID).
- 결제 대기 중 sweep 이 30초마다 결제 시도를 조회한다(사건 유실 대비). 미승인(CREATED·PENDING)은 자원 만료 뒤에만 해제(`RESOURCE_EXPIRED`), 조회 실패면 해제하지 않고 다시 확인. payment 는 `resourceExpiresAt` 뒤 승인 API 를 부르지 않는다. 불명 1분×5 재조회·상신은 payment 가 하고(`PaymentResultUnknown.escalated`), case 사건 연결은 슬라이스 9.
- 각 정본도 자체 TTL 로 HELD 를 EXPIRED 처리한다(재고·여력·슬롯). 초기 재고는 INITIAL_STOCK 승인 → mfc-inbound 초기 보유 반입 → mfc-inventory 전기(D2).

## 2. 확정 후: 이행 요청 (S2 시작)

```
OrderConfirmed → order-orchestration → mfc-fulfillment.ConfirmOrder(orderId, fulfillmentId, lines, window, outOfStockPreference, lotConstraints)
  ← WorkAccepted (작업 접수 ≠ 피킹 완료) | WorkRejected(재고 불일치/LOT 부적격/운영 중단/여력 부족)
WorkRejected → 같은 MFC 내 적격 재고 복구 가능?(D6-5: 재고 불일치·운영 중단, 10분·최대 2번) → 새 작업 버전 재개 : 자동 전체 취소 + 전액 환불(사용자 결정 2026-11-06, 이전 "고객 선택(보류/취소·정산)" 대체). 타 MFC 재배정 없음.
mfc-fulfillment: PreparationStarted → order-service `상품준비`(이후 예약 시간 변경 차단)
mfc-fulfillment: 피킹 → 패킹 → PackingFinished → ShipmentReady(포장·봉투·중량·주류)
```

### 2.0 슬라이스 6 선결 결정 (사용자 결정 2026-11-06)

| # | 질문 | 결정 | 영향 |
|---|---|---|---|
| D6-1 | 상품준비(`PreparationStarted`) 시점 | **첫 피킹 시작**(작업자가 첫 품목을 집기 시작할 때). 작업 접수·작업 생성·명령 전송만으로는 준비가 아니다 | 그 전까지 고객 자가 취소 가능(cancel-vs-handover.md). 이후 예약 시간 변경 차단 |
| D6-2 | 출고(shipment) 단위 | **주문당 출고 1개**(MFC Q-10 기준선, 10kg 분할 미채택) | `ConfirmOrder.shipments[]` 는 원소 1개. 분할·합배송은 Q-10 결정 뒤 출고 줄 추가로 확장 |
| D6-3 | 예약 주문 PICK `READY` 시점 | **배송 슬롯 시작 N분 전**. N 은 센터 운영 설정, LAN 값 **90분**. 미설정 센터는 그 작업만 `CONFIG_REQUIRED` 보류(0·즉시로 대체하지 않음) | 즉시 배송(가장 이른 슬롯)은 접수 즉시 READY 와 같아진다 |
| D6-4 | `WorkRejected` 뒤 | **자동 전체 취소 + 전액 환불**(고객 선택 단계 없음). 타 MFC 재배정 없음 | 취소 사가(S3)와 claim 환불(원 승인액 전액)로 처리. 작업이 없으니 STOP_WORK 는 이미 끝난 것으로 본다 |

| D6-5 | `WorkRejected` 뒤 같은 MFC 재시도(OMS 6장 "적격 재고 복구 → 새 작업 버전") | **6a 에 포함.** 대상 사유는 **재고 불일치·운영 중단**만(여력 부족은 결제 때 여력을 이미 점유했으므로 실제 문제로 보고 바로 D6-4, LOT 부적격은 6a 에 LOT 가 없어 해당 없음). order-orchestration 이 거절 뒤 **W분 동안 1분마다** 재고 원장의 남은 예약·적격 재고를 다시 확인하고, 되찾았으면 **새 작업 버전**으로 ConfirmOrder 를 다시 보낸다. **W = 10분, 주문당 새 작업 버전 최대 2번**(센터 운영 설정 — 미설정이면 재시도 없이 D6-4 가 아니라 그 주문만 `CONFIG_REQUIRED` 보류). 배송 슬롯 시작이 지나면 기다리지 않고 D6-4 | 그동안 고객 자가 취소는 열려 있다(피킹 전, D6-1). 재시도 실패·횟수 초과·창 만료 → D6-4 |

D6-5 세부(사용자 결정 2026-11-07):
- (2026-11-07 ADR-0042: IV-09 구현 때 "같은 MFC 의 같은 SKU 적격 LOT 재확보" 로 넓힌다 — 그 전까지 아래 그대로.)
- **되찾음 기준 = 원래 재고 예약만.** 원래 예약이 다시 확정 상태로 요청 수량을 모두 덮을 때만 새 작업 버전을 보낸다. 새 예약을 잡지 않는다(6a 에는 예약 막힘·풀림이 없어 실제로 되찾는 경우는 드물다 — 재고 막힘 기능이 생기면 의미가 생긴다).
- **즉시 배송**(슬롯 없음)도 같은 규칙: 10분·최대 2번.
- **대기 10분은 처음 거절부터 한 번만** 센다(재거절 때 다시 주지 않는다). 설정이 늦게 채워져도 처음 거절 기준.
- D6-3 N 미설정: **작업은 접수하고 PICK 만 `CONFIG_REQUIRED` 대기**(설정이 들어오면 시각을 다시 계산). 주문을 취소하지 않는다.
- 6a 범위 사실: "운영 중단" 거절의 원천(mfc-capacity 운영 제한)은 아직 501 이라 그 경로는 시험으로만 확인한다. 상품준비(`PreparationStarted`)는 첫 피킹 시작이라 6b.

기본값(사용자 확인 2026-11-06):
1. 작업 접수 때 출고마다 PICK(`READY` — 예약 주문은 D6-3) + PACK(`WAITING`, 피킹 완료 뒤 `READY`) 작업을 만든다. 출발(DISPATCH)은 6c.
2. 6a 는 작업자 배정 없음(수동 배정 WK-02/Q-08 은 6b).
3. 접수 때 패킹대 여력을 다시 검사하지 않는다(결제 진입 때 MFC 여력 점유로 충분).
4. 이미 `order_work_stop` 이 있는 주문: 부분 중지면 나머지 범위만 작업 생성, 전체 중지면 거절(사유: 이미 중지).

### 2.0.1 구현 상태 (2026-11-07, 슬라이스 6a)

| 단계 | 서비스 | 구현 |
|---|---|---|
| 이행 계획 | order | `OrderConfirmed.shipments` = 출고 1개 `<orderId>-S1`(모든 행), 행 `productName` |
| S2 시작 | order-orchestration | `OrderConfirmed`(order.order.v1, 바인딩 `orderEvents-in-0`) → `fulfillment_saga`(V5, 주문당 1). `pmart.orchestration.fulfillment.start-orders-confirmed-after` 전 확정 주문은 무시(새 구독 그룹이 토픽 처음부터 읽음), 미설정이면 S2 를 시작하지 않는다 |
| REQUEST_FULFILLMENT | orchestration → mfc-fulfillment | ConfirmOrder(`workVersion`, 키 `sagaId:REQUEST_FULFILLMENT:v<n>`). fulfillment 가 이미 중지된 수량을 빼고(전부면 `ALREADY_STOPPED`), 원래 재고 예약이 확정으로 덮는지 보고(아니면 `INVENTORY_MISMATCH`), 접수면 출고마다 PICK(READY / 예약 주문은 슬롯 시작 − N분까지 WAITING / N 없으면 `CONFIG_REQUIRED` 대기) + PACK(WAITING). 결정은 201 본문, `WorkAccepted`/`WorkRejected` 발행 |
| AWAIT_RECOVERY | orchestration | 재시도 대상 거절 → `RETRY_WAITING`, 1분마다 mfc-inventory 원래 예약 재확인 → 되찾으면 작업 버전 +1. 처음 거절 + W(센터 `retry-window`), 새 버전 최대 M(`max-new-versions`), 슬롯 시작 경과 → 자동 취소. 센터 설정 없음 → `CONFIG_REQUIRED`(1분마다 재평가, 기준은 처음 거절). 운영 중단은 해제를 알 원천이 없어 재확인 때 바로 새 버전(fulfillment 가 판정) — 6a 에는 그 거절의 원천도 없다 |
| REQUEST_SYSTEM_CANCEL | orchestration → claim | `POST /internal/claims/system-cancellations`(reason FULFILLMENT_REJECTED, channel SYSTEM) → `CancellationRequested` → 기존 S3(중지 → 해제 → 주문 취소) → 전액 환불. 진행 중 고객 취소가 있으면 claim 이 그것을 돌려준다 |
| 끝 | orchestration | `COMPLETED` + outcome(`WORK_ACCEPTED` / `CANCEL_REQUESTED` / `CLOSED_BY_CANCEL` — 고객 취소 S3 가 먼저 시작됨). 하위 4xx 는 `ESCALATED`. 조회 `GET /internal/sagas/{id}`, `GET /internal/sagas?orderId=&sagaType=S2_FULFILLMENT` |
| 접수 뒤 취소 | mfc-fulfillment | 주문 단위 중지가 접수된 작업 행을 취소(잔량 0 작업·출고 CANCELLED). 6a 는 피킹 시작이 없어 판정은 전부 STOPPED |

6b 이후: 작업자 배정·피킹 시작(`PreparationStarted` → order 상품준비)·결품·패킹·출발, 포장별 중지 판정(fence).

### 2.0.2 구현 상태 (2026-11-07, 슬라이스 6b — ADR-0043)

- 배정: 센터 관리자(`MFC_SUPERVISOR`)가 mfc-capacity `POST /assignments` 로 작업마다 담당 1명(재지정 = 버전 +1) → `AssignmentPublished` → fulfillment 담당 투영(READY → ASSIGNED, 진행 중 재배정은 ASSIGNED 로 돌림).
- 시작: 작업자(`MFC_WORKER`) `POST /tasks/{id}/starts` — capacity 배정 동기 조회 후 판정 기록. 그 주문의 첫 PICK 시작 = `PreparationStarted`(D6-1) → order CONFIRMED → PREPARING·`OrderPreparationStarted`(이후 고객 자율 취소 거절).
- 피킹: `POST /tasks/{id}/executions`(PICK, 한 선반) → mfc-inventory PICK(선반 → 포장 구역, 예약 조각 집품량, 총량 불변). 모두 집으면 PICK COMPLETED, PACK READY/ASSIGNED. 결품 보고는 BLOCKED + `LineShortageReported`(2.1 처리는 IV-09 단계).
- 포장: PACK 시작 후 `POST /shipments/{id}/packages`(LOT 은 집품분에서 소비기한 빠른 순) → mfc-inventory PACK → SEALED·QR. 집품분 전부 포장 → PACK COMPLETED, 출고 READY, `PackingFinished`·`ShipmentReady`(배차 요청은 6c).
- 피킹이 시작된 주문의 작업 중지(2026-11-08, ADR-0046 — IV-08): 전체 중지면 남은 작업 취소·작업 종료·포장 INVALIDATED·출고 CANCELLED·RETURN 작업 → inventory `cancel-returns`(집품·포장분 `CANCEL_RETURN_PENDING`, 예약 해제) → RESOLVED(전부 STOPPED). RETURN 실적(MOVE)으로 선반에 돌려놓을 때 판매 가능. 부분 중지는 아직 `PENDING`. inventory 는 집품 시작된 예약의 단순 해제를 여전히 409 `RESERVATION_PICKED`(취소 복귀로만).

### 2.1 결품(LineShortageReported) 처리

구현(2026-11-07, ADR-0044 — IV-09 1부): 결품·파손은 먼저 MFC 안에서 같은 SKU 적격 LOT 으로 재확보를 시도하고, **재확보 못 한 양만** `LineShortageReported` 로 나온다. 2부(ADR-0045, 결품 사가): `품절 상품만 취소`는 자동 — 작업 수량 취소 → 이행부족 해제 → 주문 수량 변경(SHORTAGE) → 부분 환불(원단가×수량, 봉투·배달팁 제외). 선택 없음(CS 미구현)은 상신(수동)으로 두고 작업은 BLOCKED 유지. `전체 취소`·주문 전체 결품(2026-11-08, ADR-0046)은 결품 사가 REQUEST_FULL_CANCEL → claim 시스템 전체 취소(reason OUT_OF_STOCK, 주문 PREPARING 허용) → S3(현장 중지가 집품분을 취소 복귀로) → 주문 취소 → 환불 = 원 승인액 − 앞선 결품 부분 환불(봉투·배달팁 포함).

예약 보류분(2026-11-08, 사용자 결정 #5 안 C): 결제 뒤 격리·실사 손실로 생긴 보류(`ReservationBlocked`)는 **피킹 시작 전에는 mfc-inventory 가 자동 재확보**(1분, `ReservationBlockCleared` → order 확인 필요 종결)하고, **피킹 시작 때(·집품마다) 남아 있으면 mfc-fulfillment 가 `LineShortageReported`(reason BLOCKED)** 로 넘긴다 — 아래 표 그대로.

**결품 처리(2026-11-08 사용자 결정, ADR-0049 — ADR-0045 의 사전 선택별 처리를 대체)**: 고객 사전 선택과 관계없이 **무조건 그 품목만 부분 취소·환불** — 미인계 범위에서 해당 품목 부분 취소 → claim.RefundIntent(원가격·원안분) → payment.PartialCancel → RefundResult → OrderLineQuantityChanged. 남은 주문이 15,000원 미만이어도 유지, 재결제·배달팁 추가 없음, 대체 상품 없음, 상신·CS 이관 없음. 예외: 결품이 주문의 남은 수량 전부면 전체 취소(봉투값·배달팁 포함 전액 환불).

부분 취소 PG 요청은 `추가 환불 가능 한도 = 승인액 − 성공 누적 − 불명 점유` 안에서만, 같은 요청 ID로 1회.

## 2.2 사가 S4: 예약배달 시간 변경 (2026-11-16 — CP-1 2부, 규칙 OMS §4·A.2.5, Commerce §9.4)
고객 `POST /orders/{id}/reservation-time-changes`(order: 본인·예약배달·CONFIRMED·다른 시간대·진행 중 없음 → PENDING, 202) → orchestration `time_change_saga`.

| 단계 | 하는 일 | 실패 |
|---|---|---|
| FIND_RESOURCES | 지금 점유 = 확정된 이전 시간 변경의 새 점유, 없으면 주문을 확정한 S1 의 점유. 작업 지시 ID(fulfillment) | 없으면 REJECTED |
| HOLD_SLOT | delivery-capacity `newSlotFor` = 지금 슬롯 → 새 HELD(기존 그대로, 정원 둘 다) | 거절 → REJECTED(원인 코드) |
| HOLD_CAPACITY | mfc-capacity `replacesHoldId` = 지금 여력 → 새 창 HELD | 거절 → 새 슬롯 해제 → REJECTED |
| MOVE_LOT_DATE | 날짜가 바뀔 때만 — mfc-inventory 약속일 변경(잡힌 LOT 이 새 날짜에도 적격) | LOT_EXPIRY_NOT_MET → 새 점유 해제 → REJECTED |
| **RESCHEDULE_WORK** | **확정 지점** — mfc-fulfillment 작업 지시 시간 변경(준비 시작 전만 — 먼저 확정된 쪽만 유효) | PREPARATION_STARTED → 새 점유 해제·약속일 되돌림 → REJECTED |
| CONFIRM_SLOT·CONFIRM_CAPACITY | 새 점유 확정 — 같은 트랜잭션에서 기존 해제(TIME_CHANGED·RESCHEDULED) | 거절은 상신(처리 대기 TIME_CHANGE) |
| REPORT_ORDER | order 결과 — CONFIRMED 면 주문 도착 시간대·버전·`ReservationTimeChanged`, REJECTED 면 그대로 | 모름은 sweep |

- 새 결제·새 피킹·주소 변경 없음. 실패하면 새로 잡은 것만 풀고 기존 시간·예약·금액 유지(OMS §4).
- 시간이 바뀐 주문의 취소(S3)는 새 슬롯·여력을 해제한다.
- 아직: `ReservationTimeChanged` 구독(고객 안내·배차 약속) 없음. 새 점유 TTL(슬롯 30분·여력 30분) 안에 확정 지점까지 가지 못하면 확정 단계가 거절돼 상신.

## 3. 배차·인계·전달

```
ShipmentReady → delivery-dispatch: DeliveryCreated(배송 단위 = order 이행 계획; 10,000g 초과 분할은 order-service가 결정)
  → 후보 탐색(rider-service 적격: 운행중·신규배차 ON·권역·수단·제한 없음) → DispatchOfferSent(1분) → DeliveryAssigned(동시 수락 1명)
라이더 가게 도착(RiderArrivedAtStore) → QR 스캔(포장 전수)
  → mfc-fulfillment.AuthorizePackageHandover(packageId, riderId, deliveryId)  ; 배정 일치·기인계·타 주문·제한 라이더 검사 → HANDOVER_IN_PROGRESS(fence)
  → mfc-fulfillment.CommitPackageHandover → mfc-inventory.CommitPackageHandover(H−q, E−q, R−q, commitId)
  → PackageHandedOver(inventoryCommitId) → order(인계 누계), delivery-execution(PickupCompleted 조건), claim(출고 전 취소 불가 범위)
고객 도착 → 사진 업로드 성공 → 전달 완료 버튼 → DeliveryCompleted(증빙 ref)
  → order: 배송 단위 완료 집계 → 모든 유효 배송 완료 시 OrderCompleted (시간 경과 자동완료 없음)
  → review 자격 시작(품목별 수령 시각), notification(분할은 전량 완료 1회), demand-planning 실적
```

구현 메모(2026-11-08, ADR-0047): MFC 쪽(배정 투영·인계 허가 fence·인계 확정·재고 인계 전기·`PackageHandedOver`)은 구현됐다. 서비스 간 약속의 정본은 [package-handover-contract.md](package-handover-contract.md) — 배차·라이더·배송 실행·주문은 그 문서 §5 를 따른다.

### 3.1 수량 불변식 (order-service가 검증)
`주문행 원수량 = 미이행 보유 + 유효 인계 누계 + 출고 전 취소 누계`. 인계 후 반품은 별도 회수/검수/환불 수량(과거 인계 삭제 금지).

### 3.2 분기
| 상황 | 처리 |
|---|---|
| 배차 실패(기사 없음·반복 거절·만료) | `DispatchFailed` → 점포·CS 안내, 고객 취소 시 S3. 배차 불가로 PMart 측 취소면 환불(쿠폰 보상은 3차) |
| 픽업 전 배차 취소 | 상품 점포 보관, 재배차. 인계 fence 해제 |
| 픽업 후 배차 취소 | `case-service` 지원센터: 상품 위치·회수·재인계 확인 후 처리 |
| 전달 실패 | 자동 환불 없음. 고객 연락·보관·회수 판단(case). 재배송은 교환 승인 건만. **상품 반납(2026-11-17)**: 지원센터 확정 + 상품이 라이더 손(custody RIDER)이면 dispatch 가 반납 배송(RETURN·FAILED_DELIVERY_RETURN)을 같은 라이더에게 제안 없이 배정 → 받음 없이 MFC 반납(ReturnDelivered). 수행료: 고객 사유면 실패 배송 = 수락 금액 + 반납 = 일반 표(고객 집 → MFC), 그 밖 사유면 둘 다 지원센터 조정. 매장 반입·검수는 mfc-inbound(MI-2 — 아직) |
| 완료 후 파손·누락 신고 | 기존 `DeliveryCompleted` 유지, `DeliveryIncidentRaised` + claim 접수 |

## 4. 수용 검증 (문서 OMS 14장 발췌)

| 조건 | 기대 |
|---|---|
| PG 승인·OMS 확정 응답 유실 | 원주문 조회/복구, 신규 주문/재승인 0 |
| WMS 예약 성공·MFC 슬롯 실패 | PG 차단·원예약 해제 확인 |
| 10분 만료와 PG 결과 불명 | 신규 결제·출고 차단, 취소/거래 확인 없는 재판매 없음 |
| 10,000g / 10,001g | 전자 미분할, 후자 2배송. 1배송 완료는 부분 전달 |
| 부분 품절 | 원가격·할인 안분으로 PG 부분취소 1회, 잔여 주문·누적 환불 한도 유지 |
| 취소와 포장 인계 동시 | 먼저 확정한 상태 기준, 이중 재고 복구 없음 (→ S3) |
