# fleet-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `fleet-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/fleet-service.yaml` → `openApiGenerate` 가 `com.pmart.fleet.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.fleet.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:fleet-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:fleet-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8703.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- **0.2.0(2026-11-26, TMS 1단계 — 설계 `docs/cdc/design/2026-11-26-tms-first-stage.md`, cdc 세션)**, Flyway V2 `carrier`·`vehicle`·`driver`·`fleet_reservation`, `FleetService`:
  - 운송사·차량·기사 등록·조회. 센터에 묶이지 않는 기준정보 — 조회는 운영자 누구나, 등록·변경은 `CDC_SUPERVISOR`(직원 권한 정본 `docs/architecture/auth/staff-roles.md`). 같은 ID 409 ALREADY_EXISTS.
  - 차량: 계약 구분 REGULAR/SPOT·온도대 하나·톤수·최대 R/T(기본 20)·최대 중량(기본 5,000kg — [가정]). 바꾸기는 번호판·최대값·사용 여부만(X-Expected-Version).
  - 기사: 표시 이름만 둔다(실명·연락처·면허 번호 없음).
  - 점유 `/internal/reservations`: [시작,끝) 겹침을 차량·기사 따로(권고 잠금으로 같은 자원 줄 세움 — ID 순서로 잠가 교착 막음) → 409 RESOURCE_OCCUPIED(detail 에 VEHICLE/DRIVER·겹친 점유 ID). 사용 중지 409 RESOURCE_INACTIVE. purposeRef(운송지시서) 당 살아 있는 점유 하나 — 같은 본문 그대로, 다르면 앞 것을 REPLANNED 로 풀고 새로(한 트랜잭션). 해제는 멱등.
  - 이벤트 없음. 시드 `deploy/lan/seed/tms_fleet.py`(운송사 1·정규차 12·기사 12 — 노선 4 × 온도대 3).
