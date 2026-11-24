# CDC WMS 상태표

근거: WMS v8.1 3.3, L467, L907~955, L1174~1186, L1399, L1471, L1538, L2073, L2743~2754, L3547~3553

## PutawayTask / MOV (cdc-inbound)

`CREATED → RESERVED(공간 예약·출발 점유) → DISPATCHED → IN_PROGRESS → PARTIALLY_COMPLETED → COMPLETED` ; `FAILED | EXCEPTION_HOLD → RETRY_READY → RESERVED(잔량만)` ; `CANCELLED`(실물/권리 정리 확인 후). 이동 중 `PICKED_UP → MOVING → PLACED`. 불변 `Q = C + U + K`.

## Wave (cdc-outbound)

`DRAFT → PLANNED → RELEASED → IN_PROGRESS → WORK_DONE → CLOSED` / `CANCELLED`. (`RELEASED`는 릴리스이지 예약 해제가 아님.)

## Task (cdc-outbound)

`CREATED → ASSIGNED → ACCEPTED → IN_PROGRESS → PARTIAL | DONE` / `EXCEPTION` / `CANCELLED`. 작업 진행 축: 피킹중 → 피킹완료 → 검품완료 → 상차완료(LOADED). `DONE ≠ QUANTITY_SETTLED ≠ CLOSED`.

배정(2026-11-25 사용자 결정 "피킹은 다음 작업을 PDA 가 자동 배정", 계약 0.2.0): 작업자 PDA 의 **다음 작업 받기**(`POST /cdcs/{cdcId}/workers/{workerId}/next-tasks`)는 `CREATED → ACCEPTED`(받기 = 수락, 한 작업 한 사람), **착수**(`POST /tasks/{taskId}/starts`, 담당만)는 `ACCEPTED → IN_PROGRESS`. `ASSIGNED` 는 관리자 수동 배정(예외, 아직 경로 없음) 자리로 남긴다.

## TRF 출고 축 (cdc-outbound → cdc-inventory)

`RECEIVED → ALLOCATED(부분 PARTIALLY_ALLOCATED) → PICKED → LOADED → DEPARTED / TOUT_POSTED`. 취소 축: `접수 → 중지/확인대기 → 실물·권리정리 → 해제`.

## Departure (출발 사실, cdc-inventory 전기 축)

`NOT_DEPARTED → DEPARTURE_PENDING_CONFIRMATION → DEPARTURE_POSTED` | `FACT_UNKNOWN` | `STOP_PENDING`. 같은 departureLineId는 1회만 전기.

## Transit (cdc-inventory)

`TRANSIT_OPEN → RECEIPT_POSTED_AWAITING_ACK → TRANSIT_RECONCILED` ; 예외 `OUTBOUND_DISCREPANCY`, `RECONCILIATION_PENDING`, `CONFLICT`. 불변 `q = r + l + t`.
WMS 대사 축: 수령 결과 확인 대기 → 대사 처리 중 → 대사 완료 | 충돌/불명.

## 재고 할당 축

`미확보 → 부분 확보 → 확보 → (품질 BLOCKED ↔ 해제) → 실제 출발 소진`. 품질 축: `미판정 / 정상 / 격리 / 혼합`. LOT 식별: `UNVERIFIED / VERIFIED / INVALID`.

## LPN

BOX `PACKING → SEALED → IN_TRANSIT → RECEIVED → OPENED` ; PLT `BUILDING → READY → IN_TRANSIT → RECEIVED → DISMANTLED` ; `EXCEPTION`.

## LOC (cdc-location)

`DRAFT → ACTIVE → BLOCKED | INACTIVE → RETIRED`.

## 초기 보유 (INITIAL_BALANCE)

`준비 → 검증 → 승인 → 반영 → 운영개시`. 1회만.

## Adjustment

`APPROVAL_PENDING → APPROVED → (부분 집행) → EXECUTED` / `REJECTED`. 집행은 inventory, 승인은 본사.
