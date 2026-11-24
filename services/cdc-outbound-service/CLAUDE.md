# cdc-outbound-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-outbound-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-outbound-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.outbound.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행 `WaveStateChanged`·`PickShortageReported`(토픽 `cdc.outbound.v1`, outbox). 소비 없음(TransferApproved 등은 아직 — 지금은 `/internal/transfer-intakes` 로 받는다).
- 동기 호출: cdc-inventory `POST /internal/allocations`·`GET /internal/allocations/{id}`(`infrastructure/InventoryClient`, `Downstream`). 주소 `pmart.outbound.cdc-inventory-base-url`.

## 패키지
`com.pmart.cdc.outbound.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 수량 정본은 cdc-inventory — TRF 줄의 할당 수량·Task 줄의 LOT·위치는 그 응답의 사본이다.

## 실행
```bash
./gradlew :services:cdc-outbound-service:check   # 단위 + 통합(Testcontainers, cdc-inventory 는 WireMock)
```
로컬 포트 8603. 설정 정본(pmart-config `cdc-outbound-service.yml`)은 아직 없다 — PC1 요청(`docs/operations/pc1-todo.md`).

## 상태
- 2026-11-25 새로 만듦(계약 0.3.0):
  - TRF 인수 `/internal/transfer-intakes`: trfId·버전 하나. 인수 기록 → (트랜잭션 밖) cdc-inventory 할당(멱등 키 `outbound-alloc-<trfId>-v<버전>`) → 결과 기록. 재고 4xx 는 ALLOCATION_FAILED(사유), 불명·미전달은 ALLOCATING — 같은 본문 재전송이 같은 키로 다시 부른다. 같은 버전 다른 본문 409 TRANSFER_INTAKE_CONFLICT, 새 버전 대체는 아직(409 TRANSFER_CHANGE_NOT_SUPPORTED).
  - Wave: 생성(운영자, 확보된 TRF 만, PLANNED) → 릴리스 때 재고 서비스에서 할당의 **지금** LOT·위치를 읽어 TRF × 구역 × 층 PICK Task(`domain/PickPlan`).
  - Task: 경로 `/cdcs/{cdcId}/tasks…`(게이트웨이 `/api/v1/tasks/**` 는 mfc-fulfillment). PDA 다음 작업 받기(작업자 본인 토큰, 들고 있으면 그것, 없으면 TRF 기한 → 만든 순으로 하나 — `FOR UPDATE SKIP LOCKED` 로 한 작업 한 사람, 받기 = ACCEPTED) → 착수(담당만, 첫 착수 때 Wave IN_PROGRESS) → 피킹 실적(나눠 보내도 됨, 줄마다 실피킹 + 부족 ≤ 남은 계획, 끝나면 DONE/PARTIAL, Wave WORK_DONE, TRF PICKED).
  - 부족은 `PickShortageReported` 만 — 재고 해제·재할당은 아직(예약은 출발 뒤 RESIDUAL_CLOSED 로 풀린다).
- 아직 501: 분류·검품·상차, 출발 검증(→ TOUT)·매니페스트·출발 준비, 미출발 취소. 근무·업무 자격·온도대 출입 검사 없음(CDC 작업자 정본 없음). 게이트웨이·LAN 배치·Helm 미등록.
