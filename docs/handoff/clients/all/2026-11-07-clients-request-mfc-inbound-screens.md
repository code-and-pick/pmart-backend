# [pmart-backend → pmart-clients] MFC 입고 현장 화면 요청 — 입고 예정 수령·품질검사(LOT 분리)·적치, 위치 등록 (2026-11-07)

- 상태: **LAN 배포 2026-11-07**(backend `b81acec`, pmart-config `5b5f4e4`). 시험 센터 MFC-SEED-01 의 위치·시드 재고는 seed-v2 로 채우는 중.
- 계약 정본: `contracts/openapi/mfc-inbound-service.yaml`(0.3.0), `mfc-location-service.yaml`(0.2.0), `mfc-inventory-service.yaml`(0.2.0)
- 결정: ADR-0040(MFC 재고 모델 v2) 결정 5·8, MFC 업무 문서 §3 IV-02·03·13
- 커밋: `6b06db5`(재고 v2) `63affb3`(위치) `c7f5e48`(검수 LOT 분리) `55b0638`(입고 작업) `2b296de`(약속일)
- 원 요청서: 없음(백엔드 요청)

## 1. 무엇이 바뀌나
- MFC 재고가 **위치·LOT(소비기한)·품질 상태별**로 관리된다. 수령만으로는 팔 수 없고 **수령 → 품질검사 → 적치**를 거쳐야 판매 가능 재고가 된다.
- **초기 보유 승인(INITIAL_STOCK)은 이제 "입고 예정"**이다. 승인만으로 재고가 생기지 않는다. 현장이 실제로 받은 수량을 넣어야 한다.
- 입고(수령)는 **상품·수량만** 확인한다. **품질검사에서 정상/격리를 가르면서 제조 LOT 표기·소비기한별로 LOT 을 나눈다**.
- 고객 주문 재고는 소비기한이 빠른 LOT 부터 잡고, 소비기한이 배송 약속일 기준 최소 잔여일(냉장 2일·냉동 30일·상온 30일)보다 짧은 LOT 은 잡지 않는다.

## 2. 백오피스(센터 운영자)가 할 일
현장 단말(PDA)이 생기기 전까지 백오피스 화면이 현장 입력을 맡는다. 모두 **그 센터의 운영자 토큰**(`centers` 클레임)으로 호출한다. 변경 요청은 `Idempotency-Key`·`X-Source-System` 필수.

| # | 화면 | API | 내용 |
|---|---|---|---|
| B1 | **위치 등록·관리** | `POST /api/v1/locations`, `PUT /locations/{id}`(X-Expected-Version), `POST /locations/{id}/state-changes`, `GET /locations?mfcId=` | 코드(센터 안 유일, 형식 자유 — 주소 문법 미정 Q-03)·구역(RECEIVING 수령 / STORAGE 선반 / PICKING / QUARANTINE 격리 등)·온도대(AMBIENT·CHILLED·FROZEN)·동선 순서. 코드는 바꿀 수 없다(새로 등록). 상태 ACTIVE↔CLOSED·COUNT_LOCKED. **입고 전에 수령 위치 1개 이상과 온도대별 선반 위치가 있어야 한다** |
| B2 | **입고 예정 목록·수령** | `GET /api/v1/initial-stock-loads?approvalId=` → `state=EXPECTED` 인 기록, `POST /initial-stock-loads/{receiptId}/receipts` | 수령 위치(수령 구역) + SKU 별 **실제 받은 수량**(승인 수량 이하, 빠진 SKU 는 0 = 미도착). 한 번만 받는다(두 번째는 409). 접수 후 몇 초 안에 `APPLIED` 가 되고 품질검사 작업이 생긴다 |
| B3 | **품질검사** | `GET /api/v1/inbound-tasks?mfcId=&taskType=INSPECT&state=READY`, `POST /inbound-tasks/{taskId}/inspections` | 작업 하나 = 수령한 상품 한 줄. 결과를 **여러 줄로 나눠** 입력: 수량 + 판정(NORMAL/QUARANTINED) + 제조 LOT 표기 + 소비기한. 예) 정상 3개 LOT `A123` 기한 12-31, 정상 2개 LOT `B456` 기한 01-15, 격리 1개. 합계 ≤ 작업 잔량(남은 수량은 나중에 다시). 같은 LOT 표기·기한은 같은 LOT 으로 합쳐진다 |
| B4 | **적치** | `GET /inbound-tasks?mfcId=&taskType=PUTAWAY&state=READY`, `POST /inbound-tasks/{taskId}/putaways` | 품질검사에서 나뉜 정상 LOT 마다 작업 하나. 선반 위치 + 수량(나눠서 여러 번 가능). 적치되면 그때부터 판매 가능. 적치 후보는 `GET /api/v1/putaway-candidates?mfcId=&sku=&temperatureZone=` |

응답과 오류:
- B3·B4 응답 `applyStatus`: `APPLIED`(반영됨) / `REJECTED`(재고가 거절 — `rejectReason` 을 보여 주고 작업 잔량은 그대로) / 503 `UNKNOWN_RESULT`(결과 모름 — **같은 Idempotency-Key 로 그대로 다시 보내기**. 새 키로 보내면 두 번 반영 위험은 없지만 잔량 검사에 걸릴 수 있다).
- `rejectReason` 의 주요 코드: `TEMPERATURE_ZONE_MISMATCH`(선반 온도대 ≠ 상품 보관 유형), `LOCATION_ZONE_MISMATCH`(선반 구역이 아님), `LOCATION_CLOSED`, `LOCATION_NOT_REGISTERED`, `CONFIG_REQUIRED`(상품 보관 유형 미등록), `INSUFFICIENT_STOCK`.
- 수령(B2) 400: 승인에 없는 SKU, 승인 수량 초과, 합계 0. 409: 입고 예정 상태가 아님.

## 3. 고객 앱이 할 일
- **변경 없음.** 가용 재고 조회(`/availability`) 응답 모양은 그대로다.
- 결제 진입 때 재고 부족 거절은 같은 `INSUFFICIENT_STOCK` 이다. 다만 화면 가용 수량이 있어도 **소비기한 기준을 통과한 재고가 모자라면** 거절될 수 있다(`details[].reason` 이 `NO_ELIGIBLE_LOT;…` 로 시작). 문구는 지금의 재고 부족 안내와 같게 두면 된다.

## 4. MFC 점원 단말(PDA)
- 위 B2~B4 와 같은 API 를 쓰게 된다(단말 인증·토큰은 단말 단계에서 따로 안내). 지금 할 일 없음.
