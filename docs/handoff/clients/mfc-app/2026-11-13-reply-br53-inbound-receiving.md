# 회신: 입고 수령 화면 값 — BR-53 (R8~R11)

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-13
- 답하는 것: pmart-clients `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` 의 R8~R11(`backend-requests.md` BR-53).
- 계약: `mfc-inbound-service.yaml` 0.8.0, `mfc-inventory-service.yaml` 0.15.0(서비스 간), `mfc-capacity-service.yaml` 0.13.1(설명만). PR 병합 뒤 main 에서 `gen:api`. LAN 반영은 PC 1 배포 뒤.

## 0. 먼저 알아 둘 것 — 송장(TRF) 입고 예정은 서버에 아직 없다

`/inbound-promises/*`(입고 예정 목록·상세·도착 등록·수령 등록)는 **계약만 있고 서버는 501** 이다. CDC → MFC 이송(TOUT·TRF)을 만드는 쪽(cdc-outbound·cdc-inventory·tms)이 아직 없어서 입고 예정이 생기지 않는다.
지금 실서버에서 PDA 가 할 수 있는 입고 일은 아래뿐이다.

| 일 | API | 상태 |
|---|---|---|
| 초기 보유 반입 수령 | `GET /initial-stock-loads?approvalId=`, `POST /initial-stock-loads/{receiptId}/receipts` | 동작 |
| 품질검사 | `GET /inbound-tasks?mfcId=&taskType=INSPECT`, `POST /inbound-tasks/{taskId}/inspections` | 동작 |
| 적치 | `GET /inbound-tasks?mfcId=&taskType=PUTAWAY`, `POST /inbound-tasks/{taskId}/putaways` | 동작 |
| 송장 입고(도착·수령) | `/inbound-promises/*` | **501** — 목으로만 |

그래서 R9~R11 은 **규칙을 계약에 정해 두고**(구현 때 그대로 따름), R8 은 지금 동작하는 화면부터 실제 값을 넣었다.

## 1. R8 상품명·바코드 — 수용(지금 동작하는 화면부터)

- 새 필드(모두 선택, 없을 수 있음):
  - `InboundTask.productName`·`InboundTask.barcodes` — 품질검사·적치 작업 목록, 실행 응답의 `task`
  - `InitialStockLoad.lines[].productName`·`lines[].barcodes` — 초기 보유 반입 목록
  - `InboundPromise.lines[].productName`·`lines[].barcodes` — 입고 예정 구현 때 채움(지금은 501)
- **새 API `GET /inbound-tasks/{taskId}`** — 입고 작업 단건(그 센터 운영자만, 다른 센터 403, 없으면 404).
- `productName` 은 **상품 관리용 명칭**(product-service `internalName`)이다. 고객 앱의 상품명(product-content)과 다를 수 있다. 피킹 작업의 `productName` 은 주문 때 사본이라 출처가 다르다.
- `barcodes` 는 그 SKU 의 활성 바코드 전부(선행 0 보존). 스캔 값이 이 중 하나와 같으면 맞는 상품이다.
- 값이 비는 경우 — 화면은 SKU 로 대신 보이고 직접 입력을 허용해 주세요:
  - 이번 변경 전에 등록된 상품은 **다음 상품 버전 발행까지 null·빈 배열**(LAN 기존 상품 다수). PC 1 에 재발행 여부 확인 요청함.
  - 서버끼리 조회가 실패하면 목록은 그대로 오고 상품명·바코드만 빈다(작업 조회를 막지 않는다).

## 2. R9 RECEIVE 배정 ↔ 입고 예정 — taskId = trfId

- **RECEIVE 작업의 `taskId` = 입고 예정 `trfId`** 로 정했다(앱 가정과 같음). `detail` 에 따로 넣지 않는다.
- 다만 **다음 작업 받기는 지금 RECEIVE 를 주지 않는다** — 입고 예정이 서버에 없어서. 입고 예정이 생기면 그때 다음 작업 받기 후보에 넣는다(그때 알림). 센터 관리자가 수동 배정하면 taskId 에 trfId 를 넣는다.
- 초기 보유 반입은 RECEIVE 배정이 아니다 — 목록(`approvalId`)에서 `receiptId` 로 고른다.

## 3. R10 송장 바코드 — `TRF:{trfId}:{version}`

- 라벨 스캔 형식 `{유형}:{id}:{버전}`(포장 `PKG:` 와 같은 규칙)을 따라 **`TRF:{trfId}:{version}`**.
- 맞는 송장인지는 **trfId 만** 비교하세요. 버전은 라벨 인쇄 때 값이라 지금 입고 예정 `version` 과 다를 수 있다.
- 라벨 인쇄는 출발 센터(CDC) 쪽 일이라 백엔드 요청으로 남겼다(`docs/architecture/service-requests/cdc-outbound-service.md` CO-1). 형식이 바뀌면 다시 알린다.

## 4. R11 차이 사유가 하나일 때 — 수량 차이 우선

- `RecordReceiptRequest.lines[].discrepancyReason` 은 줄당 하나:
  1. 수량 차이(`NOT_RECEIVED`·`SHORT`·`OVER`)가 있으면 그것.
  2. 수량이 맞고 격리(`quarantinedQty > 0`)가 있을 때만 `DAMAGED`·`TEMPERATURE`.
- 격리 수량은 사유와 상관없이 `quarantinedQty` 로 따로 남는다(인수 = 정상 + 격리 + 미판정). 앱 가정과 같다.

## 5. 앱에서 바꿀 것

- 품질검사·적치·초기 반입 화면: 실서버에서도 `productName`·`barcodes` 를 쓰고, 비면 SKU·직접 입력으로.
- 입고 작업 상세가 필요하면 `GET /inbound-tasks/{taskId}`.
- 송장 입고 화면은 계속 목. 스캔 해석은 `TRF:` 접두어 + trfId 비교.
- R9 가정(taskId = trfId)·R11 규칙은 그대로.

## 6. 남은 것

- 입고 예정(`/inbound-promises/*`) 구현 — CDC 이송 쪽이 생겨야 한다(2단계).
- BR-52 R2·R5~R7·R12~R20 은 따로 회신.
