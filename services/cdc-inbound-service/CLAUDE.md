# cdc-inbound-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-inbound-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-inbound-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.inbound.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.cdc.inbound.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:cdc-inbound-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:cdc-inbound-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8605.

## 상태
- 2026-11-26 첫 구현(계약 0.2.0, Flyway V2, 로컬 포트 8605 — WMS CDC-F-010~013, 사가 S7, 사용자 "입고·적치·보충"·"적치 지시까지"·"박스 입수"). 흐름 문서 `docs/business/flows/cdc-putaway-replenishment.md`. 설정은 부트스트랩만(ADR-0029), 시험 사본 `src/integrationTest/resources/pmart-config/`.
- 작업 하나 = 출발 칸 → 목적 칸 이동 한 줄(`putaway_task`, kind PUTAWAY 운영자·REPLENISHMENT 자동). `TaskService`: 검사(트랜잭션 밖 — 재고 스냅샷·위치 조회·상품 보관 온도) → CREATED → cdc-location 공간 예약(키 `cdc-inbound-reserve-<작업>`) → DISPATCHED. 운영자 지시는 요청 키(`request_key`) 한 번 — 다시 오면 검사 없이 그 작업.
- 가상 설비: `POST /internal/putaway-task-claims`(한 작업 한 설비, `SKIP LOCKED`, 들고 있으면 그것, 없으면 204) → `POST /internal/work-results`(eventId 한 번 — Idempotency-Key 도 eventId, 그 작업을 받은 설비만, SUCCESS = 남은 전량·PARTIAL = 1~남은·FAILED = 0) → 증가분 cdc-inventory move-postings(PUTAWAY·REPLENISHMENT_MOVE, 키 `inb-move-<eventId>`) → 예약 소비(`inb-consume-<eventId>`). 결과 상태 PENDING → MOVED → APPLIED / REJECTED(재고 거절 → 작업 FAILED).
- 실패·취소(CDC_SUPERVISOR, 반영 중 결과 있으면 409)는 남은 예약만 해제(`inb-release-<작업>`, `space_release_pending`). 불명은 주기 재시도(`pmart.inbound.retry-interval`).
- 자동 보충 `ReplenishmentService`(주기 `pmart.inbound.replenishment-interval`, 기본 1분): cdc-location `/internal/pick-faces`(최소·목표 둘 다 설정된 칸만) × 재고 스냅샷 — 계산은 흐름 문서. 칸별 마지막 판단 `replenishment_check` → `GET /cdcs/{cdcId}/replenishment-checks`.
- 주소 `pmart.inbound.cdc-inventory-base-url`(기본 http://cdc-inventory-service:8601)·`cdc-location-base-url`(기본 http://cdc-location-service:8602). 사건 발행 없음. ASN 은 501(3단계).
- 결정 역할 고침(2026-11-26): `CDC_SUPERVISOR`(직원 권한 정본) — 전에는 정본에 없는 `CDC_MANAGER`.
