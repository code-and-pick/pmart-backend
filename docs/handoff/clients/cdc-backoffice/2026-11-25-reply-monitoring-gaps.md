보낸 영역: platform(PC 1 — cdc)
받는 앱: cdc-backoffice
원 문서: pmart-clients `docs/handoff/cdc-backoffice/2026-11-25-backend-monitoring-gaps.md`(PR #566)
작성: pmart-backend, 2026-11-25
상태: M-3·M-6 **LAN 배포 끝**(2026-11-25 main 260f4bca), M-1·M-4 **구현(cdc-outbound 0.18.0) — LAN 배포 전**, M-2 cdc-inbound 뒤, M-5 설계 필요

# 회신: DC 백오피스 관제 빠진 조회

- **M-3 출발 준비·검증 결과(cdc-outbound 0.16.0)**
  - `GET /cdcs/{cdcId}/departures?result=&page=&size=` — 이 센터 TRF 가 든 출발 검증, 검증 시각 최신 순, result = MATCHED·PARTIALLY_MATCHED·MISMATCHED, 쪽 나눔은 transfers 와 같음. 줄 = `DepartureValidation`(내부 조회와 같음 — overallResult·transfers[] 결과·postingStatus·residualStatus·residualReason·mismatches[] 종류·LPN·사유).
  - `GET /cdcs/{cdcId}/transfers/{trfId}/readiness` — 출발 준비 단계(NOT_STARTED…LOADED·DEPARTED·CANCELLED)·검품 합격 BOX 수·수량·무게·온도대·blockers. 다른 센터 TRF 는 404.
  - 계약 바뀐 점: 출발 준비 응답 스키마 이름이 `OutboundReadiness`(전 inline — 생성 타입 이름 GetOutboundReadiness200Response → OutboundReadiness).
  - 운송(차량·기사·운행)은 TMS 서비스(transport-*·fleet)가 생긴 뒤.
- **M-6 칸별 재고(cdc-inventory 0.9.0)**: `GET /cdcs/{cdcId}/location-stocks?locationPrefix=&sku=&limit=` — 한 줄 = 칸 × SKU × LOT × 품질 상태(onHand·reserved·expiresAt), 칸 코드 → SKU → LOT 순, limit 기본 500·최대 2000, 넘으면 truncated=true. 칸 카드는 locationPrefix 로 구역·층·통로 단위로 부르세요 — 칠곡 칸 코드는 `CDC01-<구역>-<층>-<통로>-…`(예 `CDC01-A-5-01-01-1-1`)라 `CDC01-A-5-` = A 구역 5층, `CDC01-A-5-01-` = 그 통로. cdc-location 의 occupiedQty 는 그대로 비어 있어요(칸 재고는 이 조회로).
- **M-1 작업자 위치(cdc-outbound 0.18.0, Flyway V12)** — 사용자 결정 "PDA로 스캔한 위치":
  - 위치가 남는 때: 피킹 실적(`pick-results`)의 **마지막 줄 locationCode**, 그리고 새 `POST /cdcs/{cdcId}/workers/{workerId}/location-scans {locationCode, taskId?}`(작업자 본인 토큰, 204 — PDA·시뮬레이터 가상 작업자가 위치 바코드를 찍을 때). 작업자마다 마지막 한 줄(이력 없음).
  - `GET /cdcs/{cdcId}/workers?floor=` → `{asOf, workers[{workerId, locationCode, zone, floor, source(PICK_RESULT·SCAN), scannedAt, locationState, taskId, taskKind, taskState}], byFloor[{key=층, count}]}`. 위치가 있거나 지금 작업(ACCEPTED·IN_PROGRESS)을 든 작업자. locationState FRESH(10분 안)·STALE(10분 넘음 — "모름")·UNKNOWN(스캔 없음). byFloor 는 FRESH 인원만. 층·구역 = 칸 코드 셋째·둘째 구간.
- **M-4(0.18.0)**: `outbound-summary.byMfc[]` — 그날 MFC 별 `{mfcId, transferCount, approvedQty, allocatedQty, pickedQty, shortQty}`, MFC 순.
- **M-2** cdc-inbound 서비스가 생긴 뒤. **M-5 사건 기록**은 지금 cdc-outbound 가 낸 사건을 따로 쌓는 곳이 없어 표를 새로 두어야 해요 — 순서가 오면 따로.

## 덧붙임(2026-11-26 — 사용자 "응 진행해", 구현 — LAN 배포 전)
- **CS-1 실사 목록(cdc-inventory 0.12.0)**: `GET /cdcs/{cdcId}/count-sessions?state=&page=&size=` — 줄 = CountSession(결정 전 lines 없음), 만든 시각 최신 순, page 0부터·size ≤ 200·hasNext.
- **전체 작업(cdc-outbound 0.19.0)**: `GET /cdcs/{cdcId}/tasks` 의 waveId 가 **선택** — 없으면 센터 전체 작업(state·assigneeId·cursor 그대로). "최근 TRF 의 Wave" 대신 쓰세요.
- **결품 관리(0.19.0)**: `GET /cdcs/{cdcId}/shortages?state=&page=&size=` — 피킹 부족 뒤 재할당 진행·결과(followupId·trfId·mfcId·waveId·taskId·sku·lot·locationCode·shortQty·state PENDING·RELEASED·DONE·FAILED·reallocatedQty·unresolvedQty·newTaskIds·reason).
- **LPN 관리(0.19.0)**: `GET /cdcs/{cdcId}/lpns?trfId=&unitType=&page=&size=` — BOX·PLT·RT, 분류 최신 순, 봉인·검품·상차·출발 칸, BOX 는 contents[sku·lot·qty].
- 화면 다시 올림: 8447 = clients 2e9631c3(web-sync, 빌드 설정 지문 다른 직원 웹과 같게).


## 덧붙임(2026-11-26 오후 — 작업 조작, cdc-outbound 0.20.0)
사용자 "큰 순서 전부 고치고"의 3번. 자기 센터 관리자만, 모두 `Idempotency-Key`·`X-Source-System` 필수, `X-Expected-Version`(작업 version) 선택. 응답은 바뀐 뒤 `Task`(200).
- 재배정 `POST /cdcs/{cdcId}/tasks/{taskId}/reassignments {workerId?, reason?}` — 시작 전(CREATED·ACCEPTED)만. 작업자를 주면 ACCEPTED(그 작업자가 다른 작업을 들고 있으면 409 `WORKER_BUSY`), 비우면 대기열(CREATED).
- 보류 `…/holds {reason?}` — 시작 전만, 상태 **HELD**(새 TaskState 값), 담당 비움. PDA 다음 작업 받기에 안 나옴. 재개 `…/resumptions {reason?}` — HELD → CREATED.
- 우선순위 `…/priority-changes {priority, reason?}` — -100~100(기본 0), 높을수록 PDA 가 먼저 받음(같으면 기한 순). 끝난 작업은 409. `Task.priority` 새 칸.
- 이력 `GET …/adjustments` → `items[{adjustmentId, action REASSIGN·HOLD·RESUME·PRIORITY, fromState, toState, fromAssignee, toAssignee, fromPriority, toPriority, reason, actorId, adjustedAt}]` 최신 순.
- 시작한 작업(IN_PROGRESS 뒤)은 피킹 실적이 섞이므로 409 `INVALID_STATE` — 화면에서 버튼을 숨겨 주세요.

## 덧붙임(2026-11-26 — 화면 번호·상품 이름, cdc-outbound 0.21.0)
PC 5 요청(화면 번호 T·W·K, P-1 상품 이름). 규칙은 ADR-0070 덧붙임(2026-11-26).
- 번호: `trfNumber` T+6, `waveNumber` W+4(차면 5), `taskNumber` K+5, `departureNumber` S+6. 기존 기록도 모두 번호가 붙었어요(옛 기록 "번호 없음"은 거의 안 나올 거예요 — 그래도 null 대비).
  - Task: taskNumber·waveNumber·trfNumber·**trfNumbers**(trfIds 와 같은 순서)·lines[].shares[].trfNumber
  - Wave: waveNumber·**trfNumbers**(trfIds 순)
  - TransferSummary·TransferDetail: trfNumber·waveNumber
  - DepartureValidation: departureNumber·transfers[].trfNumber
- 찾기: `GET /cdcs/{cdcId}/transfers|waves|departures|tasks?number=` — `T482913`·`t482913`·`482913` 모두 같은 번호. 숫자가 아니면 빈 목록.
- 상품 이름: `TransferDetail.lines[].productName`·`Task.lines[].productName`(product 승인 스냅샷 internalName, 못 찾으면 null).
- 실사 번호 C 와 cdc-inventory 상품 이름(stock-snapshot·location-stocks·sku-handlings·count-sessions·recount-holds)은 **cdc-inventory 요청 CI-1** 로 CDC 세션이 해요(그쪽 PR 과 겹치지 않게). 그동안은 말씀하신 대로 화면이 product 에서 찾아 주세요.

## 덧붙임(2026-11-26 — Wave 조작, cdc-outbound 0.22.0)
사용자 "큰 순서 전부 고치고" 4번. 자기 센터 관리자만, `Idempotency-Key`·`X-Source-System` 필수, `X-Expected-Version`(Wave version) 선택. 응답은 바뀐 뒤 `Wave`.
- 보류 `POST /waves/{waveId}/holds {reason}`(사유 필수) — PLANNED·RELEASED·IN_PROGRESS. 상태(state)는 그대로, 응답 `hold {reason, heldBy, heldAt}`. 보류 중엔 릴리스 409 `WAVE_HELD`, PDA 다음 작업 받기에 안 나옴(이미 받은·시작한 작업은 계속). 재개 `…/resumptions {reason?}`.
- 취소 `…/cancellations {reason}` — PLANNED, 또는 RELEASED 인데 시작·실적 있는 작업이 없을 때만(있으면 409 `PICKING_STARTED`). 작업은 CANCELLED, TRF 는 Wave 에서 빠져 ALLOCATED 로 — 다시 Wave 에 넣을 수 있어요.
- 합치기 `…/merges {sourceWaveId, reason?}` — 둘 다 PLANNED·같은 온도대. sourceWaveId 의 TRF 가 이 Wave 로, 원 Wave 는 CANCELLED.
- 나누기 `…/splits {trfIds, reason?}` — PLANNED, 고른 TRF 로 새 PLANNED Wave(201, 응답은 새 Wave). 다 옮길 수는 없어요.
- 이력 `GET /waves/{waveId}/adjustments` → `items[{action HOLD·RESUME·CANCEL·MERGE_IN·MERGED·SPLIT_OUT·SPLIT_FROM, fromState, toState, otherWaveId, trfIds, reason, actorId, adjustedAt}]`.
- 릴리스 뒤(작업이 생긴 뒤)는 합치기·나누기 안 됨 — 화면에서 버튼을 숨겨 주세요.

## 덧붙임(2026-11-26 — M-5 사건 기록, cdc-outbound 0.25.0, cdc 세션 — LAN 배포 전)
- `GET /cdcs/{cdcId}/events?since=&until=&types=&limit=` → `{cdcId, since, until, truncated, items[]}`. 줄 = `{at, type, refType, refId, refNumber, trfId, waveId, taskId, sku, qty, state, action, actorId, reason}`, **최신 순**.
- type: `TRF_RECEIVED`·`WAVE_CREATED`·`WAVE_RELEASED`·`WAVE_ADJUSTED`(action 보류·재개·취소·합치기·나누기)·`TASK_STARTED`·`TASK_COMPLETED`(state DONE·PARTIAL)·`TASK_ADJUSTED`·`SHORTAGE_REPORTED`·`SHORTAGE_SETTLED`(DONE·FAILED, qty = 다시 잡은 양)·`DEPARTURE_VALIDATED`(state = 결과)·`PRE_DEPARTURE_CANCEL`·`RECALL_HOLD`. `types` 를 반복해 거른다.
- since 기본 = 지금 − 24시간, until 기본 = 지금, limit 기본 200·최대 500(넘으면 truncated=true — since/until 을 좁혀 다시). since ≥ until 이면 400.
- 새로 쌓는 사건 표가 아니라 이미 남은 기록을 모은 것이다 — 이상 알림 등급(빨강·노랑)은 기준이 정해지지 않아 없다. refNumber 는 화면 번호(T·W·K·S, 부족·취소는 비움).
- 게이트웨이 라우트 `/api/v1/cdcs/*/events` 는 PC 1 이 넣는다.
