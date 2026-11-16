# backoffice-bff

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `backoffice-bff` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/backoffice-bff.yaml` → `openApiGenerate` 가 `com.pmart.backoffice.bff.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.backoffice.bff.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:backoffice-bff:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:backoffice-bff:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8504.

## 승인함 (2026-11-03, BR-40)
- `GET /views/approval-inbox`(`application/ApprovalInboxView`): approval-service `GET /api/v1/approvals` 를 **운영자 본인 토큰**으로 읽어(`infrastructure/ApprovalClient`, ADR-0026 의 서비스 간 인증이 아니라 사용자 토큰 전달) 화면 항목으로 묶는다. `dossierId` = `approvalId`. 결정은 approval-service 상세에서.
- approval-service 장애(연결 실패·5xx)면 200 + 빈 items + `unavailableSources=[approval-service]`(추정하지 않음). 4xx 는 같은 뜻(401·403 → 403, 그 밖 400). 주소 `pmart.bff.approval-base-url`(기본 `http://approval-service:8505`, local 은 pmart-config).

## 처리 대기 (2026-11-16, BO-12 — 계약 0.2.0)
- `GET /views/pending-work`(`application/PendingWorkView`, `infrastructure/PendingWorkClients`): OPERATOR_ADMIN 만. order-orchestration `/internal/stuck-sagas`(서비스 간, 토큰 없음) + order `/fulfillment-issues?state=OPEN`(운영자 토큰 전달 — 관리자는 모든 센터)을 오래된 것부터. 원천 장애면 그 항목 없이 `unavailableSources`.
- `POST /commands/{targetService}/{command}`: 지금은 `order-orchestration-service/saga-requery`(REQUERY_RESULT)·`saga-resolve`(MARK_RESOLVED)만 — targetRef = sagaId, reason 은 saga-resolve 만 필수(saga-requery 는 없으면 기본 문구), operatorId = 토큰 주체. 대상 4xx 는 코드 그대로, 그 밖 대상·명령 422 UNSUPPORTED_ACTION. 승인 없음(돈·재고를 움직이지 않는 조치, 사용자 결정).
- 주소 `pmart.bff.orchestration-base-url`(기본 `http://order-orchestration-service:8202`)·`order-base-url`(기본 `http://order-service:8201`).

## 통합 조회 (2026-11-16, BO-11 — 계약 0.3.0)
- `GET /views/unified-lookup?q=&kind=`(`application/UnifiedLookupView`, `infrastructure/LookupClients`): 역할 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS. 원천 다섯(order 직원용 주문·delivery-execution 배송·delivery-dispatch 배차·claim 클레임·case 열린 사건)을 **운영자 토큰 전달**로 읽어 `matches` 에 원천마다 따로 싣는다(상태를 합치지 않음).
- 찾는 값: 주문·배송·사건·고객 ID(완전 일치), `kind` 비우면 접두어(ORD-·DLV-·CASE-·CU-). 배송·사건은 그 주문으로 잇는다. 고객 ID 는 주문 목록만. TRANSFER·TRIP·모르는 값은 422 UNSUPPORTED_LOOKUP(전화번호 검색 안 함).
- 원천 실패·403 은 `unavailableSources`(0건으로 바꾸지 않음), 404 는 항목 없음. summary 는 원천 필드 중 **개인정보가 아닌 것만 고른 목록**(`*_FIELDS`) — 배송지·메모·연락처(가린 값 포함)는 넣지 않는다. 찾는 값은 로그에 남기지 않는다.
- 배송은 앞 10개까지 배차·사건을 더 읽는다. 아직 없음: MFC 현장(PC 3 API), 고객 문의(주문으로 거르는 API 없음), 닫힌 사건.
- 주소 `pmart.bff.execution-base-url`(기본 `http://delivery-execution-service:8405`)·`dispatch-base-url`(8404)·`claim-base-url`(8204)·`case-base-url`(8506).

## 상태
3단계 스캐폴딩(2026-10-23). 승인함 구현(2026-11-03), 처리 대기·명령 전달 일부·통합 조회(2026-11-16). 그 밖의 `/views/*`·명령 결과 조회는 501.
