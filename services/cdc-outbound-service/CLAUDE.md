# cdc-outbound-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-outbound-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-outbound-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.outbound.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행 `WaveStateChanged`·`PickShortageReported`·`TruckDepartureValidated`·`OutboundDiscrepancyRaised`·`TransferShipmentManifestIssued`(토픽 `cdc.outbound.v1`, outbox). 소비 없음(TransferApproved 등은 아직 — 지금은 `/internal/transfer-intakes` 로 받는다).
- 동기 호출: cdc-inventory `POST /internal/allocations`·`GET /internal/allocations/{id}`·`POST /internal/departure-postings`(TOUT)·`POST /internal/allocations/{id}/releases`(잔여 RESIDUAL_CLOSED)(`infrastructure/InventoryClient`, `Downstream`). 주소 `pmart.outbound.cdc-inventory-base-url`.

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
  - TRF 인수 `/internal/transfer-intakes`: trfId·버전 하나. 인수 기록 → (트랜잭션 밖) cdc-inventory 할당(멱등 키 `outbound-alloc-<trfId>-v<버전>`) → 결과 기록. 재고 4xx 는 ALLOCATION_FAILED(사유), 불명·미전달은 ALLOCATING — 같은 본문 재전송이 같은 키로 다시 부른다. 같은 버전 다른 본문 409 TRANSFER_INTAKE_CONFLICT, 새 버전 대체는 아직(409 TRANSFER_CHANGE_NOT_SUPPORTED).
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
- 근무·업무 자격·온도대 출입 검사 없음(CDC 작업자 정본 없음). 게이트웨이·LAN 배치·Helm 미등록.
