# case-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `case-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/case-service.yaml` → `openApiGenerate` 가 `com.pmart.cases.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.cases.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:case-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:case-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8506.

## 상태
3단계 스캐폴딩(2026-10-23). 아래 문의 외 엔드포인트는 501 UNSUPPORTED_ACTION.
- **고객센터 문의(2026-11-09, 계약 0.2.0, V2)**: `/inquiries`(`InquiryService`·`domain/InquiryRules`·`InquiryStore`) — 사용자 결정 C2(회원만)·C4(고객앱 문의 페이지). 고객이 남기고(본인 주문이면 order `GET /orders/{id}` 를 고객 토큰으로 불러 확인 — `OrderClient`, 설정 `pmart.cases.order-service-base-url`), 상담원 역할 `CS_AGENT` 가 답한다(→ ANSWERED). `OPERATOR_ADMIN` 은 목록·상세 보기만(답변 403 — 2026-11-16 사용자 (가), 계약 0.4.2). 문의 하나 = `cs_inquiry` + `case_record`(CS_INQUIRY). 남의 문의는 404, 내용은 로그에 남기지 않는다. 사진 첨부·종료 API 는 아직 없다. 답변 때 `InquiryAnswered`(platform.case.v1, 답변 내용 없음 — 2026-11-16 고객 알림 N-2).
- **라이더 컴플레인(2026-11-15, 계약 0.3.0, V3 — service-requests R-5)**: `/rider-complaints`(`RiderComplaintService`·`domain/RiderComplaintRules`·`RiderComplaintStore`). 상담원(CS_AGENT·OPERATOR_ADMIN)이 기록 → 라이더는 dispatch 내부 배정 조회(`DispatchClient`, 설정 `pmart.cases.delivery-dispatch-base-url`) → `rider_complaint` + Outbox `RiderComplaintRecorded`(platform.case.v1, 이 서비스의 첫 발행). 조회는 RIDER_OPS 도. 메모는 로그·사건 금지.

- **사건함(2026-11-16, 계약 0.4.0, V4 — 사용자 "사건함 공통 결정" 추천대로, R-4·R-6·R-7)**: `CaseInboxService`·`domain/CaseRules`·`CaseStore`(표 `case_record` 확장 + `case_history`).
  - 여는 길: 구독 `deliveryEvents`(delivery.delivery.v1 `DeliveryIncidentRaised` → RIDER_HELP·DELIVERY_INCIDENT, 사고 P1), `trackingEvents`(delivery.tracking.v1 `RiderLocationStale` → RIDER_LOCATION_STALE — 같은 라이더·배송으로 열린 사건이면 RECURRED 이력만), 직원 `POST /cases` kind MISDELIVERY(+ `MisdeliveryConfirmed`, 배정 라이더는 dispatch 조회 — 실패해도 기록). 같은 일(dedupe_key)로 열린 사건은 하나.
  - 팀 공용 목록 `GET /cases`(state 비우면 열린 것, P1·기한 순, unassigned·overdue·assigneeGroup) → 집기 `POST …/assignments`(자기 자신만, 관리자는 다른 사람에게) → 닫기 `POST …/closures`(resolution·summary 필수, 남이 맡은 것은 관리자만). 보기 OPERATOR_ADMIN·RIDER_OPS·CS_AGENT(배송 쪽, CS 는 보기만).
  - 처리 기한 `pmart.cases.sla.[종류]`(맵 키는 `"[RIDER_HELP]"` 처럼 — `_` 를 지키려고) — 표시만(`overdue`), 알림 없음. 값 확정(2026-11-16 사용자 — 도움요청·위치 끊김 10분, 전달 실패·오배송 30분).
  - 사건 `CaseOpened·CaseAssigned·CaseClosed`(platform.case.v1). 고객 문의 행(CS_INQUIRY)은 `/cases` 에 나오지 않는다.
  - 아직: 행위 요청(501). 재개 0.6.0, 이관 0.7.0, 의무 0.8.0.
- **사가·결제 이상(2026-11-16, 계약 0.5.0 — R-3, 사용자: 기한 1시간·"이상만"·멈춘 사가는 지금 안 함)**: `CaseInboxService.onOrderException`·`CaseRules.orderExceptionKind`. 마이그레이션 없음(같은 표).
  - 구독 `sagaEvents`(order.saga.v1 `ReconciliationRequired` 전부·`CompensationExecuted` 중 result ≠ SUCCESS), `paymentEvents`(order.payment.v1 `PaymentMismatch` 전부·`PaymentResultUnknown` 중 escalated=true). 앞 셋 → RECONCILIATION, 결제 불명 → PAYMENT_UNKNOWN. 정상 보상·조회 중 결제 불명은 버린다.
  - 주문(없으면 사가·결제 시도) + 종류마다 열린 사건 하나(dedupe `<종류>:<대상>`). 재발은 RECURRED 이력, 결제 불명 반복(15분마다)은 이력도 없음. refs = orderId·sagaId·paymentAttemptId·resourceId, 금액·상세 없음.
  - 팀 ORDER_OPS = **OPERATOR_ADMIN 만** 보고 집고 닫는다(RIDER_OPS·CS_AGENT 에게는 404). 기한 `pmart.cases.sla.[RECONCILIATION]`·`[PAYMENT_UNKNOWN]` PT1H(표시만).
  - **사건은 알림·기록, 조치는 처리 대기(BO-12, backoffice-bff)·각 메뉴** — 겹치지 않게. 업무 거절로 멈춘 사가(ESCALATED)는 사건으로 열지 않는다(처리 대기에 보인다, 알리는 사건도 아직 없음).
- **배차 실패(2026-11-16, 계약 0.5.0, V5 — 사용자: 중간안·지원센터·10분)**: `CaseInboxService.onDispatchFailed`·`CaseRules.dispatchFailureOpens`. 구독은 기존 `deliveryEvents`(delivery.delivery.v1 — delivery-dispatch 도 같은 토픽)에 `DispatchFailed` 를 더함(설정 바인딩 변경 없음).
  - 표 `dispatch_failure_watch` 에 배송별 실패 횟수를 센다(이벤트 `attempts` 는 재배차 시도마다 다시 세서 못 쓴다). reason `SYSTEM_FAILURE`(자동 재배차 없음)는 첫 실패에, 그 밖은 두 번째 실패부터 `DISPATCH_FAILED` 사건. 배송마다 열린 사건 하나, 그 뒤 실패는 RECURRED 이력. 닫은 뒤 다시 실패하면 바로 새 사건(이미 두 번 넘음).
  - 배송 쪽 종류(DELIVERY_OPS) — RIDER_OPS 가 집고, CS_AGENT 는 보기, 기한 10분(표시만). 배차가 뒤에 성공해도 자동으로 닫지 않는다.

- **문의를 주문으로 찾기(2026-11-16, 계약 0.5.0, V6 색인)**: `GET /inquiries?orderId=`(완전 일치) — 백오피스 통합 조회(bff)가 쓴다. 권한은 목록 그대로(고객 본인·CS_AGENT·OPERATOR_ADMIN).

- **0.5.1(2026-11-16)**: 사건 목록 `state=CLOSED` 는 닫힌 시각 최근 순(열린 것은 그대로 P1·기한·오래된 것) — 통합 조회의 "지난 사건 최근 20건"·사건함 "닫힌 것" 탭.

- **센터 관리자(2026-11-16, 계약 0.5.2 — 사용자 "추천대로", 배송 원문 §3.6)**: `MFC_SUPERVISOR` 만 가진 직원은 자기 센터(토큰 centers)의 `DISPATCH_FAILED` 만 보기(`CaseRules.centerScoped`, 목록 `Filter.centers`, 단건은 다른 센터면 404). 집기·닫기 없음. 사건 센터 = `DispatchFailed.mfcId`(dispatch 가 싣기 전엔 null → 안 보임 — 요청 `service-requests/delivery-dispatch-service.md`).

- **재개·이전 사건 연결(2026-11-16, 계약 0.6.0 — 사용자 "1번 + 이전 사건 연결", 업무 원문 §5.3·BO-27)**: `CaseInboxService.reopen`·`withPrevious`, `CaseStore.reopen`·`latestClosed`. 마이그레이션 없음.
  - `POST /cases/{id}/reopenings`(reason 필수): **사람이 손으로만**(자동 재개 없음 — 근거가 뒤집혔는지는 사람이 판단), 닫기와 같은 사람(`CaseRules.canWork` — 그 종류 팀·관리자, CS 는 403), 기간 제한 없음. CLOSED → REOPENED, 담당 비움(팀 공용 목록), 처리 기한은 지금부터 그 종류 기본값, 마지막 종결 내용(resolution·closureSummary)은 남김. 이력 REOPENED(사유 — 로그 금지), `CaseReopened`. 닫히지 않음 409 INVALID_STATE, 같은 dedupe_key 로 열린 사건이 있으면(열린 사건 유일 색인 V4) 409 CASE_ALREADY_OPEN.
  - 새 사건을 열 때(구독·오배송 모두) 같은 종류·같은 subjectRef 의 가장 최근 닫힌 사건을 refs `previousCaseId` 로 잇는다(기간 값 없음).

- **이관(2026-11-16, 계약 0.7.0 — 사용자 "A안", 업무 원문 §5.3 "넘긴 의무는 이관 완료/상대 처리 중으로")**: `CaseInboxService.transfer`, `CaseRules.TRANSFER_GROUPS`·`transferable`, `CaseStore.transfer`. 마이그레이션 없음.
  - `POST /cases/{id}/transfers`(toGroup·reason 필수): **배송 쪽 사건만** `DELIVERY_OPS`(지원센터) ↔ `ORDER_OPS`(관리자 팀). 사가·결제 사건·그 밖 팀·toCenterId·mergeIntoCaseId 는 422 UNSUPPORTED_ACTION. 같은 팀·닫힌 사건 409 INVALID_STATE.
  - 담당 비움(ASSIGNED → OPEN), 기한·보이는 범위 그대로. 이력 TRANSFERRED(`from → to: 사유`), `CaseTransferred`(fromGroup·toGroup·reason).
  - **`CaseRules.canWork(roles, kind, group)`** — 집기·닫기·재개·이관 모두 지금 팀 기준: 관리자는 모두, RIDER_OPS 는 배송 쪽 사건이 ORDER_OPS 가 아닐 때만. 남이 맡은 사건 이관은 관리자만(닫기와 같음).

- **의무(2026-11-16, 계약 0.8.0, V7 — 사용자 "고친 안으로 진행해 줘", 업무 원문 §2 Obligation·§5.3)**: `ObligationService`·`domain/ObligationRules`·`ObligationStore`(표 `case_obligation`·`case_obligation_resolution`), `ObligationsController`.
  - 등록 `POST /cases/{id}/obligations`: 사건을 다룰 수 있는 사람(`canWork`), 열린 사건만(닫힘 409), **8종만**(재고 회수·폐기·공급사 청구 422 UNSUPPORTED_KIND — 담당 팀 없음), owner = DELIVERY_OPS·ORDER_OPS·CS(비우면 사건 팀) — **담당 팀이 그 사건을 볼 수 있어야**(지원센터·상담원은 배송 쪽 사건만, 아니면 422 OWNER_CANNOT_SEE_CASE). 수량·금액 따로 기록만, 기한 표시만, 자동 생성 없음.
  - 해소 `POST /obligations/{id}/resolutions`(evidence system·ref 필수 — 확인하지 않고 기록): FULFILLED·ATTEMPTED 는 담당 팀(DELIVERY_OPS = RIDER_OPS, CS = **CS_AGENT** — 상담원의 첫 쓰기 권한, ORDER_OPS = 관리자)·관리자, **WAIVED·FAILED 는 관리자만**(note 필수). ATTEMPTED 는 시도 기록(note 필수, 상태 그대로, ObligationResolved 없음). 부분 해소 — 의무에 있는 수량·금액만 받고(없는 값·빠진 값 400) 넘으면 409 QTY_EXCEEDS_OBLIGATION, 모두 채우면 RESOLVED. 끝난 의무 409.
  - 목록 `GET /obligations?owner=&state=&caseId=`(볼 수 있는 사건의 것만, 기본 열린 것, 기한·오래된 순), 단건(사건을 못 보면 404). 사건 상세 obligations·openObligationCount, 목록 openObligationCount.
  - **닫기는 열린 의무(OPEN·PARTIALLY_RESOLVED)가 있으면 409 OPEN_OBLIGATIONS**. 이력 OBLIGATION_CREATED·_ATTEMPTED·_RESOLVED, 이벤트 `ObligationCreated`·`ObligationResolved`(aggregate Obligation). 메모·근거는 표·이력에만(로그 금지).

## 다른 서비스의 요청
- MFC·주문 이행: `docs/architecture/service-requests/case-service.md`(R-1 결품 CS 이관 — ADR-0049 로 취소됨; R-2 진행 중 주문의 고객센터 취소, 2026-11-08). 구현 때 그 문서의 "보충 목록" 도 같이.
