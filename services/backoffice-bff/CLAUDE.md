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
- **MFC 묶음(0.4.0, 2026-11-16 사용자 "A안" — 0.8.0 2026-11-17 PC 3 F-4(mfc-fulfillment 0.9.0) 연결: 목록 기본 켬, 항목 `lastRetry`, 재시도 본문 `{operatorId: 토큰 주체, reason}`(500자) → MFC 재시도 기록; 0.9.1 목록을 커서로 최대 5쪽 × 200건, 넘으면 응답 `truncated`)**: kind `MFC_STOP_FAILED`(실패로 멈춘 집품 뒤 중지, refId = stopRequestId)·`MFC_HANDOVER_REJECTED`(재고가 거절한 인계 D-8, refId = handoverId), allowedActions `[RETRY]`. 목록은 PC 3 요청 F-4(`/api/v1/stop-requests?failed=true`·`/package-handovers?status=REJECTED` — 아직 없음, 운영자 토큰 전달)라 **`pmart.bff.mfc-pending-lists` 0.8.0 부터 기본 true — false 면 부르지도 sources 에 넣지도 않는다**. 두 목록 중 하나라도 실패하면 MFC 항목 모두 빼고 unavailableSources(반만 보이지 않게).
- 명령 `mfc-fulfillment-service/stop-retry`·`handover-retry`: MFC 내부 재시도 경로(`/internal/stop-requests/{id}/retries`·`/internal/package-handovers/{id}/retries`, 이미 있음)로 — 설정과 상관없이 열림. reason 필수(BO-12, 500자) — 운영자 ID(토큰 주체)와 함께 MFC 에 넘겨 MFC 재시도 기록으로 남는다(0.8.0). 응답은 CommandResponse 로 옮김(중지 RESOLVED → APPLIED, PENDING+lastError → REJECTED·FIX_AND_RETRY, PENDING → ACCEPTED, 그 밖 UNKNOWN_RESULT / 인계 status 그대로). 주소 `pmart.bff.mfc-fulfillment-base-url`(기본 `http://mfc-fulfillment-service:8302`).

## 통합 조회 (2026-11-16, BO-11 — 계약 0.3.0)
- `GET /views/unified-lookup?q=&kind=`(`application/UnifiedLookupView`, `infrastructure/LookupClients`): 역할 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS. 원천 다섯(order 직원용 주문·delivery-execution 배송·delivery-dispatch 배차·claim 클레임·case 사건(열린 것 + 닫힌 것 최근 20건)·case 고객 문의)을 **운영자 토큰 전달**로 읽어 `matches` 에 원천마다 따로 싣는다(상태를 합치지 않음).
- 찾는 값: 주문·배송·사건·고객 ID(완전 일치), `kind` 비우면 접두어(ORD-·DLV-·CASE-·CU-). 배송·사건은 그 주문으로 잇는다. 고객 ID 는 주문 목록만. TRANSFER·TRIP·모르는 값은 422 UNSUPPORTED_LOOKUP(전화번호 검색 안 함).
- 원천 실패·403 은 `unavailableSources`(0건으로 바꾸지 않음), 404 는 항목 없음. summary 는 원천 필드 중 **개인정보가 아닌 것만 고른 목록**(`*_FIELDS`) — 배송지·메모·연락처(가린 값 포함)는 넣지 않는다. 찾는 값은 로그에 남기지 않는다.
- 배송은 앞 10개까지 배차·사건을 더 읽는다. 문의(`GET /inquiries?orderId=`, case 0.5.0)는 CS_AGENT·OPERATOR_ADMIN 만 볼 수 있어 원천 이름을 `case-service/inquiries` 로 따로 둔다(RIDER_OPS 403 이 사건 원천 실패로 보이지 않게), 다른 주문 문의는 bff 에서도 거르고 제목·내용은 싣지 않는다. **MFC 현장(0.9.0, 2026-11-17 — PC 3 F-3 mfc-fulfillment 0.10.0)**: 주문으로 `GET /work-orders?orderId=`(직원 토큰, `Source.FULFILLMENT` — 주소는 `mfc-fulfillment-base-url`) → 작업 지시마다 `FULFILLMENT`(`FULFILLMENT_FIELDS` — shipments 안 작업·결품 보고·포장·인계, 원천 모양 그대로), 중지 요청마다 `MFC_STOP`. 404 는 현장 기록 없음.
- 주소 `pmart.bff.execution-base-url`(기본 `http://delivery-execution-service:8405`)·`dispatch-base-url`(8404)·`claim-base-url`(8204)·`case-base-url`(8506).

## 실시간 배송 현황 (2026-11-16, 배송 원문 §10.1 — 계약 0.3.0)
- `GET /views/live-delivery?mfcId=&state=&size=&cursor=`(`application/LiveDeliveryView`): 역할은 통합 조회와 같은 셋(모든 센터) + **MFC_SUPERVISOR(0.3.1 — 자기 센터로 고정, `centerFor`: 비우면 하나뿐인 자기 센터, 다른 센터 403, 여럿이면 400)**. mfcId 비우면 모든 센터(셋만).
- state 비우면 끝나지 않은 배송(`ACTIVE_STATES` 9개)을 delivery-execution 에서 상태마다 읽어 생긴 순으로 합쳐 size(≤50)건 — 쪽 넘김 없음, 넘으면 `truncated`. state 를 주면 원천 커서 그대로.
- 배송마다 delivery-dispatch 배차를 한 번(배차 장애면 그 칸 state null·UNKNOWN), 열린 사건은 case `/cases?size=200` 한 번으로 읽어 배송 번호(subjectRef·refs deliveryId)로 잇는다. promisedAt = 배차 arrivalWindow 의 date+end(Asia/Seoul). **latestEta(0.7.0, 2026-11-17 TR-3)** = tracking `GET /deliveries/{id}/eta`(직원 토큰, 배송마다 한 번)의 dropEta.etaAt — status AVAILABLE 일 때만, `etaStatus`·`delayedBySeconds` 는 원천 값 그대로, `etaSource` 따로(404 = tracking 배포 전 배송 → null·DIRECT, 실패·403 → UNKNOWN + unavailableSources). 주소 `pmart.bff.tracking-base-url`(기본 `http://delivery-tracking-service:8406`).

## 사건함 화면 (2026-11-16 — 계약 0.5.0)
- `GET /views/case-inbox`(`application/CaseInboxView`, `LookupClients` 재사용): case `GET /cases` 를 **직원 토큰**으로(필터 이름 옮김 — assignee → assigneeId, size ≤ 50). 보이는 범위·정렬·403 은 case 판정 그대로(case 403·400 은 그대로 돌려줌, case 장애는 200 + 빈 items + unavailableSources). **0.9.3**: case 401 → 401(다시 로그인), 그 밖 4xx 상태 그대로(400 만 조건 오류). 연결 원천이 장애(PmartException)면 남은 건은 부르지 않고 UNKNOWN(4xx 는 건마다). **0.10.0**: 항목에 case `myLastSeenAt`(부른 직원이 마지막으로 본 시각) 그대로.
- 사건마다 연결 배송(subjectType DELIVERY·refs deliveryId·**recoveryDeliveryId(오배송 회수 배송, 0.5.1)·reshipmentDeliveryId(오배송 재발송 배송, 0.5.2)** → execution `/deliveries/{id}` 의 state)·주문(subjectType ORDER·refs orderId → order `/orders/lookups/{id}` 의 status)을 `linked` 에 — 같은 ID 는 한 번만, 합치거나 해석하지 않음. 연결 원천 실패·403 은 그 칸만 state null·UNKNOWN + unavailableSources, 404 는 state null·DIRECT(없음).
- **도움요청 대화(0.6.0, 2026-11-17 — PC 5 DE-2 반영)**: 라이더 도움요청 사건(kind RIDER_HELP, refs deliveryId·requestId)은 execution `GET /deliveries/{id}/help-requests/{requestId}` 를 직원 토큰으로 읽어 `linked` 에 kind HELP_REQUEST(state·`lastRiderMessageAt`·`lastSupportMessageAt`). "새 메시지" 판단(두 시각 비교·직원별 읽음)은 화면이 한다. 실패·403·404 규칙은 배송·주문과 같다.

## 검토 후속 (2026-11-17, 0.9.2 — 검토자 결과 중 확인한 것)
- 실시간 배송: 배차·도착 예정은 이 요청에서 한 번 실패하면 남은 배송은 부르지 않고 UNKNOWN(멈춘 원천 × 배송 수만큼 기다리지 않게). 배차 404 는 DIRECT(없음). 열린 사건은 커서로 최대 5쪽(`CASE_MAX_PAGES`), 넘으면 `casesTruncated` 이고 안 붙은 배송 openCases 는 null(모름).
- MFC 재시도의 결과 모름(5xx·시간 초과 → `UNKNOWN_RESULT`)은 오류가 아니라 202 `status UNKNOWN_RESULT`·`nextAction QUERY_RESULT`.
- 원천 본문이 JSON 이 아니면 그 원천 장애(`dependencyUnavailable`) — 화면 전체 500 아님. 처리 대기 실패 로그는 예외 이름만(원천 본문 안 남김).
- `PendingWorkItem.orderId` nullable(MFC 거절 인계는 주문 ID 가 없을 수 있음).

## 포장 인계 현황 (2026-11-17, 배송 원문 §10.1·§3.1·§3.4·§3.7 — 계약 0.11.0)
- `GET /views/packing-handover?mfcId=&state=&size=&cursor=`(`application/PackingHandoverView`): 원천 mfc-fulfillment `GET /shipments`(0.15.0, PC 3 F-9 — 직원 토큰, `Source.FULFILLMENT`) 한 번 + 출고마다 delivery-execution `GET /deliveries?orderId=&mfcId=&type=&sort=NEWEST&size=1`(재발송 출고는 RESHIPMENT, 그 밖 FORWARD).
- 역할: 본사 셋(mfcId 필수 — 비우면 400) + MFC_SUPERVISOR(`LiveDeliveryView.centerFor` 로 자기 센터 고정 — 사용자 2026-11-17 "그래 넣고"). 작업자만 가진 직원 403(PDA).
- **단계 `stage`**(사용자 2026-11-17 "BFF로 단계표시" — `PackingHandoverView.stage`, 계약에 규칙): 출고 HANDED_OVER·PARTIALLY_HANDED_OVER·READY(→ READY_FOR_HANDOVER) 그대로, PREPARING 이면 PACK IN_PROGRESS·BLOCKED → PACKING, PICK IN_PROGRESS·BLOCKED → PICKING, PICK COMPLETED·PACK 시작 전 → PACKING(사용자 2026-11-17 "(가)" — 원문 네 단계 안에서), 둘 다 시작 전(없음·WAITING·READY·ASSIGNED) → REQUESTED. 그 밖 조합 null(추정하지 않음 — 원천 값 shipment·pick·pack 이 같이 나간다).
- 수량: `handedOverQty` = 원천 packageCounts.handedOver(포장 수), `remainingPackages` = total − handedOver(같은 원천·기준시각). MFC asOf 를 그 칸 source asOf 로.
- 원천 실패: MFC 실패면 빈 items + unavailableSources(400 은 조건 오류 400), 배송은 한 번 실패하면 남은 출고는 부르지 않고 UNKNOWN. `LookupClients.get` 은 0.11.0 부터 Collection 값을 반복 파라미터로 보낸다(state=A&state=B).

## 상태
3단계 스캐폴딩(2026-10-23). 승인함 구현(2026-11-03), 처리 대기·명령 전달 일부·통합 조회·실시간 배송 현황·사건함(2026-11-16), 포장 인계 현황(2026-11-17). 그 밖의 `/views/*`·명령 결과 조회는 501.
