# S3 취소 접수 vs 현장 중지·인계 경합 사가

버전 0.1 · 2026-10-23 · 설계안. 조정자: `order-orchestration-service`(접수는 `claim-service`). 근거: OMS 8.3~8.5, 10, 12, A.1.11, A.5.4; MFC IV-08, 인계 fence(1980~1986행); BackOffice BO-12; Delivery 5.4~5.5.

## 0. 원칙

1. **취소 접수시각만으로 인계보다 우선했다고 판단하지 않는다.** 같은 수량에 대해 현장(`mfc-fulfillment`)이 "출고 중지 확정" 또는 "인계 확정" 중 하나를 먼저 확정하고, 후속 요청은 최신 상태로 재심사한다.
2. 실제 인계된 수량은 출고 전 취소 대상이 아니다. 회수·반품 절차(S6)로만 처리한다.
3. 취소 요청 상태 축(접수→출고 중지 확인 중/인계 확인 중→취소 처리 중→취소 완료/취소 불가)은 주문의 상품준비/배송 상태와 **별개**다.
4. 취소 완료·환불 완료·재고 복구는 각각 별도 사실이다. PG 취소 성공 전 "환불 완료" 표시 금지.

## 1. 취소 가능 시점 (문서 기준)

| 시점 | 가능 여부 | 비고 |
|---|---|---|
| 결제 전(초안) | 자유 | 사가 S1 보상 |
| 결제 후 ~ 피킹 전 | 고객 자율 전체 취소(승인 없음) + **고객센터 대리 전체 취소**(2026-11-18) | `[사용자 확정 2026-10-31]` 상품 준비(피킹) 시작 전까지는 자유롭게 전체 취소. 피킹이 시작되면(주문 PREPARING) 고객 자율 취소는 받지 않는다 — 이후는 품절 사전 선택(8.2)·시스템 취소(결품)만 — **고객센터 취소도 없음(ADR-0060, 2026-11-13)** |
| 피킹·패킹 중 ~ 인계 전 | 현장 중지 확인 후 | 포장별 판정 |
| 인계 후(라이더 보유) | 출고 전 취소 불가 → 회수·반품 | Delivery 참고: 회수비 부담 정책 `[결정 필요]` |

구현 메모(2026-11-07, 6b — ADR-0043): 주문이 PREPARING(첫 PICK 시작)이 되면 claim 이 고객 취소를 거절한다. 그 직전 접수된 취소가 피킹 시작 뒤 도착하면 mfc-fulfillment 중지 판정은 `PENDING`(작업 유지)이고, 집품분 판매 제외·복귀(IV-08)가 생길 때까지 S3 는 대기한다.

구현 메모(2026-11-08, ADR-0046 — IV-08): 피킹 뒤 **전체** 중지는 이제 끝까지 간다. fulfillment 가 남은 작업을 취소하고 작업(CLOSED_PARTIAL/CANCELLED)·포장(INVALIDATED)·출고(CANCELLED)를 닫고 RETURN 작업을 만든 뒤, inventory `cancel-returns` 로 집품·포장분을 `CANCEL_RETURN_PENDING`(E−q, H 유지)·예약 R−q·RELEASED → 판정 RESOLVED(포장별·포장 전 단위 STOPPED). S3 의 RELEASE_RESOURCES 는 이미 해제된 예약이라 변화 없는 성공. 판매 가능 복구는 RETURN 작업 실적(inventory MOVE → 선반 STORED, E+q, 이상품은 QUARANTINED)으로만. 재고 결과 모름·진행 중 실적은 sweep 재시도, 재고 거절은 PENDING + last_error(운영자). 시스템 전체 취소(결품 사가)는 PREPARING 주문도 받는다 — 고객 자율 취소는 여전히 CONFIRMED 만.

## 2. 흐름

```
Commerce/CS ─ POST claims(CANCEL) ─► claim-service: 자격·대상 수량(원수량 − 기취소 − 인계 − 진행 클레임) 심사
  → CancellationRequested → order-orchestration.start(S3, stopRequestId)

S3 단계
  a. mfc-fulfillment.StopWork(orderId, lines, stopRequestId)   [동기, 멱등]
     ← WorkStopResult perPackage: STOPPED | HANDOVER_IN_PROGRESS | HANDED_OVER
       · fence가 HANDOVER_IN_PROGRESS이면 "인계 확인 중" 대기 → 결과(HANDED_OVER 또는 fence 만료 후 STOPPED) 재조회
       · 응답 불명 → QueryHandoverResolution(stopRequestId)로 포장별 판정 조회. 신규 취소·해제 반복 금지
  b. delivery-dispatch.CancelAssignment(deliveryId)  [해당 배송의 모든 포장이 STOPPED일 때만]
     ← 픽업 전 → DeliveryCancelledBeforePickup / 픽업 후(인계됨) → 취소 불가, 회수 경로
  c. mfc-inventory: STOPPED 수량의 예약 해제·실물 복귀 확인
     · 피킹 전: R−q, A+q
     · 피킹 후: E−q, R−q → CANCEL_RETURN_PENDING → 복귀·적격 확인 후 E/A+q (검증 없이 판매가능 복구 금지)
  d. order-service: 취소 확정 수량 반영(OrderLineQuantityChanged, scope FULL/PARTIAL), 미인계 잔량·인계량 분리
  e. claim-service: 환불 의도(원가격·원안분; 봉투값은 전체 취소만 환불; 배달팁은 확정 기준) → payment.Cancel/PartialCancel(멱등키)
     ← RefundResult SUCCESS → 취소 완료 안내 / FAILED → 재심사 / UNKNOWN → 원거래 조회(1분×5→상신→15분)
  f. (3차) 쿠폰 복원: PG 취소 성공 확인 시각에 원유효기간 남아 있으면 1회 요청. 만료 연장·신규 발급 없음
  g. HANDED_OVER 수량이 있으면 → S6(회수·반품)으로 분기, 고객 안내
```

## 3. 경합 사례

| 사례 | 판정 |
|---|---|
| 포장 3개 중 2개 인계·1개 미인계, 전체 취소 요청 | 1개만 출고 전 취소, 2개는 회수 심사. 전량 재고 복구·전액 환불 자동 확정 금지 |
| 취소 접수 성공, 현장 중지 응답 불명 | "출고 중지 확인 중" 유지. 고객에게 완료 표시 금지. 원요청 조회 |
| 중지 확정 후 늦은 QR 스캔 | fence가 없으므로 `AuthorizePackageHandover` 거절(INVALID_STATE). 라벨 무효 |
| 인계 fence 중 취소 요청 | 인계 확인 중 → fence 결과 대기. 취소가 먼저라고 인계를 되돌리지 않음 |
| 현장 중지·재고 복귀 완료, PG 취소 불명 | 주문은 취소 처리 중. 환불 완료 안내 금지. 재판매는 재고 복귀 확인으로 가능(PG와 독립) |
| 라이더 픽업 후 고객 취소 | 출고 전 취소 불가. Delivery 실제 인수 상태 확인 → 회수. 라이더 수행료는 환불과 독립(Delivery 5.5) |
| 탈퇴 접수 계정의 진행 중 취소 | 기존 주문 의무로 계속 처리. 탈퇴 완료는 의무 종결 후 |

구현 메모(2026-11-18, 사용자 "그 추천대로" — 저녁 목록 3번): **고객센터 주문 취소**. 고객센터 상담원(CS_AGENT)·운영 관리자(OPERATOR_ADMIN)가 고객 대신 `POST /claims`(type CANCEL, claim 0.4.0)로 접수한다. 범위는 고객 앱 취소와 같다 — 주문 `CONFIRMED`(첫 피킹 시작 전, 작업자 배정만 된 주문 포함)의 전체 취소만, 뒤 흐름(현장 중지 → OrderCancelled → 전액 환불)도 같다. 본인 확인 방법 필수(반품 접수와 같은 값 — ORDER_DETAILS_MATCH·APP_SIGNED_IN_CHAT·OTHER+메모)·사유 필수, 접수 직원·본인 확인·CS 사건이 클레임에 남는다. 상품준비(PREPARING) 뒤는 ADR-0060 그대로 422 — 고객센터는 "받으신 뒤 반품"으로 안내. 경계는 피킹 시작 그대로(배정 ≠ 준비). 하지 않은 것: 대리인(가족 등 — case `callerRelation` PROXY) 취소를 서버가 막기(지금은 본인 확인 방법만 기록), 피킹 시작 뒤 작업을 풀어 준비 전으로 되돌리기.

## 4. 운영자(BackOffice BO-12) 가능 동작

- 조회: OMS 적격 판정, MFC 포장별 판정, Delivery 인수 상태, PG 결과를 **원천별 시각과 함께** 표시.
- 명령: 원결과 재조회 / 미전달 재전송 / 승인된 보상 실행. "강제 취소 성공", "예약 강제 해제"는 없다.
- 환불 한도: `추가 가능액 = 승인액 − 성공 누적 − 불명 점유` 초과 요청 차단(payment가 강제).

## 5. 종결 조건 (A.4.3)

- 주문행 수량 대사 일치(미이행 + 인계 누계 + 출고 전 취소 누계 = 원수량)
- 예약 해제·실물 복귀가 mfc-inventory commitId로 확인
- PG 취소 결과 확정(불명 아님)
- 배차 취소/회수 의무 결정
- 고객 안내 전송(실패 시 통지만 재시도)

## 6. 구현 (2026-10-31, 슬라이스 8 첫 단계: 피킹 전 전체 취소)

범위: 고객 앱의 **주문 전체 취소**만. 백오피스 승인 없이 자동 처리하며, 취소 가능 조건은 주문 CONFIRMED(상품 준비 전, §1 사용자 확정). 이행(슬라이스 6)·배송(슬라이스 7)이 아직 없어 확정 주문은 모두 작업 지시 전이다. 부분 취소(고객 임의 — OMS 8.4 로 열지 않음)·품절 부분 취소(S2)·회수(S6)·운영자 개입(BO-12)·쿠폰 복원(1차 제외)은 범위 밖.

```
고객앱 POST /claims {type: CANCEL, lines: 모든 행 × 미이행 수량}
 → claim-service: order /internal/orders/{id} 로 본인·CONFIRMED·수량 심사(부분 → 422 PARTIAL_CANCEL_NOT_ALLOWED, 진행 중 클레임 → DUPLICATE_CLAIM)
   클레임 STOP_PENDING + stopRequestId 발급, ClaimOpened·CancellationRequested(order.claim.v1) 한 트랜잭션
 → order-orchestration S3(사건 구독, stopRequestId 하나에 사가 하나 — POST /internal/cancellation-sagas 도 같은 시작)
   a. STOP_WORK: mfc-fulfillment POST /internal/stop-requests(주문 단위). 작업 지시 전 → 전량 STOPPED(notStartedLines) + 중지 기록 + WorkStopResult
      · PENDING·HANDOVER_IN_PROGRESS → WAITING_RESULT(GET …/stop-requests/{id} 재조회) · HANDED_OVER → ESCALATED(해제·주문 반영 없음)
   b. (배차 취소 — 배송 기능이 없어 단계 없음)
   c. RELEASE_RESOURCES: S1 이 확정한 슬롯·작업 여력·재고 예약 해제(사유 ORDER_CANCELLED / CANCEL_BEFORE_PICK, sourceRef=stopRequestId).
      원예약 조회가 RELEASED·EXPIRED 일 때만 해제로 기록, 미확인은 같은 키로 재시도. 업무 거절은 ESCALATED
   d. APPLY_ORDER_CANCEL: order /internal/orders/{id}/line-quantity-changes(CANCEL_BEFORE_SHIP, claimId)
      → 주문 CANCELLED, OrderLineQuantityChanged·OrderCancelled(accountId·firstPurchase·claimId) → 사가 COMPLETED
 → claim-service: OrderCancelled(claimId) → 행 APPROVED·IN_PROGRESS → 환불 의도(원 승인액 전액 = 상품액+봉투값+확정 배달팁, 사용자 결정 2026-10-31)
   + RefundIntentCreated → payment POST /internal/payments/{id}/cancellations(멱등키 REFUND-<의도 ID>)
   e. RefundResult SUCCESS(또는 원거래 조회 SUCCEEDED) → 환불 COMPLETED·클레임 CLOSED·ClaimClosed. FAILED → 환불 FAILED(클레임 IN_PROGRESS, 운영자 확인)
 → customer-service: OrderCancelled(FULL, firstPurchase) → 그 주문의 첫 구매 사용 기록 삭제(OMS 175행 — 출고 전 전체 취소는 자격을 소진하지 않는다)
 → claim-service: WorkStopResult 에 HANDED_OVER 가 있으면 클레임 REJECTED(HANDED_OVER)
```

- 단계 명령 키는 `sagaId:단계`(해제는 `sagaId:RELEASE:<자원>`), 환불은 의도마다 고정 키 — 재개·재전송이 정본에 한 번만 반영된다.
- 응답 불명·미전달은 실패로 단정하지 않는다(사가 sweep·환불 sweep 이 같은 키로 재전송·원결과 조회).
- 사가 상신(ESCALATED)은 자동으로 되돌리지 않는다. 운영자 개입(BO-12)·case 상신은 아직 없다 — 사가 행의 last_error 로 확인.
- 재고 해제는 현장 중지 확정 뒤에만, 환불은 주문 취소 반영 뒤에만. 재판매 가능(재고 해제)과 환불 완료는 서로 독립(§3).
- 후속(슬라이스 6~): 작업 지시가 생기면 STOPPED 포장별 판정·피킹 후 복귀(CANCEL_RETURN_PENDING — 2026-11-08 ADR-0046 구현)·fence 대기(6c), 작업 요청이 중지 기록을 반영. 피킹 뒤 부분 중지는 아직 없다. 배차 취소(b)는 슬라이스 7.
- 인계 fence(2026-11-08, ADR-0047 구현): 살아 있는 fence·결과 대기 인계가 있으면 중지 PENDING(그 포장 HANDOVER_IN_PROGRESS) → 확정되면 HANDED_OVER, 확정 요청 없이 만료되면 fence 를 닫고 중지 진행. 인계된 포장이 하나라도 있으면 전체 중지는 RESOLVED + HANDED_OVER 로 거절(작업 유지, S3 ESCALATED·클레임 REJECTED). 중지 기록이 있는 주문은 새 인계 허가를 받지 않는다. 계약 정본 [package-handover-contract.md](package-handover-contract.md).

## 7. 배차 불가 취소 (2026-11-19, CL-5 — 원문 Delivery §3.6)
- 라이더를 끝내 못 찾은 주문은 **직원**(그 센터 MFC_SUPERVISOR·CS_AGENT·OPERATOR_ADMIN)이 사유 `DISPATCH_UNAVAILABLE` 로 전체 취소한다. 회사 사유라 본인 확인이 없고, **상품준비 뒤라도 인계 전이면** 받는다(시스템 자동 취소와 같은 범위 — §1 의 고객 취소 시점과 다름). 인계 뒤는 422(반품 흐름).
- 흐름은 §2 그대로: ClaimOpened·CancellationRequested(reason DISPATCH_UNAVAILABLE) → S3(현장 중지 — 포장분은 취소 복귀, 자원 해제, 주문 CANCELLED) → OrderCancelled(`cancelReason` DISPATCH_UNAVAILABLE) → claim 원 승인액 전액 환불(봉투값·배달팁 포함), dispatch 는 FULL 취소로 픽업 전 배송을 거둔다, notification 은 "배달 기사를 찾지 못해" 문구.
- 보상 쿠폰(2,000원)은 promotion-service 가 생길 때(아직 없음).
- **자동 취소(2026-11-20, 사용자 "쿠폰 안 되면 자동취소만"·"약속+30분")**: delivery-dispatch 가 고객 배송(FORWARD)이 배송 약속 시각 + 30분(`pmart.dispatch.auto-cancel-after`)이 지나도 라이더 없이 배차 대기(인계 없음)면 claim `POST /internal/claims/system-cancellations`(reason DISPATCH_UNAVAILABLE, channel SYSTEM, 멱등 키 `dispatch-no-rider:<배송 ID>`)를 한 번 부른다. 뒤 흐름은 위와 같고, OrderCancelled(FULL)로 그 배송도 픽업 전 취소된다. claim 이 거절(이미 인계·취소 등)하면 다시 부르지 않고 직원 수동 취소로, 응답 없음은 1분 뒤 다시.

## 8. 관리자 판단 취소 (2026-11-20, ADR-0076 — 사용자 "관리자 사유 취소만 넓힘")
- 직원이 회사 사유로 준비 중 주문을 멈출 때 사유 `OPERATOR_DECISION`(관리자 판단)으로 전체 취소한다. 누가: 그 센터 MFC_SUPERVISOR·OPERATOR_ADMIN 만(CS_AGENT 403 — §7 배차 불가와 다름). **§7 배차 불가와 같은 범위(상품준비 뒤라도 인계 전, 인계 뒤 422)·같은 흐름**, 본인 확인 없음, **사유 메모(reasonDetail) 필수**.
- 고객 요청 취소는 바뀌지 않는다 — 상품준비 전만(§1, ADR-0060). 고객 토큰은 이 사유를 쓸 수 없다(400).
- OrderCancelled `cancelReason` OPERATOR_DECISION — 고객 알림은 일반 취소 문구.
- 작업 단위(PICK·PACK) 중지·BLOCKED 해제·부분 종료·다시 열기는 아직 없다(업무 규칙 결정 필요).
