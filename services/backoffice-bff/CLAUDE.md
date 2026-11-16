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

## 처리 대기 (2026-11-16, BO-12 — 계약 0.2.0, MFC 묶음 0.4.0)
- `GET /views/pending-work`(`application/PendingWorkView`, `infrastructure/PendingWorkClients`): OPERATOR_ADMIN 만. order-orchestration `/internal/stuck-sagas`(서비스 간, 토큰 없음) + order `/fulfillment-issues?state=OPEN`(운영자 토큰 전달 — 관리자는 모든 센터)을 오래된 것부터. 원천 장애면 그 항목 없이 `unavailableSources`.
- `POST /commands/{targetService}/{command}`: 지금은 `order-orchestration-service/saga-requery`(REQUERY_RESULT)·`saga-resolve`(MARK_RESOLVED)만 — targetRef = sagaId, reason 은 saga-resolve 만 필수(saga-requery 는 없으면 기본 문구), operatorId = 토큰 주체. 대상 4xx 는 코드 그대로, 그 밖 대상·명령 422 UNSUPPORTED_ACTION. 승인 없음(돈·재고를 움직이지 않는 조치, 사용자 결정).
- 주소 `pmart.bff.orchestration-base-url`(기본 `http://order-orchestration-service:8202`)·`order-base-url`(기본 `http://order-service:8201`).
- **MFC 묶음(0.4.0, 2026-11-16 사용자 "A안")**: kind `MFC_STOP_FAILED`(실패로 멈춘 집품 뒤 중지, refId = stopRequestId)·`MFC_HANDOVER_REJECTED`(재고가 거절한 인계 D-8, refId = handoverId), allowedActions `[RETRY]`. 목록은 PC 3 요청 F-4(`/api/v1/stop-requests?failed=true`·`/package-handovers?status=REJECTED` — 아직 없음, 운영자 토큰 전달)라 **`pmart.bff.mfc-pending-lists` 기본 false — 꺼져 있으면 부르지도 sources 에 넣지도 않는다**. 두 목록 중 하나라도 실패하면 MFC 항목 모두 빼고 unavailableSources(반만 보이지 않게).
- 명령 `mfc-fulfillment-service/stop-retry`·`handover-retry`: MFC 내부 재시도 경로(`/internal/stop-requests/{id}/retries`·`/internal/package-handovers/{id}/retries`, 이미 있음)로 — 설정과 상관없이 열림. reason 필수(BO-12) 이지만 **MFC 가 아직 받지 않아 저장되지 않음**(F-4 에 요청). 응답은 CommandResponse 로 옮김(중지 RESOLVED → APPLIED, PENDING+lastError → REJECTED·FIX_AND_RETRY, PENDING → ACCEPTED, 그 밖 UNKNOWN_RESULT / 인계 status 그대로). 주소 `pmart.bff.mfc-fulfillment-base-url`(기본 `http://mfc-fulfillment-service:8302`).

## 통합 조회 (2026-11-16, BO-11 — 계약 0.3.0)
- `GET /views/unified-lookup?q=&kind=`(`application/UnifiedLookupView`, `infrastructure/LookupClients`): 역할 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS. 원천 다섯(order 직원용 주문·delivery-execution 배송·delivery-dispatch 배차·claim 클레임·case 사건(열린 것 + 닫힌 것 최근 20건)·case 고객 문의)을 **운영자 토큰 전달**로 읽어 `matches` 에 원천마다 따로 싣는다(상태를 합치지 않음).
- 찾는 값: 주문·배송·사건·고객 ID(완전 일치), `kind` 비우면 접두어(ORD-·DLV-·CASE-·CU-). 배송·사건은 그 주문으로 잇는다. 고객 ID 는 주문 목록만. TRANSFER·TRIP·모르는 값은 422 UNSUPPORTED_LOOKUP(전화번호 검색 안 함).
- 원천 실패·403 은 `unavailableSources`(0건으로 바꾸지 않음), 404 는 항목 없음. summary 는 원천 필드 중 **개인정보가 아닌 것만 고른 목록**(`*_FIELDS`) — 배송지·메모·연락처(가린 값 포함)는 넣지 않는다. 찾는 값은 로그에 남기지 않는다.
- 배송은 앞 10개까지 배차·사건을 더 읽는다. 문의(`GET /inquiries?orderId=`, case 0.5.0)는 CS_AGENT·OPERATOR_ADMIN 만 볼 수 있어 원천 이름을 `case-service/inquiries` 로 따로 둔다(RIDER_OPS 403 이 사건 원천 실패로 보이지 않게), 다른 주문 문의는 bff 에서도 거르고 제목·내용은 싣지 않는다. 아직 없음: MFC 현장(PC 3 API).
- 주소 `pmart.bff.execution-base-url`(기본 `http://delivery-execution-service:8405`)·`dispatch-base-url`(8404)·`claim-base-url`(8204)·`case-base-url`(8506).

## 실시간 배송 현황 (2026-11-16, 배송 원문 §10.1 — 계약 0.3.0)
- `GET /views/live-delivery?mfcId=&state=&size=&cursor=`(`application/LiveDeliveryView`): 역할은 통합 조회와 같은 셋(모든 센터) + **MFC_SUPERVISOR(0.3.1 — 자기 센터로 고정, `centerFor`: 비우면 하나뿐인 자기 센터, 다른 센터 403, 여럿이면 400)**. mfcId 비우면 모든 센터(셋만).
- state 비우면 끝나지 않은 배송(`ACTIVE_STATES` 9개)을 delivery-execution 에서 상태마다 읽어 생긴 순으로 합쳐 size(≤50)건 — 쪽 넘김 없음, 넘으면 `truncated`. state 를 주면 원천 커서 그대로.
- 배송마다 delivery-dispatch 배차를 한 번(배차 장애면 그 칸 state null·UNKNOWN), 열린 사건은 case `/cases?size=200` 한 번으로 읽어 배송 번호(subjectRef·refs deliveryId)로 잇는다. promisedAt = 배차 arrivalWindow 의 date+end(Asia/Seoul). latestEta 는 아직 null(tracking 미연결).

## 사건함 화면 (2026-11-16 — 계약 0.5.0)
- `GET /views/case-inbox`(`application/CaseInboxView`, `LookupClients` 재사용): case `GET /cases` 를 **직원 토큰**으로(필터 이름 옮김 — assignee → assigneeId, size ≤ 50). 보이는 범위·정렬·403 은 case 판정 그대로(case 403·400 은 그대로 돌려줌, case 장애는 200 + 빈 items + unavailableSources).
- 사건마다 연결 배송(subjectType DELIVERY·refs deliveryId·**recoveryDeliveryId(오배송 회수 배송, 0.5.1)** → execution `/deliveries/{id}` 의 state)·주문(subjectType ORDER·refs orderId → order `/orders/lookups/{id}` 의 status)을 `linked` 에 — 같은 ID 는 한 번만, 합치거나 해석하지 않음. 연결 원천 실패·403 은 그 칸만 state null·UNKNOWN + unavailableSources, 404 는 state null·DIRECT(없음).

## 상태
3단계 스캐폴딩(2026-10-23). 승인함 구현(2026-11-03), 처리 대기·명령 전달 일부·통합 조회·실시간 배송 현황·사건함(2026-11-16). 그 밖의 `/views/*`·명령 결과 조회는 501.
