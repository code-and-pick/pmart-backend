보낸 영역: cdc(PC 1 D 클론 — CDC 세션)
받는 앱: cdc-backoffice
원 문서: pmart-clients `docs/handoff/cdc-backoffice/2026-11-26-backend-missing-screens.md`(PR #579) TR-1~3 — IB·RP 는 `2026-11-26-reply-backend-missing-screens.md`, LG-1 은 `2026-11-26-reply-stock-movements.md`
작성: pmart-backend, 2026-11-26
상태: **구현(TMS 1단계 — fleet 0.2.0·transport-planning 0.2.0·transport-execution 0.2.0) — LAN 배포·게이트웨이 라우트 전**(PR #526~#530)

# 회신: TR 출하·운송

사용자가 "운송 시작"을 정해 TMS 1단계(정본 `docs/business/TMS-business-logic.md` 2.3 — 고정 노선 × 온도대 정규차)를 만들었습니다. 설계 `docs/cdc/design/2026-11-26-tms-first-stage.md`. 계약은 main 의 `contracts/openapi/{fleet,transport-planning,transport-execution}-service.yaml`(0.2.0 — 옛 0.1.0 과 완전히 다릅니다).

## TR-2 출발 운행 — `GET /cdcs/{cdcId}/trips?shipDate=&state=&delayed=&page=&size=` (execution, 자기 센터 운영자)
- 계획 출차 → 노선 → 온도대 순, page 0부터·size ≤ 200·hasNext.
- 줄 = `Trip`: tripId(= 운송지시서 번호 TO-yyyyMMdd-nnn)·routeId·tempClass·vehicleId·plateNo·driverName(표시 이름만)·state(SCHEDULED 계획완료·CHECKED_IN 입차·LOADED 상차완료·DEPARTED 운행 중·COMPLETED 운송완료·CANCELLED)·**delayed**(어느 정차든 예상 도착이 계획 + 30분 또는 07:30 을 넘음)·계획/실제 출차·boxCount·rtCount·trfCount·stops[seq·mfcId·state·trfIds·plannedArrivalAt·**estimatedArrivalAt**·arrivedAt·handover(MATCHED/MISMATCHED·부족/초과/파손 박스)]·incidents·events.
- 운행 하나 `GET /trips/{tripId}`, MFC 쪽 도착 예정 `GET /mfcs/{mfcId}/inbound-trips?arrivalDate=`.
- 대시보드 "다음 출발 n분 뒤" = state SCHEDULED·CHECKED_IN·LOADED 중 가장 이른 plannedDepartureAt.

## TR-1 배차 계획(planning, 자기 센터 운영자 — 결정은 CDC_SUPERVISOR)
- 배차 현황 `GET /cdcs/{cdcId}/transport-orders?shipDate=&state=&routeId=` — 노선 × 온도대 운송지시서: state(PLANNING 계획중·CONFIRMING·DISPATCHED 계획완료·SKIPPED 결행·CANCELLED)·차량·loadRt/maxRt·loadRatio·OVER_CAPACITY·cancelCandidate(물량 0)·stops·trfIds·history.
- 잔여 물량 `GET /cdcs/{cdcId}/transport-requests?shipDate=&state=HELD` — 보류 사유 NO_ROUTE·ALLOCATION_PENDING·TEMP_UNKNOWN·MIXED_TEMPERATURE·OVER_VEHICLE_CAPACITY·NO_REGULAR_VEHICLE·ORDER_CLOSED.
- 동작: 생성 `POST …/transport-orders/generations {shipDate}` · 요청서 옮기기 `…/{orderId}/request-moves` · 계획완료 `…/{orderId}/confirmations` · 결행 `…/skips` · 취소 `…/cancellations`(모두 Idempotency-Key·X-Source-System).
- 기준정보: 노선 `GET /cdcs/{cdcId}/routes`, 차량·기사 `GET /vehicles`·`GET /drivers`(fleet).

## TR-3 도크 배정 — 뒤로
- 도크 예약(cdc-location)은 칠곡 층 도크(R-22)가 확정된 뒤. 지금 운행에는 도크가 없습니다.

## 화면에서 알아 둘 것
- 운행 상태 이벤트(입차·출차·도착·인계)는 지금 직원이 대신 입력하거나 시뮬레이터가 넣습니다(기사 앱 없음). 입력 화면이 필요하면 `POST /trips/{tripId}/check-ins·loading-completions·departures·physical-ends`, `POST /trips/{tripId}/stops/{seq}/arrivals·handovers·departures`.
- 노선 시각·차량 수치는 [가정] 값이라 바뀔 수 있습니다.
