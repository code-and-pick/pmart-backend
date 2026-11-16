# mfc-fulfillment-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-fulfillment-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-fulfillment-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.fulfillment.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.fulfillment.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-fulfillment-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-fulfillment-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8302.

## 주문 단위 작업 중지 (2026-10-31, S3 a)
- `POST /internal/stop-requests`·`GET /internal/stop-requests/{id}`(내부 호출): 작업 지시가 아직 없어 요청 수량 전부 STOPPED(`notStartedLines`, packages 빈 배열)·`order_work_stop`(V2) 기록·`WorkStopResult`(mfc.fulfillment.v1) 한 트랜잭션. 같은 stopRequestId 는 기존 판정, 다른 주문·수량이면 409 IDEMPOTENCY_CONFLICT.
- 2026-11-07: 접수된 작업 지시가 있으면 중지 수량만큼 작업 행을 취소(`task_line.cancelled_qty`), 잔량 0 인 작업·출고는 CANCELLED. 6a 는 피킹 시작이 없어 판정은 여전히 전부 STOPPED. 포장별 판정(PENDING·fence)은 6b 이후.

## 작업 접수 (2026-11-07, 슬라이스 6a — order-checkout-saga.md §2.0)
- `POST /internal/work-orders`(ConfirmOrder, orchestration): `WorkOrderService`·규칙 `domain/WorkRules`·표 V3(`work_order` 주문당 하나, `work_order_version` 요청 버전마다, `shipment`·`shipment_line`(수량·상품명 사본은 여기 한 곳), `task`·`task_line`(실적만, 잔량은 계산)).
- 결정은 201 `WorkOrderDecision`(거절도 결과 — 같은 키 재생). 같은 orderId·workVersion 은 기존 결정, 접수된 주문에 다른 버전·마지막보다 작은 버전은 409 INVALID_STATE.
- 순서: 주문 advisory lock(중지와 한 줄) → 이미 중지된 수량 빼기(전부면 거절 ALREADY_STOPPED) → mfc-inventory `GET /internal/reservations/{id}`(트랜잭션 밖, 조회 불가면 503·기록 없음)로 원래 예약이 CONFIRMED·SKU별 확정 조각 합 ≥ 작업 수량인지(아니면 INVENTORY_MISMATCH, retryable) → 접수: 출고마다 `<shipmentId>-PICK`(READY) + `-PACK`(WAITING). 결정과 `WorkAccepted`/`WorkRejected`(mfc.fulfillment.v1, 집계 키 orderId) 한 트랜잭션.
- 예약 주문 PICK: 슬롯 시작 − `pmart.fulfillment.pick-lead-time."[<센터>]"`(LAN 90분)까지 WAITING. 센터 설정 없으면 WAITING·holdReason CONFIG_REQUIRED(0·즉시 대체 금지). `sweepReady`(`pmart.fulfillment.ready-sweep-interval`, 기본 30초)가 시각이 된 PICK 을 READY 로, 설정이 새로 들어온 대기에 시각을 넣는다(조건부 갱신).
- OPERATION_RESTRICTED·CAPACITY_EXCEEDED·LOT_INELIGIBLE 은 계약 값만 — 원천(운영 제한·LOT)이 없어 내지 않는다. 작업자 배정·피킹 시작(PreparationStarted)·DISPATCH 는 6b·6c.
- 조회 `GET /internal/work-orders/{fulfillmentId}`·`GET /internal/work-orders?orderId=`(마지막 버전 기준, 작업별 계획·완료·취소·잔량·readyAt·holdReason).

## 점원 단말 작업·포장 (2026-11-07, 슬라이스 6b — ADR-0043)
- V4: `task` 담당 투영(worker_id·assignment_version)·시작 점유(start_request_id·started_by), `task_start`(판정 기록), `task_execution`(PICK 실적 RECORDED → APPLIED/REJECTED/UNKNOWN_RESULT), `package`(OPEN → SEALED/VOIDED), `work_order.preparation_started_at`, `shipment.ready_at`.
- 작업자 = 그 센터 운영자 + `MFC_WORKER`(`TaskService.requireWorker`). 조회는 그 센터 운영자·서비스.
- 담당: `AssignmentPublished`(mfc.capacity.v1, 바인딩 `assignmentEvents-in-0`) → `TaskService.applyAssignment`(더 새 버전만, READY → ASSIGNED, 진행 중 재배정은 ASSIGNED·시작 지움). `sweepReady` 가 담당 있는 READY 도 ASSIGNED 로.
- 시작 `POST /tasks/{id}/starts`: capacity `GET /assignments?taskId=`(작업자 토큰 전달, `CapacityClient`, 실패 503·기록 없음) → 작업 행 잠금 → `TaskRules.startRejection` → 기록. 첫 PICK 시작에 `PreparationStarted`. 거절은 409 + 코드(ALREADY_STARTED·NOT_ASSIGNEE·PREDECESSOR_NOT_COMPLETED·INVALID_STATE·VERSION_CONFLICT).
- 상세 `GET /tasks/{id}`: PICK 은 inventory pick-plan 으로 줄마다 LOT 별 지시(`picks[]`), 조회 실패면 빈 지시·asOf STALE.
- 실적 `POST /tasks/{id}/executions`(PICK 만, 한 선반): 접수 → inventory PICK(Idempotency-Key = 실행 ID) → 결과. 같은 키는 같은 실행(열려 있으면 다시 보냄). 결품 보고는 집품과 섞지 않은 한 줄(pickedQty 0 + shortage, lotId·fromLocation 필수) → inventory `POST /internal/shortage-reports`(reportId = 실행 ID, ADR-0044) → 재확보 다 되면 작업 계속, 못 잡은 양이 있으면 BLOCKED + `LineShortageReported(shortQty = 못 잡은 양)`. 다 집으면 COMPLETED → 같은 출고 PACK `releasePack`.
- 포장 `POST /shipments/{id}/packages`: PACK 을 시작한 작업자만. LOT 은 `TaskRules.allocatePacking`(집품−포장 조각, 소비기한 순), 남은 포장량 = PICK 완료 − PACK 완료 − 열린 포장. inventory PACK(실행 ID = 포장 ID) → SEALED·QR, 거절 VOIDED(422 PACK_REJECTED), 모름 OPEN(503 UNKNOWN_RESULT). 다 포장하면 PACK COMPLETED·출고 READY·`PackingFinished`·`ShipmentReady`. `GET /shipments/{id}`·`GET /packages/{id}`.
- 중지: 피킹이 시작된 주문이면 `PENDING`(작업 유지, 사건 없음). → 2026-11-08 아래 IV-08 로 대체(부분 중지만 PENDING).

## 집품 뒤 취소 (2026-11-08, IV-08 — ADR-0046, V5)
- `WorkStopService`: 피킹 시작된 주문의 FULL 중지 → PENDING 기록 + (주문 잠금·작업 행 잠금 아래) 진행 중 실적·포장(RECORDED·UNKNOWN_RESULT·OPEN)이 없으면 준비: 남은 PICK·PACK 행 취소, 작업 CLOSED_PARTIAL(완료량 있음)/CANCELLED, 밀봉 포장 INVALIDATED, 출고 CANCELLED, 복귀 대상(`domain/CancelReturnRules` — 반영 PICK 도착 위치 − 같은 위치 밀봉 포장 = PICKED, 포장 = PACKED) → `<출고>-RETURN`(WAITING, `task.returns`), `order_work_stop.cancel_return·package_verdicts`.
- 커밋 뒤 `finish`: inventory `POST /internal/reservations/{id}/cancel-returns`(키 = stopRequestId) → APPLIED 면 RESOLVED·`resolved_at`·RETURN READY/ASSIGNED·`WorkStopResult`(perPackage 는 포장만). 모름·미전달·준비 대기 → `sweepPending`(`pmart.fulfillment.stop-sweep-interval`, 기본 PT30S). **실패**(재고 거절, 또는 이 서비스 기록 불일치 `RECORD_MISMATCH` — 계산을 먼저 끝내고 그 뒤에만 바꾸므로 부분 반영 없음) → `last_error`·ERROR 로그 한 번, PENDING 유지, sweep 에서 빠짐 → 원인 고친 뒤 `POST /internal/stop-requests/{id}/retries`(사유 지우고 다시 끝까지, 0.3.1). 판정 응답 `lastError`.
- 판정 응답 `packages[]`: 밀봉 포장마다(PACKED) + 출고의 포장 전 단위(packageId 없음, NOT_PICKED·PICKED). notStartedLines 는 빈 배열.
- RETURN 실적: `executionType RETURN`(작업 유형과 같아야, 아니면 422 UNSUPPORTED_EXECUTION) — 줄마다 lotId·pickedQty>0, (주문 줄·SKU·LOT·출발 위치) 남은 복귀량 이하(아니면 409 QTY_EXCEEDS_REMAINING) → inventory MOVE(resultQuality 전달) → APPLIED 면 완료량, 모두 0 이면 COMPLETED. 상세 `picks[]` 는 이 서비스 기록(복귀 잔량·포장 위치).

## 라이더 인계 (2026-11-08, 슬라이스 6c — ADR-0047, V6, 계약 0.4.0)
- 정본 `docs/business/flows/package-handover-contract.md`. 규칙 `domain/HandoverRules`, 실행 `application/HandoverService`, 표 `delivery_assignment`(배송 배정 투영)·`package` fence 열·`package_handover`(인계 사실, 포장당 하나).
- 배정: `delivery.delivery.v1`(바인딩 `deliveryEvents-in-0`, `DeliveryEventConsumers`) — DeliveryCreated(shipmentId)·DeliveryAssigned·DeliveryAssignmentChanged·DeliveryCancelledBeforePickup, 더 새 버전만, 바뀌면 확정 요청 전 fence 끝.
- 허가 `POST /packages/{id}/handover-authorizations`(RIDER 토큰, 주체 = riderId): 주문 잠금 → 포장 잠금 → `HandoverRules.authorize`. 유효시간 `pmart.fulfillment.handover-fence-ttl."[센터]"` → 없으면 `default`(사용자 결정 D-2: 3분, 공통 설정) → 둘 다 없으면 422 CONFIG_REQUIRED. 같은 라이더·배송 재스캔은 같은 허가.
- 확정 `POST /packages/{id}/handover-commits`: 주문 잠금 아래 fence 확인·인계 사실 REQUESTED → 밖에서 inventory `/internal/package-handovers`(키 = handoverId) → APPLIED 면 포장 HANDED_OVER·출고 갱신·`PackageHandedOver`(집계 Package). 모름 202 UNKNOWN_RESULT(sweep `pmart.fulfillment.handover-sweep-interval` 기본 PT15S), 거절 202 REJECTED(fence 유지·대사).
- 재고 거절(REJECTED)은 자동으로 다시 안 감 — 운영자가 실물 확인·재고 정정 뒤 `POST /internal/package-handovers/{handoverId}/retries`(0.4.1, 같은 인계 ID, REJECTED 아니면 지금 결과). 확정 응답 `rejectReason`.
- 판정 `GET /packages/{id}/handover-resolution`. 중지(`WorkStopService.prepare`)는 인계된 포장이 있으면 거절(RESOLVED + HANDED_OVER), 살아 있는 fence 면 PENDING(HANDOVER_IN_PROGRESS), 만료 fence 는 닫고 진행. `WorkStopStore.orderStopped` 가 허가를 막는다.

## 상태
작업 접수·조회·접수 뒤 중지(6a), 점원 단말 작업·피킹·포장(6b), 집품 뒤 전체 중지·복귀 작업(IV-08), 라이더 인계(6c), 재포장(WK-07). DISPATCH 작업은 501(배차 요청 시점 결정 대기 — package-handover-decisions D-5). 피킹 뒤 부분 중지는 PENDING.
- 재포장(2026-11-08, V7, 0.4.2): `POST /packages/{id}/repackings`(`RepackService`·`domain/RepackRules`) — 그 센터 작업자, 주문 잠금 아래 SEALED·fence 없음·결과 대기 인계 없음·중지 없음만. 새 포장(새 ID·packageVersion+1·새 QR, 내용·봉투·위치·PACK commit 그대로, `replaces_package_id`) + 이전 INVALIDATED(reject_reason `<사유>→<새 ID>`) + `PackageInvalidated`. 재고·예약 변화 없음. 내용 변경은 422 CONTENT_MISMATCH(아직 없음).
- 검토 반영(2026-11-08, `docs/business/flows/mfc-defaults-status.md`): 재포장은 인계 허가가 열려 있으면(만료됐어도 실제로 안 넘겼다는 확인 전 — D-1 미결) 409 `FENCE_EXPIRED_UNCONFIRMED`. 작업 시작은 capacity 배정 조회의 `workerEligibility`(배정 뒤 정지·퇴사·업무 변경)가 있으면 그 코드로 거절.
- 위치 배정(2026-11-08, 0.4.3): PICK 작업이 STARTED 로 기록되면(트랜잭션 뒤) mfc-inventory `POST /internal/reservations/{id}/location-allocations`(키 `taskId:requestId`). 지시는 참고라 실패해도 시작은 유지(경고 로그) — 집품 판정은 inventory(배정 밖 칸은 다른 주문 몫을 빼고 남을 때만, 아니면 409 LOCATION_ALLOCATED_TO_OTHER → 실적 REJECTED). 작업 상세 `picks[].locations[].allocatedQty`(배정 위치가 앞, `locationId` = 첫 위치).
- 결품 해결(ADR-0045): `POST /internal/work-orders/{id}/shortage-resolutions`(결품 사가, CANCEL_ITEM) — PICK·PACK 행 cancelled +q, BLOCKED PICK 은 시작자 있으면 IN_PROGRESS·없으면 ASSIGNED/READY, 남은 집품 0 이면 COMPLETED·포장 준비. `LineShortageReported` 에 outOfStockPreference·reservationId·mfcId·shipmentId.
- **예약 보류분 → 결품(2026-11-08 #5 안 C)**: PICK 시작 때(위치 배정 뒤)와 집품 반영(APPLIED)마다 pick-plan 의 BLOCKED 조각을 보고, 작업이 IN_PROGRESS 면 BLOCKED + `LineShortageReported`(reason BLOCKED, 작업 줄 남은 양까지, inventory 결품 보고는 안 부름) → 결품 사가. 피킹 중 생긴 보류는 `ReservationBlocked`(mfc.inventory.v1, 바인딩 `inventoryEvents-in-0`) → 진행 중 PICK 에 `task.blocked_check_due_at` 표시(V10, 사건 트랜잭션 안 외부 호출 없음) → `sweepBlockedChecks`(`pmart.fulfillment.blocked-check-interval` 기본 10초)가 같은 처리 — 마지막 집품 뒤 생긴 보류도 잡힌다(2026-11-08).
- **인계 허가 만료 뒤 점원 확인(2026-11-08 D-1 안 C, V8, 0.4.4)**: 확정 없이 만료된 허가 = 확인 필요(`HandoverRules.checkRequired`). 같은 라이더·배송 다시 찍기만 확인 없이, 다른 라이더 409 `FENCE_EXPIRED_UNCONFIRMED`, 중지 PENDING, 재포장 409. 재배정은 이전 허가를 끝내지 않고 이전 라이더 확정은 409 NOT_ASSIGNED. `POST /packages/{id}/handover-checks`(그 센터 MFC_WORKER): AT_STATION → 허가 해제(대기 중지는 sweep), TAKEN_BY_RIDER → 허가 라이더에게 인계 사실 → 재고 전기(확정과 같은 경로). 판정 `checkRequired`·`lastCheck`.
- **배차 요청(2026-11-08 D-5, V9, 0.4.5)**: `DispatchRequestService` — `POST /shipments/{id}/dispatch-requests`(그 센터 MFC_WORKER, PACK IN_PROGRESS/COMPLETED·출고 취소 아님, 예상 포장 1~180분) → `shipment.dispatch_*` + `DispatchRequested`(MANUAL, estimatedReadyAt = 지금 + 분). **자동 요청 없음(0.4.11, 사용자 2026-11-13 "자동요청빼", BR-51)** — 요청 없이 READY 가 돼도 사건을 내지 않고, 점원은 READY 뒤에도 요청 가능(PACK COMPLETED). 예전 자동 요청 행의 trigger AUTO_ON_READY 는 기록으로 남음. 출고당 한 번(PACK 작업 행 잠금), 다시는 200 같은 요청. 출고 조회 `dispatchRequest`. DISPATCH 작업 유형은 여전히 안 씀.
- 복귀 추천 칸(2026-11-08 #59, 0.4.6): RETURN 작업 상세 `picks[].suggestedToLocations`(inventory placement-suggestions, 실패하면 빈 목록). 복귀 실적은 inventory 진열 규칙으로 판정(다른 칸 409 SKU_ALREADY_SHELVED 등 → 실적 REJECTED).
- **포장 사이 재구성(2026-11-08 C-12 1부, 0.4.7)**: `POST /shipments/{id}/repackings`(`RepackService.regroup`, `RepackRules.regroupMismatch`) — 같은 출고·같은 포장 위치의 밀봉 포장들 → 새 포장들, (주문 줄·SKU·LOT) 합계가 정확히 같아야(422 CONTENT_MISMATCH, 새 줄은 LOT 필수), 이전 포장마다 단건 재포장과 같은 거절 규칙. 새 포장 = 새 ID·이전 최대 버전+1·새 QR·무게/부피 비움, 이전 INVALIDATED + `PackageInvalidated`(newPackageIds). 재고·예약 변화 없음. 수량 감소(포장 중 파손 등)는 사용자 결정으로 운영자가 확인·처리(자동 없음, 2026-11-08).
- **작업 내놓기·주의 표시(작업자 쪽 3단계, 0.4.8)**: `POST /tasks/{id}/releases`(담당 본인 또는 센터 관리자, ASSIGNED·IN_PROGRESS, 진행 중 실적·열린 포장 있으면 409 EXECUTION_IN_FLIGHT) → capacity `/internal/assignments/{taskId}/releases`(실패면 503 — 안 바꿈) → READY(담당·시작 지움, 완료량 그대로) + TaskProgressed. 목록·상세 `late`(시작 뒤 `pmart.fulfillment.task-minutes.<유형>` 의 2배 — 공통 설정 PICK 7·PACK 4·RETURN 5분)·`idle`(시작·마지막 실적 뒤 30분)·`lastActivityAt` — 표시만, 자동 해제 없음(Q-09).
- **오배송 재발송 작업 지시(2026-11-16, 0.5.0, V12 — ADR-0052 결정 4, 사용자 "새상품 재배차 서비스 넣어" → "전부 PR로", 작성 PC 5 — PC 3 검토)**: ConfirmOrder `kind` RESHIPMENT + `reshipmentRef`(오배송 확인 사건 caseId) = 같은 주문의 두 번째 작업 지시(`work_order.kind`·`source_ref`, 주문당 ORIGINAL 하나·근거당 하나 — `ux_work_order_order` 를 부분 유일 색인으로). 자기 재고 예약으로 덮이는지 보고, 원래 주문의 작업 중지 수량은 빼지 않는다. `findByOrder`(주문 조회·작업 중지)는 ORIGINAL 만. 재발송 출고의 WorkAccepted·PackingFinished·ShipmentReady·DispatchRequested·LineShortageReported 에 `purpose: MISDELIVERY_RESHIPMENT`·`caseId`(배차가 재발송 배송으로 표시), WorkAccepted/Rejected 집계 키는 fulfillmentId. 재발송 중 주문 작업 중지는 원래 작업 지시만 본다(재발송 출고 중지는 아직 없음).
