# [pmart-backend → pmart-clients] MFC 피킹·포장 화면 요청 — 작업 배정(감독자), 작업 목록·시작·피킹·결품 보고·포장 밀봉(작업자) (2026-11-07)

- 상태: **LAN 배포 2026-11-07**(backend `8443c44` mfc-capacity·mfc-inventory·mfc-fulfillment·order, pmart-config `edd01eb`).
- 계약 정본: `contracts/openapi/mfc-fulfillment-service.yaml`(0.2.0), `mfc-capacity-service.yaml`(0.2.0), `mfc-inventory-service.yaml`(0.5.0)
- 결정: ADR-0043(슬라이스 6b), ADR-0042(위치 코드·LOT 대체), 업무 문서 MFC IV-07·WK-01~07
- 원 요청서: pmart-clients BR-05(점원 단말 Task API). 작업자 로그인(BR-02)은 아직 결정 전 — 아래처럼 운영자 계정으로 잠정 운영.

## 1. 무엇이 바뀌나
- 결제가 끝난 주문은 센터에 **피킹 작업**과 **포장 작업**으로 접수된다(6a). 이제 이 작업을 사람이 맡아 실제로 할 수 있다.
- **작업자 = 운영자 계정**(역할 `MFC_WORKER` + 그 센터 권한). **배정은 감독자**(역할 `MFC_SUPERVISOR`)가 한다. 운영자 관리 화면에서 역할을 줘야 한다.
- 피킹을 시작하면 그 주문은 **상품 준비 중**(order `PREPARING`)이 되고, 그 뒤로 고객 앱 취소는 거절된다(이미 정해진 규칙, `ORDER_NOT_CANCELLABLE`).
- 피킹은 선반에서 **포장대(포장 구역 위치, 예 `PAK-A-01`)** 로 옮기는 일이고, 포장 밀봉이 끝나면 출고가 **준비 완료**가 된다(라이더 배차·인계는 다음 단계 6c).

## 2. 감독자(백오피스)

| # | 화면 | API | 내용 |
|---|---|---|---|
| P1 | **작업 배정판** | `GET /api/v1/tasks?mfcId=&state=READY&state=ASSIGNED&state=IN_PROGRESS&state=BLOCKED`, `GET /api/v1/assignments?mfcId=`, `POST /api/v1/assignments` | 작업마다 담당 지정·변경. 본문 `{mfcId, taskId, taskType, workerId, reason}`, 헤더 `X-Expected-Version` = 지금 담당 버전(없으면 0). 같은 사람 재지정은 200(변화 없음), 버전이 낡으면 409 VERSION_CONFLICT → 다시 조회. 진행 중에 바꾸면(교대, WK-05) 앞 사람의 시작이 풀리고 새 담당이 이어서 남은 양만 한다 |
| P2 | **막힌 작업** | 같은 목록 `state=BLOCKED`, `GET /api/v1/tasks/{id}` 의 `blockReason` | 결품 보고로 막힌 작업. 처리(대체·취소) 화면은 IV-09 단계에서 — 지금은 보여 주기만 |

## 3. 작업자(점원 단말·임시로 백오피스 화면)

| # | 화면 | API | 내용 |
|---|---|---|---|
| W1 | **내 작업** | `GET /api/v1/tasks?mfcId=&assignee=me`(기본 ASSIGNED·IN_PROGRESS) | 커서 `page.nextCursor` |
| W2 | **작업 시작** | `POST /api/v1/tasks/{id}/starts` 본문 `{workerId: 내 ID, assignmentVersion, deviceId}` | 201 이면 시작. 409 코드: `ALREADY_STARTED`(누가 이미 시작), `NOT_ASSIGNEE`(내 작업 아님), `VERSION_CONFLICT`(배정이 바뀜 — 목록 새로고침), `PREDECESSOR_NOT_COMPLETED`(포장인데 피킹이 안 끝남), `INVALID_STATE`. 응답을 못 받으면 **같은 Idempotency-Key 로 다시**(기록된 판정이 온다) 또는 `GET …/starts/{키}` |
| W3 | **피킹 지시** | `GET /api/v1/tasks/{id}` | 줄마다 상품명·남은 수량과 `picks[]` = **LOT 별 남은 양·소비기한·선반 위치 후보(위치 코드·그 위치 잔량)**. 한 줄이 여러 LOT 일 수 있다. `asOf.freshness=STALE` 이면 지시를 못 불러온 것(실적은 등록 가능) |
| W4 | **집품 등록** | `POST /api/v1/tasks/{id}/executions` 본문 `{executionType: PICK, workerId, fromLocation: 선반, toLocation: 포장대, lines:[{orderLineId, sku, lotId, pickedQty}]}` | **한 번 = 한 선반**. LOT 은 지시의 LOT(다른 LOT 은 재고가 거절 `LOT_MISMATCH`). 응답 202 의 `status`: `APPLIED`(반영) / `REJECTED`(거절 — 실행 조회 사유) / `UNKNOWN_RESULT`(모름 — 같은 키로 다시). 남은 양보다 많으면 409 `QTY_EXCEEDS_REMAINING`. 모두 집으면 피킹 작업 완료, 포장 작업이 열린다 |
| W5 | **결품 보고** | 같은 API, **집품과 따로 한 줄**: `fromLocation`(발견한 선반) + `lines:[{orderLineId, sku, lotId(지시의 LOT), pickedQty: 0, shortage: {qty, reason: SHORT(못 찾음)/DAMAGED(파손)/BLOCKED, note}}]` (갱신 2026-11-07, ADR-0044) | 서버가 같은 상품의 다른 LOT 을 자동으로 다시 잡으면 작업은 그대로 진행 — 상세를 새로 불러 새 LOT 지시대로 집는다. 못 잡으면 작업이 BLOCKED(감독자 P2). 섞어 보내면 400 |
| W6 | **포장 밀봉** | 포장 작업 시작(W2) 후 `POST /api/v1/shipments/{shipmentId}/packages` 본문 `{contents:[{orderLineId, sku, qty}], bagCount, temperatureZone, weightG, containsAlcohol, packingLocation?}` | 포장 하나마다 한 번(냉장·상온 따로 등). LOT 은 비워 두면 서버가 집품분에서 고른다. 201 이면 SEALED + `qrCode`(라벨). 집품보다 많으면 409 `CONTENT_MISMATCH`, 재고 거절 422 `PACK_REJECTED`, 모름 503 `UNKNOWN_RESULT`(같은 키로 다시). 포장대가 둘 이상이면 `packingLocation` 필수(`GET /tasks/{포장작업}` 의 `packingLocations`) |
| W7 | **출고 상태** | `GET /api/v1/shipments/{id}`, `GET /api/v1/packages/{id}` | 모든 집품분이 포장되면 출고 `READY`, 포장 목록·봉투 수·무게 |
| W8 | **복귀 작업(추가 2026-11-08, ADR-0046)** | 취소된 주문의 집품·포장분을 선반으로. 작업 유형 `RETURN`(목록 `type=RETURN`) — 배정·시작은 W1·W2 와 같다. 상세 `GET /api/v1/tasks/{id}` 의 `lines[].picks[]` = LOT 별 남은 복귀량과 `locations[]`(지금 놓인 포장 위치·그 위치에서 옮길 양). 실적 `POST /api/v1/tasks/{id}/executions` 본문 `{executionType: "RETURN", workerId, fromLocation(포장 위치), toLocation(돌려놓을 선반), lines:[{orderLineId, sku, lotId, pickedQty(옮긴 양), resultQuality?: NORMAL/QUARANTINED}]}` | 한 번에 한 출발 위치. 선반 온도대가 상품 보관 유형과 다르면 재고가 거절(202 `REJECTED`, 사유). 남은 양 초과 409 `QTY_EXCEEDS_REMAINING`. 이상품(파손 등)은 `resultQuality: QUARANTINED`(격리로 선반에, 판매 제외). 다 옮기면 COMPLETED — 그때 다시 판매 가능 |

공통: 변경 요청은 `Idempotency-Key`·`X-Source-System` 필수. 토큰은 그 작업자(운영자) 토큰.

## 4. 고객 앱
- 주문 상태에 `PREPARING`(상품 준비 중)이 실제로 나온다. 이 상태에서 취소 버튼은 숨기거나, 누르면 `ORDER_NOT_CANCELLABLE` 안내.

## 5. 아직 없는 것
- 단말 바코드 대조(`TaskLine.barcode` 는 null — 상품 바코드 사본이 아직 없음), 재포장, 배차·라이더 인계(6c), 작업자 전용 로그인(BR-02).
- 2026-11-08: 피킹 중 주문이 취소되면(결품 전체 취소 포함) 진행 중이던 PICK·PACK 작업이 `CLOSED_PARTIAL`/`CANCELLED` 로 바뀌고 실적은 409 — 화면은 상세를 다시 불러 "취소됨" 안내 후 목록으로. 포장 라벨은 `INVALIDATED`(출고 CANCELLED).
