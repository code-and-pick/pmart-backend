# transport-execution-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `transport-execution-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/transport-execution-service.yaml` → `openApiGenerate` 가 `com.pmart.transport.execution.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.transport.execution.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:transport-execution-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:transport-execution-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8702.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- **0.2.0(2026-11-26, TMS 1단계 — 설계 `docs/cdc/design/2026-11-26-tms-first-stage.md`, cdc 세션)**, Flyway V2 `trip`·`trip_stop`·`trip_transfer`·`trip_event`·`handover`·`trip_incident`, `TripService`:
  - 운행 = planning 계획완료 지시서 사본(`/internal/trips`, tripId = orderId — 같은 본문 그대로, SCHEDULED 이고 지시서 버전이 높으면 바꿈, 아니면 409 TRIP_CONFLICT). 취소는 출차 전만(409 ALREADY_DEPARTED).
  - 명령은 상태로 멱등(같은 명령 다시 = 그대로). 입차 → 상차완료(이관요청서별 박스 — 빠진 TRF 는 NOT_LOADED 예외·출발 구성에서 빠짐, 모두 빠지면 409 NOTHING_LOADED) → 출차(LOADED 에서만, 상차로 자동 출차 없음, departureId) → 정차 도착(앞 정차 출발 뒤) → 인계(그 MFC 운영자, 출고 박스 vs 인수 박스 — 차이·파손이면 MISMATCHED·QTY_MISMATCH 예외, 한 번만) → 정차 출발(인계 증빙 필요) → DC 복귀(모든 정차 인계 뒤 COMPLETED).
  - 권한: 입차·상차·출차·복귀는 출발 센터 운영자, 도착·정차 출발은 출발 센터나 그 정차 MFC, 인계는 그 MFC, 조회는 출발 센터나 정차 MFC. 시뮬레이터는 `/internal/trips/{id}/commands/{command}`(토큰 없이 — 출차 source 기본 SIMULATED).
  - 트랜잭션 밖 알림: cdc-outbound `departure-validations`(키 = departureId, outboundLinkage ACKNOWLEDGED·FAILED·UNKNOWN_RESULT), planning `trip-updates`(version 이 `planning_synced_version` 보다 크면), 복귀 뒤 fleet 점유 해제. 실패·불명은 `retry()`(주기 `pmart.execution.retry-interval`)가 같은 키로 — 출차·인계 사실은 되돌리지 않는다.
  - 예상 도착·지연은 조회 때 계산(`domain/TripRules` — 실제 출차·도착부터 남은 구간 + 하차 30분, 계획 + 30분 또는 D+1 07:30 초과 = 지연 플래그). 저장 안 함.
  - **사건 발행 없음**(설계 §6 — TruckActuallyDeparted 줄 SKU·LOT 을 모름, 구독자 없음). 도크 배정·온도 이탈·운임 근거는 뒤로.
- 0.3.0(2026-11-26, DK-2): 운행에 도크 사본 `dock_code`·`dock_reservation_id`(Flyway V3 — planning 이 준 값, 없으면 null). 도크 해제는 planning 몫.
- 0.4.0(2026-11-27, 요청 TE-1 — PC 3, cdc 세션 대신 PC1): 정차가 처음 ARRIVED 가 되면 같은 트랜잭션에서 outbox 로 `StopArrived`(tms.trip.v1, aggregate Trip, `TripService.publishStopArrived`) — tripId·stopId `<tripId>-<seq>`·mfcId·sequence·arrivedAt·arrivalBasis DRIVER_INPUT·trfIds = 그 정차에 **상차한** TRF. 이 서비스의 첫 사건(Debezium `outbox-transport-execution-service` 는 이미 등록됨). 구독 mfc-inbound 0.24.0(입고 예정 자동 도착). 시험 `ExecutionFlowIT`(outbox 한 줄·미상차 TRF 뺌).
- 0.5.0(2026-11-27, DC 시뮬레이터 R-29 — PC1, Flyway V4 `trip.release_after`): 인계 = 정차 자동 출발(`TripService.autoDepart`), 모든 정차 출발 = 운행 자동 COMPLETED(`completeIfDone`, endedAt = 마지막 정차 출발). 점유 해제는 `release_after` = 종료 + return_minutes 뒤(`releaseReservation`·`retry`), DC 복귀 등록이 당김(PHYSICAL_END 기록). 수동 정차 출발도 마지막이면 종료. 옛 HANDED_OVER 정차는 `retry` 가 인계 시각으로 정리. 시험 `ExecutionFlowIT`.
- 0.6.0(2026-11-27, DC 백오피스 DN-3·MFC 시뮬레이터 R-52 — 사용자 "cdc 세션이 둘 다", Flyway V5 `trip_transfer.trf_number`·`trip.departure_number`): 화면 번호 사본 — TRF 번호는 planning 이 TripInput `stops[].trfNumbers` 로 준 값, 출발 번호는 cdc-outbound 출발 검증 응답 `departureNumber`(`sendOutbound`). 응답 붙이기 `api/TripNumberLabels`(Trip·TripPage — `departureNumber`·`stops[].trfNumbers`·`transfers[].trfNumber`, inbound-trips 포함). 옛 운행은 비어 있음.
