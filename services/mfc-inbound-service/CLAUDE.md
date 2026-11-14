# mfc-inbound-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-inbound-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-inbound-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.inbound.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.inbound.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-inbound-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-inbound-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8306.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- 초기 보유 반입(슬라이스 4, 결정 D2, 2026-10-30): `platform.approval.v1` 의 ApprovalDecided 중 `type=INITIAL_STOCK`·`targetService=mfc-inbound-service`·APPROVED 만 InboxGuard 안에서 센터별 `initial_stock_load`(PENDING) 로 만든다(행 attributes `{mfcId, sku, qty}`, 한 행이라도 틀리면 승인 전체 REJECTED `INVALID: …`). `InitialStockPoster` 가 트랜잭션 밖에서 mfc-inventory `POST /internal/receipts`(내부 호출, Idempotency-Key = receiptId, trfId `INITIAL-<approvalId>`, departureId `INITIAL`)로 전기하고, 미전달·응답 불명은 같은 receiptId 로 5초부터 두 배(최대 5분) 재시도, 4xx 는 REJECTED. 조회: `/internal/initial-stock-loads/{receiptId}`(서비스), `/initial-stock-loads?approvalId=`(운영자). `POST /internal/initial-stock-loads` 와 그 밖의 엔드포인트는 501.
- **입고 예정·현장 작업(ADR-0040 결정 8, 2026-11-07, 브랜치 `mfc-v2`)**: V3. INITIAL_STOCK 승인은 이제 `EXPECTED`(입고 예정)만 만든다. 현장 수령 `POST /initial-stock-loads/{receiptId}/receipts`(그 센터 운영자, 수령 위치·SKU 별 수령 수량 ≤ 승인 수량, 빠진 SKU = 0) → `PENDING` → `InitialStockPoster` 가 inventory 에 수령 위치와 함께 전기 → 수령 결과(`GET /internal/receipts/{id}`)의 lotId 로 수령 줄마다 `INSPECT` 작업(`inbound_task`). 품질검사 `POST /inbound-tasks/{id}/inspections`(결과 줄 = 수량·판정·제조 LOT 표기·소비기한 → inventory INSPECT 의 LOT 분리) → 원장(`/internal/commits/{id}`)에서 나뉜 정상 LOT 마다 `PUTAWAY` 작업. 적치 `POST /inbound-tasks/{id}/putaways`(목적지 검사는 inventory). 실행은 `inbound_task_execution`(CAPTURED → APPLIED / REJECTED / UNKNOWN_RESULT, 같은 Idempotency-Key = 같은 실행 ID 로 다시, 열린 실행 수량은 잔량에서 뺌). 목록 `GET /inbound-tasks?mfcId=`.
- 품질검사 결과 줄 `manufactureDate`(ADR-0041, 계약 0.4.0) → inventory INSPECT 로 그대로. 오늘(Asia/Seoul) 이후·소비기한보다 늦으면 400.
- **로컬 공급 요청(WK-14, 2026-11-08, V4)**: `/supply-requests`(`SupplyRequestService`) — 그 센터 MFC_SUPERVISOR. 제출 = SUBMITTED + `SupplyRequestSubmitted`(mfc.inbound.v1, 버전마다), 같은 센터·SKU 미해결(SUBMITTED·ACKNOWLEDGED) 요청 있으면 409 DUPLICATE_OPEN_REQUEST, 변경(PATCH, X-Expected-Version)·철회는 SUBMITTED 일 때만. 승인·이송은 replenishment(SCM). SCM 수신 확인(SupplyRequestReceived → ACKNOWLEDGED)은 생산자가 없어 아직 소비하지 않는다. 생성은 센터 단위 `pg_advisory_xact_lock` 으로 줄 세운다 — 미해결 요청이 없을 때 동시에 온 같은 SKU 요청도 하나만(2026-11-08 검토 반영).
- **재고 이동 지시(IV-03, 2026-11-08 사용자 결정, 0.5.0, V5)**: `/move-orders`(`MoveOrderService`·`InventoryMoveClient`) — MFC 선반 간 이동은 **센터 관리자 지시**(MFC_SUPERVISOR), 실행은 그 센터 작업자(MFC_WORKER·SUPERVISOR)가 두 단계: `POST …/pickups`(카트 식별번호 CART 위치) → `POST …/drops`. 취소는 센터 관리자. 수량·칸 규칙은 mfc-inventory 이동 배정이 판정하고 거절 코드를 그대로 돌려준다. moveId = 지시 요청 키에서(같은 키 = 같은 이동), 실행 ID = moveId + 요청 키. `move_order` 는 inventory 응답 사본. 작업자 배정(capacity)과는 아직 안 묶었다(작업자 쪽은 나중 — 사용자).
- **자동 선반 보충 기록(IV-04, 0.6.0, V6)**: `ReplenishmentScheduler`(`pmart.inbound.replenishment-enabled` — LAN 켬, 주기 `replenishment-interval` 기본 1분) → mfc-inventory 점검 → 진행 중 보충 이동을 `move_order`(kind REPLENISH, created_by SYSTEM, 같은 moveId 한 번)로 남긴다. 작업자는 일반 이동처럼 카트로 실행. 사람이 보충을 만드는 API 는 없다(MFC 문서 "수동 보충 요청 불가"). **재고 변동 즉시 점검(2026-11-08)**: `inventoryEvents`(mfc.inventory.v1) 의 `InventoryExecutionApplied`·`InventoryReleased`·`InventoryHoldExpired`·`StockAdjusted` 가 오면 `ReplenishmentTrigger` 가 표시만 하고 `replenishment-trigger-delay`(기본 5초)에 한 번으로 모아 점검(실패하면 표시 되돌림). 1분 주기는 그대로(놓친 사건 대비).
- **회수품 반입(IV-10, 2026-11-08, V7, 0.7.0)**: `POST /return-receipts`(`ReturnReceiptService`, 그 센터 운영자) — `return_receipt` 기록 → mfc-inventory 수령(receiptId = 반입 ID, trfId = claimId, departureId = 회수 ID, 줄 quarantinedQty = qty·normalQty 0, 반품 위치 `toLocationCode`, LOT 표기 sourceLotRef 없으면 `RET-<claimId>`) → APPLIED 면 lotId 받아 `ReturnItemReceived`(mfc.inbound.v1, 줄 lot·locationCode, receivedBy). 결과 모름은 같은 키로 다시. `qualityEvents`(mfc.quality.v1) `ReturnInspected` 의 RESALE 분 → PUTAWAY 작업(반품 위치 → 선반, 진열 규칙은 inventory), 검수 완료 표시. 조회 `GET /return-receipts/{id}`(lines·putawayTaskIds). 환불은 claim(요청 CL-1).
- **이동 지시 담당(작업자 쪽 4단계, 0.7.1, V8)**: `move_order.worker_id·assignment_version·urgent·picked_by`. `capacityEvents`(mfc.capacity.v1) `AssignmentPublished` → 담당 투영(더 새 버전만). 담당이 있으면 담당만 집어 올림(409 NOT_ASSIGNEE), 카트에 실은 사람만 내려놓음(409 NOT_PICKER). 지시 때 `urgent`(센터 관리자 긴급 — 다음 작업 받기에서 보충과 같은 순위). 응답 urgent·assigneeId·pickedBy.
- **입고 화면 상품명·바코드(2026-11-13, 0.8.0, 클라이언트 BR-53)**: `InboundTask`·`InitialStockLoad.lines[]` 에 `productName`·`barcodes` — `SkuLabels` 가 mfc-inventory `GET /internal/skus?skus=`(`InventorySkuClient`, 100건씩)로 채운다. 표시용이라 조회 실패는 빈 값으로 두고 목록은 그대로(WARN 로그). 단건 `GET /inbound-tasks/{taskId}`(그 센터 운영자). 서비스 간 `/internal/initial-stock-loads/{id}` 는 채우지 않는다. 계약에 정한 TRF 규칙(입고 예정 구현 때 따름): RECEIVE taskId = trfId, 송장 라벨 `TRF:{trfId}:{version}`(trfId 만 비교), 줄 차이 사유는 수량 차이 우선·수량이 맞을 때만 격리 사유. `/inbound-promises/*` 는 아직 501.
- **입고 작업 담당·이동 지시 상품명(2026-11-14, 0.9.0, V9, 클라이언트 BR-52 R2·R19-1)**: `inbound_task.worker_id·assignment_version` — `capacityEvents`(mfc.capacity.v1) `AssignmentPublished` 를 이동 지시와 입고 작업 둘 다에 투영(`InboundTaskService.onCapacityEvent`, 더 새 버전만). 담당이 있으면 담당만 품질검사·적치(409 `NOT_ASSIGNEE`, 사용자 결정 2026-11-14), 담당 없으면 그 센터 운영자 누구나. 응답 `InboundTask.assigneeId·createdAt`. `MoveOrder.productName·barcodes`(SkuLabels — 조회 실패는 빈 값). 목록·단건 조회 `withLabels=false` 면 inventory 를 부르지 않는다(mfc-capacity 서비스 간 조회용 — 2 hop).
