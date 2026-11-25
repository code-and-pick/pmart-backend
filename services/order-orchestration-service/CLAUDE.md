# order-orchestration-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `order-orchestration-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/order-orchestration-service.yaml` → `openApiGenerate` 가 `com.pmart.order.orchestration.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.order.orchestration.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:order-orchestration-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:order-orchestration-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8202.

## S1 결제 진입 사가 (2026-10-31, 슬라이스 4 a~f)
- 흐름·보상·만료 규칙의 정본: `docs/business/flows/order-checkout-saga.md` §1.4. 규칙은 `domain/CheckoutRules`, 실행은 `application/CheckoutSagaService`.
- 시작 명령은 사가만 저장(202)하고 단계는 가상 스레드에서 진행. 단계 명령 키 `sagaId:단계` — 재개·재전송이 정본에 한 번만 반영된다. `sweep()` 이 멈춘 사가·자원 만료를 처리.
- 단계 실행 중 사가 행을 `FOR UPDATE` 로 잡는다(같은 사가를 두 실행자가 진행하지 않음). 하위 호출은 `Participants` 한 곳(내부 호출).
- 해제는 원예약 조회로 RELEASED·EXPIRED 를 본 뒤에만 RELEASED. 결제 불명·불일치면 자원을 풀지 않는다.
- 발행: `CheckoutSagaStateChanged`·`CompensationExecuted` (order.saga.v1).

## S3 취소 사가 (2026-10-31, 피킹 전 전체 취소)
- 정본: `docs/business/flows/cancel-vs-handover.md` §6. 규칙 `domain/CancellationRules`, 실행 `application/CancellationSagaService`, 표 `cancellation_saga`(V4).
- 시작: `CancellationRequested`(order.claim.v1, inbox) 또는 `POST /internal/cancellation-sagas`(FULL 만). stopRequestId 하나에 사가 하나.
- 단계: STOP_WORK(mfc-fulfillment `/internal/stop-requests`, 전부 STOPPED 일 때만 진행·대기는 WAITING_RESULT 재조회·인계됨은 ESCALATED) → RELEASE_RESOURCES(주문을 확정한 S1 사가의 보상 로그에서 슬롯·여력·재고를 찾아 취소 사유로 해제, 원예약 조회로 확인) → APPLY_ORDER_CANCEL(order 수량 변경) → COMPLETED. 환불은 claim 이 한다.
- 업무 거절은 ESCALATED(자동 보상 없음, 수동 개입 BO-12 미구현). 설정 `pmart.orchestration.mfc-fulfillment-base-url`.

## S2 이행 요청 사가 (2026-11-07, 슬라이스 6a)
- 정본: `docs/business/flows/order-checkout-saga.md` §2.0·§2.0.1. 규칙 `domain/FulfillmentRules`, 실행 `application/FulfillmentSagaService`, 표 `fulfillment_saga`·`fulfillment_attempt`(V5). 구독 `OrderConfirmed`(order.order.v1, `orderEvents-in-0`).
- 단계: REQUEST_FULFILLMENT(작업 버전마다 키 `sagaId:REQUEST_FULFILLMENT:v<n>`) → 접수 COMPLETED / 재시도 대상 거절 RETRY_WAITING(AWAIT_RECOVERY, 1분 고정 재확인) / 그 밖 REQUEST_SYSTEM_CANCEL(claim) → COMPLETED. 고객 취소 사가가 있으면 CLOSED_BY_CANCEL.
- 설정 `pmart.orchestration.fulfillment.*`: `start-orders-confirmed-after`(없으면 S2 시작 안 함), 센터별 `retry-window."[센터]"`·`max-new-versions."[센터]"`(없으면 CONFIG_REQUIRED 보류, 자동 취소 안 함). `claim-base-url`.
- 대기 마감(처음 거절 + W)·사용한 재시도 수(work_version − 1)는 저장하지 않고 계산 — 설정이 늦게 들어와도 처음 거절 기준.

## 상태
S1 a~j 구현(결제 사건 구독 order.payment.v1 → 대사 → 자원 확정 → 주문 확정, 실패 시 보상 취소 확인 뒤 해제). S3 피킹 전 전체 취소(2026-10-31). S2 작업 요청·같은 MFC 재시도·자동 취소(2026-11-07, 6a). S4·S2 의 결품·배차, S3 의 피킹 후/인계·배차 취소, case 상신은 501·미구현. **수동 개입(BO-12, 2026-11-16, 계약 0.2.0)**: `GET /internal/stuck-sagas`(`ManualActionStore` — 네 사가 표 합쳐 읽기만)·manual-actions REQUERY_RESULT(결제 확인 대기 → `CheckoutSagaService.advance`)·MARK_RESOLVED(V7 `saga_manual_resolution` — 사가 상태는 그대로), 승인 없이(operatorId·reason). 재통지·보상 실행은 422 UNSUPPORTED_ACTION. 사가 조회(`/internal/sagas`)는 S1·S2.
- 결제 전 자원 점유 유효시간 `pmart.orchestration.hold-ttl`(ADR-0042, Q-04 결정 10분 — 코드 상수 `HOLD_TTL_MINUTES` 대체). 없으면 새 결제 진입 422 CONFIG_REQUIRED, 진행 중 단계는 예외로 멈추고 sweep 이 값이 들어온 뒤 잇는다.
- **결품 사가(IV-09 2부, 2026-11-07, ADR-0045, V6)**: `LineShortageReported`(mfc.fulfillment.v1, 바인딩 `fulfillmentEvents-in-0`)마다 `shortage_saga`(사건 ID 유일). `ShortageRules`: CANCEL_OOS_ONLY → CHECK_ORDER(주문 원장 — 전체 결품이면 REQUEST_FULL_CANCEL, 아래) → RESOLVE_WORK(fulfillment shortage-resolutions) → RELEASE_SHORTFALL(inventory shortfall-releases) → APPLY_ORDER_CHANGE(order SHORTAGE) → REQUEST_REFUND(claim shortage-refunds) → COMPLETED. 선택 없음은 시작 때 ESCALATED. 업무 거절 상신, 결과 모름은 같은 키 sweep. 시작 기준 시각은 `fulfillment.start-orders-confirmed-after`.
  - 2026-11-08(ADR-0046): CANCEL_ALL 은 첫 단계가, CANCEL_OOS_ONLY 라도 전체 결품이면 CHECK_ORDER 다음이 **REQUEST_FULL_CANCEL**(claim `system-cancellations` reason OUT_OF_STOCK, 키 `sagaId:REQUEST_FULL_CANCEL`) → 클레임 ID 로 COMPLETED. 뒤는 S3(현장 중지 → 피킹 뒤면 fulfillment 가 취소 복귀까지 처리해 RESOLVED).
- **결품은 무조건 부분 취소·환불(ADR-0049, 2026-11-08)**: `ShortageRules` 가 사전 선택을 보지 않는다(상신 없음). 남은 수량 전부 결품일 때만 REQUEST_FULL_CANCEL.
- **오배송 재발송 사가(2026-11-16 — ADR-0052 결정 4·5, 사용자 "새상품 재배차 서비스 넣어" → "전부 PR로", 작성 PC 5, V8)**: 정본 `docs/business/flows/misdelivery-recovery-reshipment.md` §2. 구독 `caseEvents-in-0` ← platform.case.v1 `MisdeliveryConfirmed`(`ReshipmentSagaService`, `reshipment_saga` caseId 유일, 시작 기준 `pmart.orchestration.reshipment.start-cases-confirmed-after` — 없으면 시작 안 함, 소비 그룹 시작 위치 latest). `ReshipmentRules`: RESOLVE_ORDER(dispatch `GET /internal/deliveries/{id}/assignment` → orderId, order 원장 보낼 줄) → RECORD_RESHIPMENT(order RESHIPMENT) → RESERVE_INVENTORY(422 INSUFFICIENT_STOCK details 로 줄여 다시, 키 `:RESERVE_INVENTORY:<n>`, 최대 5) → CONFIRM_RESERVATION(결제 없음) → REQUEST_WORK(fulfillment kind RESHIPMENT·reshipmentRef, 출고 `<orderId>-RS-<8자>`) → APPLY_SHORTAGE(못 보내는 줄 SHORTAGE) → REQUEST_REFUND(claim shortage-refunds) → COMPLETED. 업무 거절 상신, 모름은 sweep. `Participants.Rejected` 에 Problem `details`, dispatch 주소 `pmart.orchestration.delivery-dispatch-base-url`(기본 `http://delivery-dispatch-service:8404`).
- 결품 사가: `LineShortageReported` 의 `purpose: MISDELIVERY_RESHIPMENT`(재발송 작업 결품)면 CHECK_ORDER(주문 전체 취소 판정)·사전 선택 없이 RESOLVE_WORK 부터 — 그 품목만 부분 취소·환불(2026-11-16).
- 결품 사가: reason `PARTIAL_CLOSE`(mfc-fulfillment 0.30.0 센터 관리자 부분 종료 잔량, ADR-0079)도 같은 단계 — RELEASE_SHORTFALL 만 inventory 사유 `PARTIAL_CLOSE_CANCELLED`(`ShortageRules.releaseReason`, 집지 않은 예약 해제). 계약 0.4.2(모양 변화 없음). 시험 `ShortageRulesTest.partialCloseReleasesUnpickedReservation`.
- **처리 대기에 재발송 사가(2026-11-16, 사용자 "운영자 처리 대기 목록에 재발송 건 추가해")**: `ManualActionStore` 다섯 번째 표 `reshipment_saga`(sagaType `RESHIPMENT`, ESCALATED → MARK_RESOLVED). 주문을 찾기 전에 멈추면 orderId 빈 값 — lastErrorMessage 앞에 `오배송 사건 <caseId> · 배송 <deliveryId>`. backoffice-bff 처리 대기는 sagaType 을 그대로 전달.
- 아직: 재발송 지연 기한·고객 안내(ADR-0052 남은 것).
- **오배송 귀책(2026-11-16, ADR-0065, V9 `reshipment_saga.mode` RESHIP·REFUND)**: `MisdeliveryConfirmed` fault CUSTOMER 는 재발송 사가를 만들지 않는다(고객 잘못 — 다시 배송·9,000원 결제·상품값 − 9,000원 환불은 다음 단계). RIDER·SYSTEM(또는 귀책 없는 예전 사건): resolution REFUND → `reshipment_saga.mode` REFUND — RESOLVE_ORDER → RECORD_RESHIPMENT → APPLY_SHORTAGE(전 줄) → REQUEST_REFUND(전 품목, 재고 예약·작업 없음), 그 밖 RESHIP(재발송).
- **고객 잘못 + 환불(2026-11-16, ADR-0065)**: mode `CUSTOMER_REFUND`(V10) — 전 줄 SHORTAGE 뒤 claim 환불에 `feeDeduction` 9,000원(`ReshipmentRules.CUSTOMER_FAULT_FEE` — 업무 원문 회수재배송 9000원). 고객 잘못·다시 배송은 이 사가가 아니다(배송 200m 판정·결제).
- **오배송 다시 배송 9,000원 결제 대기(2026-11-16, ADR-0065 4단계, V11 `redelivery_payment`, 계약 0.3.0)**: 정본 흐름 문서 §2.2. 구독 `deliveryEvents-in-0` ← delivery.delivery.v1 `RedeliveryPaymentRequired`(`RedeliveryPaymentService`, caseId 유일 — 나머지 배송 사건은 지나감), order.payment.v1 은 `PaymentEventConsumers` 가 결제 진입 사가와 함께 넘긴다(자기 결제 시도만). `RedeliveryPaymentRules`: PREPARE_PAYMENT(주문 참조 `RDL-<caseId>`) → AWAIT_PAYMENT(30분, 진행 중 결제는 기다림) → RELEASE_DELIVERY 또는 CONVERT_TO_RECOVERY(dispatch + 재발송 사가 CUSTOMER_REFUND) → COMPLETED, 늦은 승인 CANCEL_LATE_PAYMENT(보상 취소). **고객 경로**(이 서비스의 첫 고객 API — 게이트웨이 라우트 `/api/v1/redelivery-payments/**` 필요): `GET /redelivery-payments`, `POST …/{id}/payment-attempts`·`…/declines`(고객 토큰·본인 accountId, RequestIdempotency). 처리 대기 sagaType `REDELIVERY_PAYMENT`.
- **S4 예약 시간 변경 사가(2026-11-16, 계약 0.4.0, V12 `time_change_saga` — 작성 PC 5)**: `POST /internal/time-change-sagas`(order 가 부름, requestId 유일·주문당 진행 중 하나) → `TimeChangeSagaService`/`TimeChangeRules`: FIND_RESOURCES → HOLD_SLOT(newSlotFor) → HOLD_CAPACITY(replacesHoldId) → MOVE_LOT_DATE(날짜 바뀔 때) → RESCHEDULE_WORK(확정 지점) → CONFIRM_SLOT·CAPACITY → REPORT_ORDER. 확정 지점 전 거절 = RELEASE_NEW(새 점유 해제·약속일 되돌림) → REJECTED, 뒤 거절 = 상신(처리 대기 sagaType TIME_CHANGE). S3 `resourcesOf` 는 시간이 바뀐 주문이면 마지막 확정 변경의 새 슬롯·여력. 정본 흐름 `order-checkout-saga.md` §2.2.
- **사고·파손 재발송(2026-11-18 — ADR-0069, 작성 PC 5)**: `deliveryEvents` 가 execution `DeliveryFailed` 도 받아 `ReshipmentSagaService.onDeliveryEvent` — reason RIDER_INCIDENT·DAMAGED 이고 일반(FORWARD)·오배송 재발송 배송이면 재발송 사가(mode RESHIP, `case_id` = `DF-<deliveryId>`, 보낼 수량 = 원수량 − 출고 전 취소 − 전달됨 — `ReshipmentRules.failedResendQty`). 시작 기준은 오배송과 같은 `start-cases-confirmed-after`. **고침**: 모자란 품목이 없을 때 REQUEST_WORK 뒤 끝나지 않고 작업 지시를 끝없이 다시 부르던 것(`requestWork` 가 `ReshipmentRules.next` 의 null 을 보게).
- **S3 슬롯 점유 기록 없음 = 풀 것 없음(2026-11-25, PC 2 — 사용자 "실 서버 10월 31일 예약주문 취소가 안돼" → "이 pc에서 고쳐")**: RELEASE_RESOURCES 에서 SLOT_HOLD 해제가 `SLOT_HOLD_NOT_FOUND` 로 거절되면 ABSENT 로 두고 진행(`CancellationRules.absent` — 슬롯만). 2026-11-10 PC 이전 때 옛 slot_hold 가 지워져 그 전 주문 취소가 ESCALATED 로 멈췄다. `sweep()` 이 그 사유로 상신된 사가를 `CancellationStore.reopenSlotHoldMissing`(saga_manual_resolution 없는 것만)으로 RUNNING 되돌림. 시험 `CancellationSagaIT.missingSlotHoldCountsAsNothingToRelease`·`sweepReopensSagaEscalatedForMissingSlotHold`.
- **S3 취소 사유 전달(2026-11-19, CL-5)**: APPLY_ORDER_CANCEL 본문에 `cancelReason` = 사가 시작 사유(CancellationRequested.reason — CUSTOMER_REQUEST·FULFILLMENT_REJECTED·OUT_OF_STOCK·DISPATCH_UNAVAILABLE). 멈춤 요청에는 사유를 넣지 않는다. 시험 `CancellationSagaIT.cancelBeforePickReleasesAndCancelsOrder`.
