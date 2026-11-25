# cdc-outbound-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-outbound-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-outbound-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.outbound.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행 `WaveStateChanged`·`PickShortageReported`·`TruckDepartureValidated`·`OutboundDiscrepancyRaised`·`TransferShipmentManifestIssued`(토픽 `cdc.outbound.v1`, outbox). 소비 없음(TransferApproved 등은 아직 — 지금은 `/internal/transfer-intakes` 로 받는다).
- 동기 호출: cdc-location `POST /internal/location-lookups`(0.9.0 — Wave 릴리스 때 통로 pickMethod, `infrastructure/LocationClient`, 주소 `pmart.outbound.cdc-location-base-url`, 안 되면 릴리스 503). cdc-inventory `POST /internal/allocations`·`GET /internal/allocations/{id}`·`POST /internal/departure-postings`(TOUT)·`POST /internal/allocations/{id}/releases`(잔여 RESIDUAL_CLOSED)(`infrastructure/InventoryClient`, `Downstream`). 주소 `pmart.outbound.cdc-inventory-base-url`.

## 패키지
`com.pmart.cdc.outbound.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 수량 정본은 cdc-inventory — TRF 줄의 할당 수량·Task 줄의 LOT·위치는 그 응답의 사본이다.

## 실행
```bash
./gradlew :services:cdc-outbound-service:check   # 단위 + 통합(Testcontainers, cdc-inventory 는 WireMock)
```
로컬 포트 8603. 설정 정본 pmart-config `cdc-outbound-service.yml`·`cdc-outbound-service-local.yml`(2026-11-25 13d64d4), lan 파일은 LAN 배치 때(PC1).

## 상태
- 2026-11-25 새로 만듦(계약 0.3.0):
  - TRF 인수 `/internal/transfer-intakes`: trfId·버전 하나. 인수 기록 → (트랜잭션 밖) cdc-inventory 할당(멱등 키 `outbound-alloc-<trfId>-v<버전>`) → 결과 기록. 재고 4xx 는 ALLOCATION_FAILED(사유), 불명·미전달은 ALLOCATING — 같은 본문 재전송이 같은 키로 다시 부른다. 같은 버전 다른 본문 409 TRANSFER_INTAKE_CONFLICT, 새 버전 대체는 0.13.0(아래).
  - Wave: 생성(운영자, 확보된 TRF 만, PLANNED) → 릴리스 때 재고 서비스에서 할당의 **지금** LOT·위치를 읽어 TRF × 구역 × 층 PICK Task(`domain/PickPlan`).
  - Task: 경로 `/cdcs/{cdcId}/tasks…`(게이트웨이 `/api/v1/tasks/**` 는 mfc-fulfillment). PDA 다음 작업 받기(작업자 본인 토큰, 들고 있으면 그것, 없으면 TRF 기한 → 만든 순으로 하나 — `FOR UPDATE SKIP LOCKED` 로 한 작업 한 사람, 받기 = ACCEPTED) → 착수(담당만, 첫 착수 때 Wave IN_PROGRESS) → 피킹 실적(나눠 보내도 됨, 줄마다 실피킹 + 부족 ≤ 남은 계획, 끝나면 DONE/PARTIAL, Wave WORK_DONE, TRF PICKED).
  - 부족은 `PickShortageReported` 만 — 재고 해제·재할당은 아직(예약은 출발 뒤 RESIDUAL_CLOSED 로 풀린다 — 0.4.0 출발 검증이 자동으로 부른다).
- 0.4.0(2026-11-25): 분류·검품·상차·출발 검증·매니페스트(Flyway V3).
  - LPN 단위 BOX·PLT·**RT(롤테이너)** — 수량은 BOX 의 contents 만, RT·PLT 는 그릇(하위 BOX 는 parentLpn). 검품·상차·출발 대조는 BOX 로 펼친다(TMS D-07 적재 = R/T, 검증 = 박스).
  - 분류 ≤ 실피킹(SKU·LOT), 검품은 RT 면 안의 BOX 모두, 상차는 PASSED BOX 만·한 운행에만.
  - 출발 검증: departureId 당 한 번(같은 본문 재전송 = 불명 TOUT 재시도, 다른 본문 409). BOX 대조(LPN_NOT_LOADED·LPN_NOT_IN_DEPARTURE·WRONG_TRF·WRONG_MFC·SEAL_MISMATCH) → 일치 TRF 만 TOUT(트랜잭션 밖, 키 `outbound-tout-<dep>-<trf>`, 줄 키 `DL-<sha>`), 이어서 잔여 해제(RESIDUAL_CLOSED, 409 INVALID_STATE 는 끝난 것 — 불명이면 `residual_status` PENDING, 그 밖의 거절은 FAILED·`residual_reason`(0.5.0, Flyway V5, 경고 로그), PENDING·FAILED 는 같은 출발 재전송이 다시 부름, 응답 `transfers[].residualStatus·residualReason`), 매니페스트(BOX 줄 + LOT 소비기한) → TRF DEPARTED. **SIMULATED 도 TOUT**(재고 기록에 SIMULATED). 불일치 TRF 는 다시 출발 검증할 수 없다(같은 TRF 2회 출발 금지 — 조사 몫).
  - 미설명 수량(0.8.0, CDC-F-017 — 사용자 결정 2026-11-25): TRF 줄 실피킹 > 그 TRF 모든 BOX 분류(SKU 별)면 `UNEXPLAINED_QTY`(SKU 마다) → MISMATCHED·TOUT 없음, `OutboundDiscrepancyRaised` MISSING(lines 에 sku·expectedQty = 실피킹·actualQty = 분류). 출발 준비 조회 `UNSORTED_QTY` 와 같은 사실 — 시뮬레이터는 출발 전에 그것을 본다.
  - 같은 작업자의 다음 작업 받기는 권고 잠금으로 줄 세움(다른 키 동시 두 번에도 한 작업).
- 출발 준비 조회(0.6.0): `/internal/outbound-readiness?trfId` — WMS 자기 사실만으로 단계(NOT_STARTED→PICKING→PICKED→SORTED→INSPECTED→LOADED, DEPARTED·CANCELLED)·검품 합격 BOX 수량·무게·온도대·막힌 이유, TMS·MFC 조건은 보지 않음(CDC-F-020).
- 미출발 취소·축소(0.7.0, Flyway V6, CDC-F-026): `/cdcs/{cdcId}/transfers/{trfId}/pre-departure-cancellations`(게이트웨이 `/api/v1/transfers/**` 는 replenishment) — **피킹 시작 전 범위**(사용자 2026-11-25). 기록·작업 막기(남은 PICK CREATED·ASSIGNED·ACCEPTED → CANCELLED, TRF CANCELLING) → 트랜잭션 밖 재고 해제(키 `outbound-cancel-<id>`) → TRF CANCELLED·Wave 닫기. 미할당이면 재고 호출 없이 바로. PARTIAL 은 Wave 릴리스 전에만(할당된 만큼 해제, `transfer_line.cancelled_qty`). 불명 UNKNOWN(같은 본문 재전송이 같은 키로), 거절 REJECTED·FULL 은 CANCEL_HOLD. 피킹·분류가 시작됐으면 409 PICKING_STARTED(**취소는 피킹 전에만 — 사용자 확정 2026-11-25**, 공급 쪽 취소 사건 구독·취소 사건 발행은 하지 않음). 취소 중·취소된 TRF 는 Wave 편성·릴리스·출발 검증에서 빠진다. 사건 발행 없음.
- 묶음 피킹(0.9.0, Flyway V7, 설계 `docs/cdc/design/2026-11-25-batch-picking.md` §7): Wave 릴리스가 위치 통로 방식으로 `domain/BatchPlan` —
  DPS·방식 없음(팔레트 랙·설정 없음) = DISCRETE(지금처럼 TRF × 구역 × 층 — 사용자 "지금처럼 TRF 하나씩"), BOX_PICK·FC·DISTRIBUTION = REGION_BATCH(지역 × 방식 × 구역 × 층), LINE = SKU_BATCH(방식 × 구역 × 층).
  같은 SKU·LOT·위치는 한 줄, 줄 밑 `task_line_share`(TRF·MFC·기한·계획·실피킹·부족 — 단건 작업도 몫 한 줄). 묶음이면 `task.trf_id` 비움(사용자 2026-11-25), 응답 trfIds·shares.
  실적은 줄 합계 → 실피킹은 기한 이른 몫부터, 부족은 늦은 몫부터(같으면 trfId 순) → TRF 줄·`PickShortageReported` 는 TRF 마다. TRF 는 몫이 든 작업이 다 끝나면 PICKED.
  지역표 `region_mfc`(`GET·POST /cdcs/{cdcId}/regions`, 칠곡 5곳·4지역 BUSAN·HONAM·CHUNGCHEONG·GANGWON 은 V7 시드 — 지역 코드 = PDA 지역 바코드 값). 묶음 크기 `pmart.outbound.batch-max-lines`(없으면 자르지 않음).
  착수 전 미출발 취소는 묶음 작업에서 그 TRF 몫만 뺀다(줄 계획 −몫, 빈 작업은 CANCELLED). 재고 MOV 없음(설계 §4).
  DPS 는 TRF × 구역 × 층 × **선반 통로**(0.10.0, Flyway V8 `task.aisle` — 시뮬레이터 R-20, 사용자 "제안대로"): 컨베이어 통로 작업자가 다음 작업 받기 `aisles` 로 자기 통로만 받는다(TRF 끼리는 묶지 않음).
- 피킹 부족 재할당(0.12.0, Flyway V9 `pick_shortage_followup`, WMS CDC-F-018 — 사용자 2026-11-25 "부족 칸은 새 할당에서 뺀다"·"48개만 보냄"): 피킹 실적 트랜잭션에서 TRF 몫 부족마다 후속 행(PENDING) →
  커밋 뒤(`ShortageService.processOpen`, 실적 응답 직후 + `@Scheduled` `pmart.outbound.shortage-retry-interval` 기본 PT30S) cdc-inventory 해제(PICK_SHORTAGE·LOT·위치·`recountHold`, 키 `outbound-short-rel-<id>`) → RELEASED →
  추가 할당(`/additions`, 키 `outbound-short-add-<id>`) → 잡힌 LOT 으로 같은 Wave 에 새 PICK 작업(`WaveService.createTasks`, 통로 방식대로 — TRF PICKED 였으면 IN_WAVE·Wave WORK_DONE 이면 IN_PROGRESS) → DONE·`PickShortageResolved`(reallocatedQty·unresolvedQty·newTaskIds).
  TRF 줄: 할당 그대로, 부족 −다시 잡은 양(남은 부족 = 못 찾음 — 실피킹 + 부족 ≤ 할당). 불명은 그 단계에 남아 같은 키로 다시, 재고 거절·TRF 출발/취소면 FAILED(전량 못 찾음). 출발 준비 blockers `SHORTAGE_REALLOCATING`.
- TRF 새 승인 버전 대체(0.13.0, Flyway V10 `transfer.superseded_allocation_id`, WMS CWC-03 — 사용자 2026-11-25 "피킹 시작 전까지"): 더 높은 trfVersion 인수 → (트랜잭션) 피킹·분류 시작 검사(PICKING_STARTED)·출발/취소 INVALID_STATE·예전 할당 불명 ALLOCATION_IN_PROGRESS·대체 중 VERSION_CHANGE_IN_PROGRESS·취소 중 CANCELLATION_IN_PROGRESS →
  안 끝난 작업에서 몫 빼기(`cancelOpenTasks`)·새 버전 줄로 바꾸기·Wave 에서 빼기(남은 TRF 없으면 Wave CANCELLED, 릴리스 뒤 남은 작업 없으면 WORK_DONE) → CHANGING → (밖) 예전 할당 해제 TRANSFER_CHANGED(키 `outbound-change-<trf>-v<n>`, 409 INVALID_STATE 는 끝난 것) → ALLOCATING → 새 버전 할당.
  해제 불명은 CHANGING, 거절은 CHANGE_HOLD — 둘 다 같은 본문 재전송이 이어서. 미출발 취소는 대체 중 409. readiness VERSION_CHANGE_PENDING·VERSION_CHANGE_HOLD. 운영자가 다시 Wave 에 넣는다.
- 피킹 재고 출고대기 자리로 옮겨 적기(0.14.0, Flyway V11 `pick_move`·`region_mfc.staging_bay` — 사용자 2026-11-25, 설계 `2026-11-25-count-adjustment.md` §5): 피킹 실적의 실피킹(TRF 몫·줄)마다 기록 → 커밋 뒤 `PickMoveService.processOpen`(실적 직후 + 주기) cdc-inventory move-postings(PICK_MOVE·allocationId, 키 `outbound-pickmove-<id>`) 칸 → `PickMoveService.stagingCode`(냉동 A·냉장 B 그대로, 칠곡 상온 5층 C·7층 G, 통로 91·자리 = 지역표 stagingBay — CDC01 V11 시드 01~05). 불명은 재시도, 거절·자리 없음은 FAILED(원위치에 남음·경고). readiness `PICK_MOVE_PENDING`. 출발 TOUT 은 할당이 지금 잡은 위치(= 출고대기)에서.
- 근무·업무 자격·온도대 출입 검사 없음(CDC 작업자 정본 없음). 게이트웨이·LAN 배치·Helm 미등록.
- **DC 백오피스 직원 조회(2026-11-25, 0.11.0 — 사용자 "Dc 백오피스 만들어줘", 계획 docs/cdc/cdc-backoffice-plan.md 1단계)**: `api/StaffViewsController` → `application/StaffViews` → `infrastructure/StaffViewStore`(읽기만, `Support.requireCenter`). `GET /cdcs/{cdcId}/transfers`(state·mfcId·page·size≤200, 기한 순, 줄 합)·`…/transfers/{trfId}`(줄별 승인·할당·피킹·부족)·`/outbound-summary?date=`(Asia/Seoul 그날 기한 TRF — 상태별 수·수량 합·진행률 피킹÷승인, Wave 상태별·작업 종류:상태별). 게이트웨이 라우트·허용 목록. 시험 `OutboundFlowIT.intakeWaveTasksPicking` 2-1.
