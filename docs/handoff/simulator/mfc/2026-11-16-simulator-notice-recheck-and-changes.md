# [공지] 11-09 이후 바뀐 것 + R-05·R-08·R-09·R-11 다시 점검 (2026-11-16)

- 보낸 영역: mfc(PC 3)
- 기준: 백엔드 `main` `0749e776`. 계약 mfc-capacity 0.14.0, mfc-inbound 0.9.0, mfc-inventory 0.16.0, mfc-location 0.7.1, mfc-fulfillment 0.4.11. 모두 LAN 배포됨(11-15).
- 읽은 요청서: `pmart-mfc-simulation/docs/backend-requests.md`. 이 PC 에서 받을 수 있는 판(GitHub `main` `4ea9ae9`)은 **R-12 까지**다. R-13 이후는 아직 GitHub 에 없어 이 공지에서 다루지 않는다 — R-14 는 PC 1 이 따로 회신했다(`2026-11-16-simulator-reply-token-r14.md`). 나머지는 요청서가 올라오면 따로 회신한다.
- 11-09 공지(`2026-11-09-simulator-notice-integration-decisions.md`)가 R-01~R-04·R-06·R-07·R-10·R-12 에 답했다. 이 공지는 그 뒤 바뀐 것과, 그때 다시 점검하지 않은 R-05·R-08·R-09·R-11 이다.

## 1. 11-09 이후 바뀐 것 — 시뮬레이터 동작에 영향

### 1-1. 작업자 = 인사·권한에서 (mfc-capacity, 11-13)
- MFC 작업자 명단은 그 센터 `MFC_WORKER` 권한이 붙을 때 생긴다. 권한 기간 밖이면 SUSPENDED. 센터 관리자(`MFC_SUPERVISOR`)는 작업자가 아니다.
- `workerId` = 로그인 계정의 운영자 ID(토큰 `sub`, `OP-…`). 가상 작업자는 R-14 회신의 계정(`mfcw-pp-01`~`10`)이다.

### 1-2. 업무 선택 (mfc-capacity 0.13.0, BR-52 R1·R3·R4)
- 작업자 조회(`GET /mfcs/{mfcId}/workers/{workerId}`)에 `workAreas` — 고를 수 있는 업무.
  - `INBOUND` = RECEIVE, `INSPECTION` = INSPECT, `STOCK_MOVE` = PUTAWAY·REPLENISH·MOVE·RETURN, `STOCK_COUNT` = (아직 작업 유형 없음), `PICK_PACK` = PICK·PACK.
- 다음 작업 받기 본문 `workArea` 를 주면 그 업무 안에서만 배정한다. 주지 않으면 지금처럼 모든 유효 업무.
- 작업자 상세가 목록과 같은 값(`effectiveTaskTypes`·`workAreas`·`onBreak` 등)을 준다(0.12.0 까지 빠져 있었음).

### 1-3. 다음 작업 받기가 품질검사·적치도 준다 (mfc-capacity 0.14.0, BR-52 R2)
- 후보에 mfc-inbound 입고 작업(READY·담당 없음)이 더해졌다. 응답 `taskId` 는 입고 작업 ID, `taskType` = `INSPECT`/`PUTAWAY`.
- 순서(Q-08 전 임시): 주문 PICK·PACK → 자동 보충·긴급 이동 → **품질검사 → 적치** → 센터 관리자 이동 → 취소품 복귀. 같은 유형은 먼저 만든 것부터.
- `detail` 은 한 줄(`sku`·`lotId`·`qty` = 남은 양·`fromLocation` = 수령 위치, `toLocation` null — 적치 칸은 현장에서 고른다. 후보는 mfc-location `GET /putaway-candidates`).
- 시뮬레이터 요청서의 "입고 작업 우선순위: 하차·검수 → 긴급 보충 → 적치 → 일반 보충"(시뮬레이터가 정한 값)과 **다르다** — 실서버 모드에서는 서버 순서를 따른다.

### 1-4. 담당만 실행 (mfc-inbound 0.9.0)
- 다음 작업 받기나 센터 관리자 배정으로 담당이 정해진 입고 작업은 **담당만** `…/inspections`·`…/putaways` 를 할 수 있다 — 아니면 409 `NOT_ASSIGNEE`(이동 지시와 같은 코드). `InboundTask.assigneeId`·`createdAt` 이 생겼다.
- 담당 없는 입고 작업은 지금처럼 그 센터 운영자 누구나.
- 가상 작업자는 **자기가 받은 작업만** 실행해야 한다. 실제 PDA 작업자가 받은 작업을 가상 작업자가 대신 처리하면 409 다.

### 1-5. 상품명·바코드 (mfc-inbound 0.8.0·0.9.0, mfc-inventory 0.16.0)
- `productName`(모르면 null)·`barcodes[]`(활성 바코드 전부, 선행 0 보존, 모르면 빈 배열)가 다음 응답에 있다:
  - 입고 작업 `InboundTask`(목록·단건 `GET /inbound-tasks/{taskId}`)
  - 이동 지시 `MoveOrder`
  - 실사 계수표 `CountSession.sheet[]`
- 다음 작업 받기 `detail` 에는 없다 — 위 조회를 쓴다(PICK·PACK 은 작업 상세 그대로).
- LAN 기존 상품도 11-15 에 채워졌다.
- `withLabels=false` 쿼리는 서비스 간 조회용 — 시뮬레이터는 쓰지 않는다.

### 1-6. 배차는 점원 요청만 (mfc-fulfillment 0.4.11, BR-51)
- 출하가 READY 가 돼도 서버가 배차를 **자동 요청하지 않는다**. 점원이 포장 시작 때(또는 READY 뒤) `POST /shipments/{shipmentId}/dispatch-requests`(예상 포장 분 필수)를 불러야 한다.
- 가상 작업자가 포장까지 할 때는 이 호출을 넣는다. 넣지 않으면 그 출고는 라이더가 정해지지 않은 채 남는다.

## 2. 다시 점검 — R-05·R-08·R-09·R-11

| 요청 | 지금 | 시뮬레이터가 할 것 |
|---|---|---|
| R-05 센터 재고 전체 조회 | `GET /mfcs/{mfcId}/stock?sku=` 는 **여전히 SKU 1개 필수**. 센터 전체 목록 API 는 만들지 않는다 — SIM-5(작업에 나온 SKU 만, 바뀌었을 때만 조회)로 필요가 없어졌다 | SIM-5 대로. 요청 닫음 |
| R-08 위치 정본 | 해소. `GET /locations?mfcId=` 에 `position`(`xM`·`zM`, 0.6.0)·`dimensions`·`pickSequence`. 코드 문법은 Q-03 일부 결정(`C-S1-03-2-04`, 그 밖 `RCV-C-01` 등 — ADR-0042). 시험 센터 칸은 11-09 공지(`…-layout-bins.md`)대로. 남은 것은 Q-03 의 배치·용량·혼재·통행 — 현장 결정이라 백엔드 할 일 아님 | 평면을 위치 조회로 그린다(이미 계획) |
| R-09 상품·보관 기준정보 | 일부 해소. 중량·보관 유형: product-service `GET /skus/{sku}`(`weightG`, `logistics.storageType`, 운영자). 상품명·바코드: §1-5 응답. 그 센터 취급 여부: mfc-inventory `GET /mfcs/{mfcId}/sku-handlings`. 보관 유형·무게가 없는 SKU 를 골라 주는 목록 API 는 없다 | 쓰는 SKU 만 `GET /skus/{sku}` 로 확인(SKU 전체 반복 조회 금지 — SIM-5) |
| R-11 칸 최소·목표량 | API 는 있다 — mfc-inventory `GET`·`PUT /mfcs/{mfcId}/replenishment-policies`(칸·SKU 별 `minQty`·`targetQty`·`unitQty`, IV-04). **값은 Q-07 미결**이라 센터가 넣은 것만 있다. 기준이 없는 칸은 자동 보충이 돌지 않는다(`CONFIG_REQUIRED` — 다른 값으로 대신하지 않음) | 실서버 모드에서는 이 목록을 읽어 쓰고, 시뮬레이터 산식(칸 한도의 30%)은 MOCK 에서만 |

## 3. 그 밖의 항목

- **R-07 가상 라이더**: 11-09 에는 "배송 서비스 없음"(SIM-7)이었지만, 지금은 delivery-dispatch·rider 서비스가 LAN 에 있고 라이더 쪽은 **라이더 시뮬레이터**(`pmart-rider-simulation`, delivery 영역 PC 5 — `docs/handoff/simulator/rider/`)가 맡는다. MFC 시뮬레이터의 인계를 라이더 시뮬레이터와 어떻게 이을지는 아직 정하지 않았다 — 정해지면 SIM-7 을 고쳐 공지한다. 그때까지 SIM-7(인계는 MOCK) 그대로.
- **R-10 정기 입고**: 입고 예정 `/inbound-promises/*` 는 아직 501(CDC → MFC 이송이 생긴 뒤). 수령 뒤 INSPECT·PUTAWAY 작업이 생기는 규칙(taskId = trfId)은 정해 뒀다.
- **R-12**: 변화 없음(OR-Tools 는 mfc-capacity 안, Chronos-2 미적용).

## 4. 요청서 정리 부탁

시뮬레이터 요청서의 "현재 상태" 표는 11-07 기준이라 R-01·R-04·R-06 등이 501 로 남아 있다. 11-09 공지와 이 공지를 반영해 고쳐 주면, 다음 회신부터 그 판을 기준으로 삼는다.
