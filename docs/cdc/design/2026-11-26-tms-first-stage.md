# TMS 1단계 설계 — 칠곡 DC → MFC 간선 운송 (fleet · transport-planning · transport-execution)

작성: 2026-11-26 CDC 세션 · 근거: `docs/business/TMS-business-logic.md` 2.3(정본 — 사용자 노션 "TMS" 2026-11-25 판), `docs/architecture/service-map.md` §6, 칠곡 결정 CDC-6·CDC-8(`docs/simulator/cdc/chilgok-dc.md`)
요청: PC5 DC 백오피스 TR-1~3(pmart-clients #579 — `GET /cdcs/{cdcId}/trips`), 사용자 "시작해 — TMS md 참고해서"

## 0. 사용자 결정(2026-11-26)
- **서비스 범위: transport-planning · transport-execution · fleet 세 개**(fleet 은 최소 — 운송사·차량·기사·점유). routing(OR-Tools)·cold-chain 은 뒤로.
- **모델은 TMS 2.3 에 맞춰 계약을 고친다**(옛 0.1.0 은 TMS v1.1 기준 — 후보 계산·S8 자원 예약 사가). 고정 노선 × 온도대 정규차 자동 배차, 운송지시서 상태는 정본 16.3.
- 이미 있는 결정: 공급 MFC 5곳(CDC-6), 용차 없음(CDC-8 — 한 대 20 R/T 를 넘으면 알림만, TRF 는 나누지 않음), 회수 없음(D-11 — 편도, DC 복귀까지만), 실시간 GPS 없음(D-08).

## 1. 서비스 경계
| 서비스 | 포트 | 정본 | 하지 않음 |
|---|---|---|---|
| fleet-service | 8703 | 운송사(CR-)·차량(VH- — 계약 구분·톤수·온도대·최대 R/T·최대 중량)·기사(DR- — 표시 이름만)·점유 예약(`[시작,끝)` 겹침, 차량·기사 따로) | 배차 결정, 운행 |
| transport-planning-service | 8701 | 노선 마스터(RT- — 정차 순서·출차 시각·구간 시간)·노선 × 온도대 정규차 계약, 이관요청서 수신·검증(V1~V8)·상태, **운송지시서**(TO- — 계획중·계획완료·결행·취소, 버전), 잔여 물량 | 차량·기사 정본(fleet), 실제 출발·인계(execution), 재고·TRF 수량(cdc-outbound·cdc-inventory) |
| transport-execution-service | 8702 | 운행(= 계획완료된 운송지시서의 실행, tripId = 운송지시서 ID), 입차·상차완료·출차·정차 도착·인계·정차 출발·DC 복귀 이벤트, **출발 사실 departureId**, 인계 증빙(HO-), 예상 도착·지연 플래그, 운행 예외 | 배차·재배정(planning), TOUT(cdc-inventory — cdc-outbound 출발 검증이 부른다), TIN(mfc-inventory) |

- TMS 는 재고를 바꾸지 않는다(정본 13장). 출차 사실 → cdc-outbound `POST /internal/departure-validations`(이미 있음) → cdc-inventory TOUT.
- 운송지시서 1건 = 차량 1대 × 운행 1회 = 운행(trip) 1건. planning 은 계획까지, 계획완료 순간 execution 에 운행을 만든다. 정본 16.3 의 상차완료 이후 상태는 execution 의 운행 상태다(planning 운송지시서는 `DISPATCHED` 로 넘기고 운행 상태를 사본으로 받는다).

## 2. 기준정보(시드 — 칠곡·5곳)
- 노선(정본 4.3 에서 5곳이 든 노선만, 정차는 등록된 MFC 만):
  - RT-04 `MFC-BJG-01`(부산진구) → `MFC-BNG-01`(부산남구) · RT-07 `MFC-SEJ-01`(세종) · RT-09 `MFC-CHC-01`(춘천) · RT-10 `MFC-MOKPO-01`(목포)
  - 노선마다 계획 출차 시각·DC→첫 정차·정차 간 주행 분은 **[가정]**(정본 3.3 식으로 첫 도착 05:00~06:00, 마지막 도착 ≤ 07:30). 실측 행렬은 routing(뒤로) 때.
- 차량: 노선 × 온도대(상온·냉장·냉동) 정규차 한 대씩 = 12대, 5톤·20 R/T·5,000kg [가정], 운송사 CR-01 하나 [가정]. 기사 12명(표시 이름 "기사 01"…).
- 시드 스크립트 `deploy/lan/seed/tms_*.py`(fleet → planning 순).

## 3. 이관요청서(transport request)
- **가져오기(pull)**: planning 이 cdc-outbound 새 내부 조회 `GET /internal/transport-feed?cdcId&dueFrom&dueTo`(cdc-outbound 0.26.0)에서 TRF 줄을 읽어 이관요청서로 만든다. cdc-outbound 가 planning 을 부르지 않는다(출고가 TMS 에 묶이지 않게). 줄 = trfId·버전·mfcId·dueAt·TRF 상태·승인·할당량·온도대(검품 BOX 기준 — 검품 전이면 비어 TEMP_UNKNOWN 보류, 정본 시간표상 생성은 21:30 출고 완료 뒤)·BOX 수·R/T 수·무게.
- **출고일 D**: dueAt(도착 기한, Asia/Seoul)이 (D 12:00, D+1 12:00] 인 TRF. 정본 시간표(D 23:00 출차 → D+1 07:30 도착)에 맞춘 규칙.
- 검증(정본 5.2, 수용 ACCEPTED / 보류 HELD / 반려 REJECTED / 제외 EXCLUDED):
  - V1 필수값 · V2 출발이 그 DC·목적이 노선에 등록된 MFC(노선 없음 = 보류 `NO_ROUTE` — 차량 부족과 구분)
  - V4·V5 같은 trfId·버전은 그대로, 새 버전은 출차 전이면 반영(운송지시서 다시 계산), 출차 뒤면 반려
  - V6 할당: ALLOCATED → 수용, PARTIALLY_ALLOCATED → 할당량만 수용, 할당 전·실패 → 보류 `ALLOCATION_PENDING`, 취소 → 제외
  - **D-06 온도대 하나**: 온도대가 둘 이상이면 보류 `MIXED_TEMPERATURE`(WMS 가 나눠 다시 발행 — 정본). ⚠ 지금 TRF 가 온도대를 섞어 오는지는 확인 필요(§8)
  - 온도대를 아직 모르면(검품 BOX 없음) 보류 `TEMP_UNKNOWN` — 다음 생성 때 다시 본다
  - V8 R/T 가 그 온도대 차량 최대(20)를 넘으면 보류 `OVER_VEHICLE_CAPACITY`. R/T 는 cdc-outbound 의 실제 R/T 바코드 수만 쓴다 — 22:00 카운팅 전(R/T 없음)이면 검사를 미룬다(BOX→R/T 환산 규칙은 정본에 없어 만들지 않음)
  - V7(21:30 뒤 수신) — 1단계는 기록만(`lateReceipt`), 이월 판단은 배차 담당
- 상태: RECEIVED → ACCEPTED·HELD·REJECTED·EXCLUDED → ASSIGNED(운송지시서에 연결) → DEPARTED → HANDED_OVER. 보류·반려는 사유와 함께 잔여 물량 목록에 남는다(숨기지 않음).

## 4. 운송지시서(transport order) — planning
- **생성** `POST /cdcs/{cdcId}/transport-orders/generations {shipDate}`(CDC_SUPERVISOR·배차 담당, 같은 날 다시 부르면 새 TRF 만 더함): 가져오기 → 노선 × 온도대마다 운송지시서(TO-yyyyMMdd-nnn, **계획중 PLANNING**)를 정규차·기본 기사로 만들고 그 노선 MFC 의 수용 요청서를 연결. 물량 0 이면 `cancelCandidate`(결행 후보).
- 적재: Σ R/T ≤ 최대 R/T 그리고 Σ 무게 ≤ 최대 중량. 넘으면 플래그 `OVER_CAPACITY`(용차 없음 — 알림만, 계획완료 막음). 이관요청서 1건은 차량 1대(나누지 않음).
- 잔량 배분(정본 7.3)의 1·2단계(같은 노선 다른 정규차·인접 노선)는 1단계에서 **수동 이동** `POST …/{orderId}/request-moves`(요청서를 같은 온도대 다른 계획중 지시서로) — 정차 3곳 이하·온도대 같음만 검사. 자동 배분·OR-Tools 보조는 뒤로.
- **계획완료 CONFIRMED** `POST …/{orderId}/confirmations`: 차량·기사(바꿀 수 있음 — 같은 온도대·용량 이상) → fleet 점유 예약(차량·기사, `[상차 시작, 계획 복귀)`) → execution 운행 만들기 → `DISPATCHED`. 점유가 겹치면 409, execution 이 불명이면 CONFIRMING 으로 두고 같은 요청을 다시 받는다(멱등).
- 결행 `…/skips`(물량 0 또는 배차 담당 결정 — 연결된 요청서가 있으면 409), 취소 `…/cancellations`(출차 전만 — 요청서는 ACCEPTED 로 돌아감, 점유 해제, 운행 취소).
- 상태: PLANNING → CONFIRMING → DISPATCHED(→ 운행 사본 LOADED·DEPARTED·COMPLETED) / SKIPPED / CANCELLED. 바뀔 때마다 version +1.

## 5. 운행(trip) — execution
- 운행 = 지시서 사본(차량·기사·온도대·정차·정차별 요청서와 계획 도착/출발). 상태(정본 16.3 상차 이후 + 정차 상태):
  `SCHEDULED → CHECKED_IN(입차) → LOADED(상차완료) → DEPARTED(출차완료) → COMPLETED(모든 정차 인계 + DC 복귀)` / `CANCELLED`(출차 전만)
  정차: `PLANNED → ARRIVED → HANDED_OVER(MATCHED·MISMATCHED) → DEPARTED`
- 입차 `POST /trips/{tripId}/check-ins`, 상차완료 `…/loading-completions {transfers[{trfId, lpns[], rtCount}]}` — 계획(요청서)과 이관요청서별 대조: 빠진 요청서 = 미상차 예외(`NOT_LOADED`), 모두 있으면 LOADED. 스캔 박스 목록은 그대로 출발 구성이 된다.
- **출차** `…/departures {departedAt?, source REAL|SIMULATED, temperatureC?}`: LOADED 에서만 → departureId(DEP-) 발급·DEPARTED(사람이 등록한 사실 — 상차완료로 자동 출차하지 않음) → 트랜잭션 밖에서 cdc-outbound `POST /internal/departure-validations`(trfCompositions = 상차 스캔) — 결과를 운행에 적음(`outboundLinkage` SENT·ACKNOWLEDGED·UNKNOWN_RESULT·FAILED, 불명이면 재시도 작업이 같은 키로 다시). `TruckActuallyDeparted` 발행(outbox).
- 정차 도착 `…/stops/{stopId}/arrivals`, 인계 `…/stops/{stopId}/handovers {transfers[{trfId, receivedLpns[]}], receiverName?}` — 출고(상차 스캔) vs 인수 박스 비교: 같으면 MATCHED, 다르면 MISMATCHED + 예외 `QTY_MISMATCH`(차량 출발은 막지 않음). 인계 증빙 HO- 저장. 정차 출발 `…/stops/{stopId}/departures`, DC 복귀 `…/physical-ends`(모든 정차 인계 뒤 → COMPLETED, fleet 점유 해제).
- **예상 도착·지연**(정본 9.3): 이벤트마다 남은 정차 예상 = 마지막 이벤트 시각 + 남은 계획 구간 + 하차 30분. 예상 > 계획 + 30분 또는 > 07:30 이면 `delayed` 플래그(상태 아님). 최초 계획은 덮지 않는다.
- 조회(TR-2): `GET /cdcs/{cdcId}/trips?shipDate&state&page&size` — 운행 번호·노선·온도대·차량(번호판)·기사 표시 이름·계획/예상/실제 출차·정차별 도착·TRF 수·BOX·R/T 수·상태·지연. 운행 하나 `GET /trips/{tripId}`. MFC 쪽 도착 예정 `GET /mfcs/{mfcId}/inbound-trips?arrivalDate`.
- 누가 부르나: 1단계는 **직원 토큰(OPERATOR — 자기 센터)** — 기사 앱·운송사 계정은 없다(직원이 대신 입력). MFC 인계는 그 MFC 직원. 시뮬레이터는 같은 경로를 `/internal/trips/...` 로 LAN 직접(CDC-9 방식, source SIMULATED).
- TR-3 도크 배정은 뒤로(cdc-location 도크 예약 — 칠곡 R-22 층 도크 확정 뒤).

## 6. 사건(이번 범위)
- execution 발행(토픽 `tms.trip.v1`): `TruckActuallyDeparted`(있는 스키마 그대로), `TripStateChanged`, `StopArrived`, `TransportHandoverEvidenceRecorded`. planning 발행 `TransportUnplannedReported`(보류·미배차). 구독은 이번 범위에 없음(planning 은 execution 운행 상태를 조회로).
- Debezium 커넥터·토픽·pmart-config 는 PC1 몫(병합 때).

## 7. 순서(PR)
1. 이 설계 + 계약 3개(fleet 0.2.0 · planning 0.2.0 · execution 0.2.0) + cdc-outbound 계약 0.26.0(transport-feed)
2. fleet-service 구현(스캐폴드) · 시드
3. cdc-outbound transport-feed 구현
4. transport-planning 구현(노선·요청서·지시서·계획완료) · 시드
5. transport-execution 구현(운행·출차 → 출발 검증·인계·조회) — TR-2
6. DC 백오피스 회신·시뮬레이터 회신

## 8. 확인할 것(사용자·다른 세션)
- 지금 TRF 가 온도대를 섞어 오는가 — 섞이면 D-06 대로 보류된다. replenishment(또는 시뮬레이터)가 온도대별로 나눠 발행해야 함.
- 노선 시각·차량 수치는 [가정] — 실제 값이 생기면 시드만 고친다.
- 22:00 전 예상 R/T(정본 5.1 "할당 시점 예상값") — WMS 가 줄 값이 없어 지금은 비운다. 환산 규칙(BOX 몇 개 = R/T 하나)이 필요하면 정해 주세요.
- 기사·운송사 계정(로그인)은 1단계에 없음 — 기사 앱이 생길 때 사용자 유형을 정한다.
