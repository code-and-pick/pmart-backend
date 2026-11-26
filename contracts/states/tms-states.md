# TMS 상태표 (transport-planning, transport-execution, fleet)

> **0.2.0(2026-11-26, TMS 정본 2.3 — 설계 `docs/cdc/design/2026-11-26-tms-first-stage.md`)이 아래 옛 표(TMS v1.1)를 대신한다.** 옛 표는 기록으로 남긴다.
>
> - **TransportRequest(planning, 이관요청서 = TRF)**: `RECEIVED → ACCEPTED | HELD | REJECTED | EXCLUDED → ASSIGNED → DEPARTED → HANDED_OVER`. 보류 사유 `NO_ROUTE`(≠ 차량 부족)·`ALLOCATION_PENDING`·`MIXED_TEMPERATURE`·`TEMP_UNKNOWN`·`OVER_VEHICLE_CAPACITY`·`ORDER_CLOSED`, 반려 `MISSING_FIELDS`·`WRONG_ORIGIN`·`VERSION_AFTER_DEPARTURE`.
> - **TransportOrder(planning, 운송지시서)**: `PLANNING(계획중) → CONFIRMING → DISPATCHED(계획완료 → 운행)` / `SKIPPED(결행)` / `CANCELLED(출차 전만)`. 플래그 `OVER_CAPACITY`·`cancelCandidate`. version 증가·변경 이력.
> - **Trip(execution, tripId = orderId)**: `SCHEDULED → CHECKED_IN(입차) → LOADED(상차완료) → DEPARTED(출차완료) → COMPLETED(운송완료)` / `CANCELLED(출차 전만)`. 출차 뒤 되돌리지 않는다. 지연은 플래그(`delayed`).
> - **Stop(execution)**: `PLANNED → ARRIVED → HANDED_OVER(MATCHED | MISMATCHED) → DEPARTED`. 인계 증빙 없이 출발하지 않는다.
> - **Incident(execution)**: `OPEN → RESOLVED`(1단계). 운행이 끝나도 남는다.
> - **FleetReservation(fleet)**: `HELD → RELEASED`(CANCELLED·REPLANNED·TRIP_ENDED). `[start, end)` 겹침, 차량·기사 따로.
> - 정본 16.3 의 배차마감·운임마감·정산마감은 뒤로(운임 단계).

## (옛 — TMS v1.1, 0.1.0)

근거: TMS v1.1 14장 [설계안], 6.2, 8.1, 11, 16

## TransportRequest (planning)

`RECEIVED → DATA_PENDING | PLAN_READY → ASSIGNED → IN_PROGRESS → OBLIGATION_PENDING → CLOSED` / `CANCELLED_BEFORE_DEPARTURE`. 보류 사유: `ADDRESS_UNVERIFIED`(≠차량 부족), `TIME_INFEASIBLE`, `CARGO_INFO_PENDING`, `OUTBOUND_HOLD`, `RECEIVING_WINDOW_UNKNOWN`, `NO_ELIGIBLE_VEHICLE`, `MODEL_INPUT_PENDING`. CLOSED = 운송 의무·미결 인계 확인(재고·공급 전체 종료 아님).

## Assignment (planning)

`PROPOSED → RESERVED(일부 자원 확보, 지시서 발행 금지) → CONFIRMED(모든 필수 자원) → SUPERSEDED | CANCELLED`. assignmentVersion 증가. 구버전 제안의 늦은 수락 거절.

## Trip (execution)

`DRAFT → VALIDATING → CONFIRMED → LOADING → READY → IN_TRANSIT → PHYSICAL_ENDED → CLOSED`. 출발 후 DRAFT·미출발취소로 역행 금지. `IN_TRANSIT` 사실 삭제 금지. PHYSICAL_ENDED(승인 구간 종료) ≠ CLOSED(의무 대사 완료). 사고·지연은 별도 플래그/사건.

## Stop (execution)

`PLANNED → ARRIVED → WAITING → UNLOADING → HANDOVER_PENDING → HANDOVER_RECORDED → DEPARTED`. 인계 기록과 MFC 수령/검수 확인은 별도 필드. 도착(GPS/버튼) ≠ 수량 인수.

## Departure 사실

`departureId` 1회. 이관별 출발 구성은 하위 레코드. 원장 반영 상태(`TOUT_POSTED / UNKNOWN / RECONCILIATION_REQUIRED`)는 이관별로 분리.

## FleetReservation (fleet)

`REQUESTED → RESERVED → RELEASED | EXPIRED` ; `UNKNOWN`(응답 불명 → 원예약 조회). 차량·기사 점유는 `[start, end)` 겹침 검사, 분리 검증.

## Incident

`OPEN → ASSIGNED → ACTION_PENDING → RESOLVED → CLOSED`. 차량 종료가 사건을 닫지 않는다.

## Linkage (연계)

`PENDING → SENT → ACKNOWLEDGED | UNKNOWN_RESULT | RECONCILIATION_REQUIRED` — 수신자별.

## 변경 경계

| 시점 | 허용 |
|---|---|
| 후보/미배정 | 재편성·철회 |
| 배정/미상차 | 실차·기사·회차·순서 재계획(기존 예약 정리, 구지시 무효) |
| 상차/미출발 | WMS 실물 확인·하차/복귀 후 변경 |
| 실제 출발 후 | ETA·안전 정차·잔여 일정만. 목적지·기출발 구성·완료 정차 고정 |
| 인계 후 | 원사건 정정·차이 조사만 |
