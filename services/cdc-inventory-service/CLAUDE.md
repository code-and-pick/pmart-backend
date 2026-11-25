# cdc-inventory-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-inventory-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-inventory-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.inventory.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.cdc.inventory.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:cdc-inventory-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:cdc-inventory-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8601.

## 상태
- 스캐폴딩(2026-10-30, tools/scaffold — 86xx 대역 첫 서비스).
- 상품 등록 슬라이스(2026-10-30): `commerce.product.v1` 의 SkuIssued·ProductVersionPublished → `sku_projection`(물류 규격 units_per_box·boxes_per_pallet·ti·hi 포함, CDC-F-002; 오래된 product_version 무시). 센터 취급 `center_sku_handling` — **레코드 없음 = OFF**, 자기 센터 운영자가 `/cdcs/{cdcId}/sku-handlings`(단건·일괄)로 켠다. 규칙·이력은 mfc-inventory 와 같다. 취급 변경 이벤트는 발행하지 않는다.
- 장부(0.2.0, 2026-11-25, Flyway V3): `stock_lot`(소비기한·식별·최초 인수시각)·`stock_balance`(위치·품질별 현물/예약, 예약은 RELEASED 에만)·`safety_stock`(행 없음 = 미설정 → CONFIG_REQUIRED, 0 으로 대신하지 않음)·`allocation*`·`transit*`·`inventory_commit*`.
  - 초기 보유: 센터·SKU·LOT 당 1회(INITIAL_BALANCE_EXISTS), SKU 투영 필요, 취급 ON 불필요, `lotIdentification` 없으면 UNVERIFIED(적격 아님).
  - 적격 E = RELEASED·VERIFIED·잔여일수 D ≥ max(1, minRemainingDays)(소비기한 당일 차단, Asia/Seoul). 가용·할당 앞 검사 순서: SKU 투영 → 취급 ON → 안전량 설정.
  - 할당: SKU 별 한도 max(0, E'−R'−S) 안에서 FEFO(기한 → 최초 인수 → LOT → 피킹 선반 먼저(0.5.0) → 위치). 부분 PARTIAL, 0 이면 FAILED. 해제는 늦은 LOT 부터. 같은 TRF 다른 버전이 살아 있으면 ALLOCATION_EXISTS.
  - TOUT: TRF 당 1회, 현물 −q·예약 −q·Transit +q. 같은 departureId·같은 줄 묶음 재전송은 기존 commitId. 남은 할당은 호출자가 RESIDUAL_CLOSED 로 해제.
  - 발행(토픽 `cdc.inventory.v1`, outbox): `AllocationResult`, `TransferDeparted`(departureSource SIMULATED 면 봉투 source 도 SIMULATED). TransferDeparted 줄 = 출발 줄 하나: `lpn` 은 LPN 이 하나일 때만(여럿이면 LPN 별 수량을 모르므로 `lpns` 목록만 — mfc-inbound 에는 lpn 없음으로 보인다), `expiryDate` 는 LOT 소비기한.
  - 같은 TRF 의 할당·출발은 권고 잠금(`pg_advisory_xact_lock`)으로 줄 세운다 — 다른 trfVersion 첫 할당 동시 요청도 하나만 들어간다.
  - 아직 계산하지 않음: 품목군 최소 잔여일수(제조일 입력 없음), 승인량 초과 검사(replenishment 없음).
- 내부 이동(0.3.0, 2026-11-25, Flyway V4): `/internal/move-postings` — 같은 SKU·LOT·품질로 위치만 바꾼다(총량·LOT 최초 인수시각 그대로). 줄에 `allocationId` 가 있으면 그 할당이 원위치에 잡은 예약을 함께 옮긴다(`allocation_lot.moved_out/moved_in`, 남은 예약 = 할당 + 옮겨 옴 − 옮겨 감 − 해제 − 출발) — 팔레트 랙 → 피킹 선반 보충(칠곡 CDC-7). 없으면 예약 안 된 현물만. 예약은 RELEASED 에만. 위치 상태는 cdc-location 이 본다(여기서는 안 봄). 원결과 MOVE.
- 재고 스냅샷(0.4.0): `/internal/snapshots?cdcId&sku?` — 지금 장부 그대로(snapshotId 는 이번 조회 식별, 보관 안 함 — 지난 id 는 404). sku 를 비우면 현물 행이 있는 SKU 전부. 취급 OFF·안전량 미설정도 거절하지 않고 handlingActive·safety.state NOT_SET·available null 로 보인다. `lots[].locations[]` = 위치·품질별 현물·예약(시뮬레이터가 칸별 재고를 그린다).
- 피킹 선반 먼저(0.5.0, 2026-11-25): 할당 FEFO 순서 기한 → 최초 인수 → LOT → **PICK_FACE 먼저** → 위치. 위치 유형은 cdc-location `POST /internal/location-lookups`(`LocationClient`, 주소 `pmart.inventory.cdc-location-base-url`, 연결 0.5초·응답 1.5초, 호출마다 새 Idempotency-Key — 없으면 공통 헤더 검사가 400, LAN #462 배포에서 확인)를 트랜잭션·행 잠금 **밖**에서 미리 읽는다. 조회가 안 되면 경고 로그만 남기고 위치 코드 순(랙 01~ 이 선반 61~ 보다 앞)으로 잡는다 — 동선 선호라 할당을 막지 않는다.
- 피킹 부족 재할당(0.7.0, Flyway V5 `recount_hold`, WMS CDC-F-018 — 사용자 2026-11-25 "부족 칸은 새 할당에서 뺀다"):
  - 해제 줄 `locationCode`(그 위치만)·`recountHold`(PICK_SHORTAGE 에서 줄마다 LOT·위치 필수 → 그 SKU·LOT·위치 재계수 대기, 이미 대기면 부족 수만 더함). 대기 칸은 새 할당·추가 할당 후보와 한도 E'·R' 에서 빠진다(현물·걸린 예약은 그대로, 손실 차감 없음).
  - 추가 할당 `POST /internal/allocations/{id}/additions`(PICK_SHORTAGE_REALLOCATION): 같은 할당에 FEFO 로 더 잡기(부분·0 허용, 같은 LOT·위치 행은 allocated 에 더함, RELEASED 였으면 PARTIAL), 원결과 ALLOCATION_ADD.
  - 운영자 `GET /cdcs/{cdcId}/recount-holds`·`POST …/{holdId}/releases`(다시 후보로). 센 결과 반영(조정)은 아직 — 4번 조정 때 잇는다.
- 이동 종류 PICK_MOVE(0.7.1): cdc-outbound 가 피킹 재고를 예약과 함께 출고대기 자리(91)로 옮겨 적는다(move-postings 그대로 — 총현물·예약 수 그대로).
- 위치 실사·CNT 조정(0.8.0, Flyway V6 `count_session`·`count_location`·`count_line`, WMS CDC-F-029·030 — 사용자 2026-11-25 "MFC 실사와 같게", 설계 `docs/cdc/design/2026-11-25-count-adjustment.md`):
  - `/cdcs/{cdcId}/count-sessions`(`CountService`): 시작(1~200칸, 출고대기 91 은 400 STAGING_NOT_COUNTED, 품질 보류 현물 칸 409 QUALITY_STOCK_PRESENT, 겹침 409 COUNT_IN_PROGRESS) — 정상 현물을 장부 기준(round 0)으로 고정, 칸은 열린 동안 할당·추가 할당 후보에서 빠짐(`AllocationService.pool`).
  - 계수표에 장부 수량 없음 → 첫 계수(없는 줄 0, 처음 보는 LOT 400 LOT_UNKNOWN) 같으면 MATCHED, 다르면 RECOUNT_REQUIRED → 다른 사람 재계수(409 SAME_COUNTER) 같으면 MATCHED, 다르면 PENDING_APPROVAL.
  - 결정: 시작·계수자 아님(422 SELF_APPROVAL). APPROVE = 장부 바뀜 409 BOOK_CHANGED → 승인 관문(`ApprovalGate` — 트랜잭션 밖) → CNT 원결과(차이만 ON_HAND) → 잠금·재계수 대기 해소 → `InventoryAdjustmentPosted`(approvalRef 없으면 `CENTER:<세션>`). 예약보다 적어지면(0.10.0) 모자란 만큼 그 칸에 늦게 잡힌 할당부터 예약을 풀고(`AllocationStore.holdersAt`, 같은 CNT 원결과에 RESERVED −) 할당마다 `AllocationShortfall`(COUNT_LOSS) — cdc-outbound 가 받는다. 풀 할당이 없으면 409 COUNT_BELOW_RESERVED(조사). REJECT = 잠금만 풂.
  - 승인 관문 `pmart.inventory.approval-ref.mode`: VERIFY(`hq-loss-krw`·`hq-sku-qty` — 사용자 "MFC 와 같게" 100000·20, pricing `/internal/prices`·approval `/internal/approval-ref-uses` 종류 CDC_STOCK_ADJUSTMENT — AP-4 대기, 주소 `pricing-base-url`·`approval-base-url`) / RECORD_ONLY(번호 필수·확인 없음) / 그 밖 CONFIG_REQUIRED.
- MFC 수령·운송 중 재고 대사(0.11.0, Flyway V7 `transit_receipt`·`transit_line.pending_qty`, WMS CDC-F-024 — 설계 `2026-11-25-receipt-transit.md`, 사용자: 미판정도 받은 수량·사건 + 복구용 API): `MfcReceiptPosted` 구독(`infrastructure/MfcEventConsumers`, 바인딩 `mfcEvents-in-0` → mfc.inventory.v1 — 발행은 PC 3 IV-8)과 복구용 `/internal/receipt-acks`(`ReceiptService`).
  - receiptId 한 번(같은 본문 = 그대로, 다른 본문 409 RECEIPT_CONFLICT). 줄별 SKU·LOT 출발 줄에 앞 줄부터 정상·격리·미판정 + Transit −r(원결과 TIN_ACK), 넘거나 없는 줄은 그 줄만 보류(conflicts). t = 0 → TRANSIT_RECONCILED, 남으면 RECEIPT_POSTED_AWAITING_ACK(자동 손실 없음) → `TransitReconciled`.
  - TOUT 보다 먼저 온 사건은 PENDING_DEPARTURE → TOUT 트랜잭션 끝에서 `applyPending`. API 는 TOUT 이 없으면 404 DEPARTURE_NOT_POSTED. 정정(correctionId)은 아직 501.
- 아직 501: 수령 정정·품질·손실·조정(`/internal/adjustments` — 실사 밖 ADJ)·일일 대사. 게이트웨이·LAN 배치·Helm 미등록.
- **DC 백오피스 재고 현황(2026-11-25, 0.6.0)**: `GET /cdcs/{cdcId}/stock-snapshot?sku=&withPlaces=`(`AvailabilityController.getCenterStockSnapshot`) — 내부 `/internal/snapshots` 와 같은 `InventoryQueries.snapshot`, 자기 센터 운영자만(`Operator.requireCenter`), withPlaces 기본 false(LOT·위치 빼고). 시험 `StockLedgerFlowIT` 2-2.
- **칸별 재고(2026-11-25, 0.9.0 — DC 백오피스 M-6)**: `GET /cdcs/{cdcId}/location-stocks?locationPrefix=&sku=&limit=`(`AvailabilityController.listCenterLocationStocks`) — `InventoryQueries.snapshot` 을 칸 × SKU × LOT × 품질 상태 줄로 펼침, 칸 → SKU → LOT 순, limit 기본 500·최대 2000(넘으면 truncated). cdc-location occupiedQty 는 그대로 null. 시험 `StockLedgerFlowIT` 2-3.
