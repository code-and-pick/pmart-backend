보낸 영역: cdc(PC 1 D 클론 — CDC 세션)
받는 앱: cdc-backoffice
원 문서: pmart-clients `docs/handoff/cdc-backoffice/2026-11-26-backend-dock-assignment.md`(DK-1~3)
작성: pmart-backend, 2026-11-26
상태: **DK-1·DK-2 구현(cdc-location 0.8.0·transport-planning 0.3.0·transport-execution 0.3.0) — LAN 배포·게이트웨이 라우트 전**, DK-3 다음

# 회신: 도크 배정

## 질문 답(사용자 2026-11-26)
- **배정 시점: 계획완료 때 자동.** 배차 담당이 운송지시서를 계획완료하면 차량·기사 점유 다음에 도크를 잡습니다. 창 = 상차 시작 ~ 계획 출차, 운행 온도대의 빈 도크(코드 순).
- **층 도크**: 지금 칠곡 도크는 시뮬레이터 R-25 기준 층마다 4칸(5F 냉장 G 2·냉동 H 2, 6F 상온 A 4, 7F 상온 E 4 = 12, 통로 90 — 예 `CDC01-A-6-90-01-0-1`). LAN 위치는 R-25 로 바꾸는 중(사용자 승인됨, PC 1 적용 전) — **바뀌기 전 LAN 에는 그 온도대 도크 칸이 없어서 도크 없이 계획완료됩니다(dockCode null).** 화면은 null 이면 "도크 배정 없음"으로 그려 주세요.
- 칸은 있는데 그 시간대에 다 차 있으면 계획완료가 409 `CAPACITY_EXCEEDED` 로 거절됩니다(차량 점유는 풀림).
- 출차하거나 지시서를 취소하면 도크 예약은 풀립니다(RELEASED).

## DK-1 `GET /cdcs/{cdcId}/dock-reservations?date=&dockCode=&state=` (cdc-location, 자기 센터 운영자)
- date = 서울 날짜(기본 오늘) — 창이 그날에 걸친 예약. 도크 코드 → 시작 순. state = RESERVED·RELEASED(생략 = 둘 다).
- 응답 `{docks[{dockCode, floor, temperatureZone}], items[DockReservation]}` — `docks` 는 그 센터의 ACTIVE 도크 칸 전부(예약이 없어도) — 층별 도크 × 시간 격자의 세로줄.
- 줄 = `DockReservation`: dockReservationId·dockCode·floor·temperatureZone·tripId(= 운송지시서 번호)·purpose(OUTBOUND_LOADING)·state·window{start,end}·version·vehicleId·routeId·plateNo·releaseReason·createdAt.

## DK-2 사본
- `TransportOrder.dockCode`·`dockReservationId`(planning `GET /cdcs/{cdcId}/transport-orders…`), `Trip.dockCode`(execution `GET /cdcs/{cdcId}/trips…`·`/trips/{id}`). 도크가 없으면 null. 지시서 `history` 에 도크 칸이 없어 건너뛰면 `NO_DOCK` 한 줄.

## DK-3 바꾸기 — 다음
- 사람이 도크를 바꾸는 경로는 이번에 없습니다(자동만). 필요하면 다시 요청 주세요 — 지시서·운행 사본도 같이 바꿔야 해서 planning 쪽 경로로 만들 예정입니다.
