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
  - 아직: 행위 요청(501). CS 접수는 0.12.0. 재개 0.6.0, 이관 0.7.0, 의무 0.8.0, 교대 0.9.0. **도움요청 접수 `/help-requests` 는 만들지 않음(0.11.1 계약에 사용 안 함 — R-4 로 delivery-execution 이 받음)**.
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
- **오배송 회수 진행(2026-11-16, 계약 0.8.1 — 사용자 "진행해", DD-2 연결)**: `CaseInboxService.onRecoveryProgress`. 기존 `deliveryEvents`(delivery.delivery.v1)에 `DeliveryCreated`·`ReturnPickedUp`·`ReturnDelivered` 를 더함(설정 변경 없음) — **purpose MISDELIVERY_RECOVERY + caseId 만**(일반 배송·고객 반품 회수는 버림, 사건이 없으면 로그만). 오배송 사건 refs 에 `recoveryDeliveryId`(한 번만), 이력 RECOVERY_CREATED·RECOVERY_PICKED_UP·RECOVERY_RETURNED. **상태 그대로 — 자동 종결 없음, 후속 처리(의무) 자동 해소 없음**(사람이 이력을 보고 처리).

- **교대 인수인계(2026-11-16, 계약 0.9.0, V8 — 사용자 "진행해 줘", 업무 원문 BO-27 최대한 그대로)**: `ShiftHandoverService`·`ShiftHandoverStore`(표 `shift_handover`·`shift_handover_item`), `IntakesController`(도움요청 접수는 만들지 않음 — CS 접수는 0.12.0), `CaseInboxService.handOver`.
  - 작성 `POST /shift-handovers`: 전임 본인(또는 관리자 — 전임 부재, fromOperatorId), group DELIVERY_OPS·ORDER_OPS 필수·centerId 선택, 건마다 **다음 조치 필수**·확인 기한 선택. 건은 열려 있고 **전임이 맡았고** 그 group 이어야(422 NOT_HANDOVERABLE), 다른 진행 중 인계서에 든 건 409 CASE_IN_HANDOVER(부분 유일 색인). 담당은 아직 그대로, 이력 HANDOVER_REQUESTED, 팀 열린 건 스냅샷(teamSnapshot ≤200). **역할(0.13.1 — 2026-11-17 사용자 (나))**: DELIVERY_OPS = RIDER_OPS·관리자, ORDER_OPS = 관리자만(그 밖 403) — `CaseRules.canHandOver`. 후임도 같은 규칙을 수락 때 검사(없으면 403, 인계서 PENDING 그대로 — 작성 때는 후임 역할을 모름).
  - 수락 `…/acceptances`: **후임 본인만**(관리자도 못 함), excludedCaseIds 는 EXCLUDED(전임 그대로 — 미인수). 그사이 바뀐 건 SKIPPED_CHANGED, 후임이 못 다루면 SKIPPED_NOT_ALLOWED, 나머지 ACCEPTED(담당 이동·이력 HANDED_OVER "전임 → 후임: 다음 조치"·CaseAssigned). COMPLETED + `ShiftHandoverCompleted`(openCases = 받은 건, notReceivedCases).
  - 취소 `…/cancellations`(수락 전, 전임·관리자 — 항목 CANCELLED 로 풀림), 조회·목록(전임·후임·관리자, `unreceived=true` = 수락 전이거나 빠진 건 있음 — 원문 "미인수 상신"). 기한 값 없음, **근무 확인 없음**(본사 근무표 없음 — 역할만), 알림 없음.

- **접수 건 메모(2026-11-16, 계약 0.10.0, V9 case_note — 사용자 "고친 안", 업무 원문 BO-27 "늦은 보고는 원사건으로")**: `CaseNoteService`·`CaseNoteStore`, `CasesController`. 남기기 `POST /cases/{id}/notes` — 볼 수 있는 **본사 직원**(OPERATOR_ADMIN·RIDER_OPS·CS_AGENT, 센터 관리자는 403), **처리 끝난 건에도**(상태 그대로), 500자. 고치기·지우기 없음. 가림 `…/notes/{noteId}/maskings` — 관리자만·사유 필수, **글을 DB 에서 지우고**(text NULL) 누가·언제·왜는 남김(ADR-0062). 목록 GET. 이벤트 없음, 글은 로그 금지.

- **오배송 재발송 연결(2026-11-16, 계약 0.11.2 — 사용자 "진행해 줘", PC 5·PC 1 재발송 #39~#41 과 잇기)**: `CaseInboxService.onReshipmentCreated`(`onRecoveryProgress` 에서 나눔). 배송 토픽 `DeliveryCreated` 중 purpose **MISDELIVERY_RESHIPMENT** + caseId → 오배송 사건 refs `reshipmentDeliveryId`(한 번만)·이력 RESHIPMENT_CREATED, 상태 그대로. 재발송 작업 사건(mfc.fulfillment.v1 — WorkAccepted·LineShortageReported 등, caseId 실림)은 구독하지 않는다(바인딩 변경 필요) — 진행은 bff 사건함의 재발송 배송 상태로 본다.

- **CS 접수(2026-11-16, 계약 0.12.0, V10 cs_intake — 사용자 "그래 일단 그대로 진행해", BO-11)**: `CsIntakeService`·`domain/CsIntakeRules`·`CsIntakeStore`, `IntakesController`. 상담원(CS_AGENT)·관리자가 전화·채팅·메일 연락을 남긴다(PHONE·CHAT·EMAIL 만, 연동 없음). **접수 ≠ 명령**(취소·환불 안 함, 동의는 기록만).
  - 회원만(customerRef 필수). subjectRef(주문)를 주면 order `GET /orders/lookups/{id}` 를 직원 토큰으로(`OrderClient.staffOrder` — customerId 만 읽음) → 없음 422 ORDER_NOT_FOUND, 다른 고객 422 CUSTOMER_ORDER_MISMATCH.
  - **본인 확인 방법 필수**(ORDER_DETAILS_MATCH — 주문 필요, APP_SIGNED_IN_CHAT — CHAT 만, OTHER — note 필수). 고객이 말한 값은 저장 안 함. **대리(PROXY)는 표시만** — 클레임을 막지 않는다(대리 정책 없음, 화면 경고).
  - 새 건 → 문의(origin **STAFF_INTAKE**): 고객 목록에서 빠지고 고객 상세 404, 답변해도 **InquiryAnswered 없음**. linkCaseId → 그 건(사건함에서 볼 수 있는 건 또는 문의 건)에 연결, 이력 CS_INTAKE_LINKED, 새 사건 없음.
  - 목록 `GET /cs-intakes?caseId|customerRef|subjectRef`(하나 이상), 문의 목록·상세에 channel·callerRelation(LEFT JOIN cs_intake). 이벤트 없음, 요약·메모 로그 금지.

- **문의 사진(2026-11-17, 계약 0.14.0, V12 inquiry_photo — 사용자 "리뷰 방식 그대로", 업무 원문 Commerce §14.1)**: `InquiryPhotoService`·`InquiryPhotoStore`·`InquiryPhotosController`, 저장소 `InquiryPhotoStorage`(S3 호환 — `pmart.storage.s3.*`, 버킷 없으면 사진만 503, review-service 와 같은 방식).
  - 올리기 `POST /inquiry-photos`(multipart, 문의를 남기는 고객·라이더만, JPEG·PNG·WEBP 앞부분 판정, 10MB, 멱등) → 문의 작성 `photoIds` 또는 보완 `POST /inquiries/{id}/photos`(남긴 본인, 닫힌 문의 409, 합쳐 5장, 상태·version 그대로). 남의·이미 붙은 사진 422 PHOTO_NOT_FOUND(트랜잭션째 되돌림).
  - 본문 `GET /inquiry-photos/{id}`: 붙은 사진 = `InquiryService.get` 과 같은 규칙(본인·종류별 직원), 안 붙은 사진 = 올린 본인만, 그 밖 404(역할 없는 직원 403). private 캐시. 안 붙은 사진 하루 뒤 정리(`InquiryPhotoSweeper`, 1시간마다 — `@EnableScheduling`). STAFF_INTAKE 문의에는 못 붙임. 사진 내용·이름 로그 금지.
- **문의 만족도(2026-11-17, 계약 0.15.0, V13 inquiry_satisfaction — 사용자 "답변 뒤 1~5점 + 한 줄", 업무 원문 Commerce §14.2)**: `InquirySatisfactionService`·`InquirySatisfactionStore`, `InquiriesController`.
  - 남기기 `POST /inquiries/{id}/satisfaction`(score 1~5, comment ≤200): 남긴 고객 본인만(라이더·직원 403, 남의·STAFF_INTAKE 404), 답변 전 409 INQUIRY_NOT_ANSWERED, 한 번만 409 ALREADY_RATED(고치기 없음). 상태·version 그대로, 이벤트·알림 없음, 의견 로그 금지.
  - **상담원 평가·제재에 쓰지 않는다** — 문의 상세 `satisfaction` 은 본인·OPERATOR_ADMIN 에게만(상담원·지원센터에게 없음). 모아 보기 `GET /inquiries/satisfaction-summary?answeredFrom&answeredTo`(관리자만, 92일까지): 답변된 고객 문의 수(분모)·남긴 수(분자)·점수 합·1~5 분포·평균(남긴 수 0 이면 null — BO-28). 상담원별로 나누지 않는다.

## 다른 서비스의 요청
- MFC·주문 이행: `docs/architecture/service-requests/case-service.md`(R-1 결품 CS 이관 — ADR-0049 로 취소됨; R-2 진행 중 주문의 고객센터 취소, 2026-11-08). 구현 때 그 문서의 "보충 목록" 도 같이.
- **오배송 귀책·고객 선택(2026-11-16, 계약 0.11.0 — ADR-0065, 작성 PC 5 — 백오피스 영역 PC 2 검토)**: `POST /cases` MISDELIVERY 에 `misdelivery`(fault CUSTOMER·RIDER·SYSTEM, resolution — RIDER·SYSTEM: RESHIP 기본·REFUND, CUSTOMER: REDELIVER 기본·REFUND, redeliveryDestinationId — CUSTOMER·REDELIVER 필수: order 에 기록한 실제 주소 ID). 검사 `CaseInboxService.misdeliveryDecision`(422 INVALID_MISDELIVERY_RESOLUTION·REDELIVERY_DESTINATION_REQUIRED). 사건 refs(fault·resolution·redeliveryDestinationId)와 `MisdeliveryConfirmed` 에 싣는다. 보내지 않으면 예전 처리(회수 + 재발송, 라이더 배달료 지급) — 백오피스 오배송 기록 화면이 귀책·선택·실제 주소를 받게 바뀌어야 한다(클라이언트 handoff).

- **라이더 문의(2026-11-16, 계약 0.13.0, V11 — 라이더 앱 요청 §11-4, 사용자 "추천대로", 작성 PC 5)**: `/inquiries` 를 라이더 토큰에도 연다. `cs_inquiry.requester_type`(CUSTOMER·RIDER, customer_id 열 = 남긴 주체), 사건 종류 RIDER_INQUIRY(사건함·교대 스냅샷 밖). 분류 `InquiryRules.RIDER_CATEGORIES`(SETTLEMENT·ACCOUNT·APP_ERROR·OTHER — 고객은 예전 7개, 서로 쓰면 400), 라이더는 orderId 400. 보기 `InquiryRules.readableTypes`(CS_AGENT 고객·RIDER_OPS 라이더·관리자 전부), 답하기 `answerRole`(라이더 문의 = RIDER_OPS — 사건함 결정의 지원센터). 라이더 문의 답변은 고객용 `InquiryAnswered`(customerId 필수) 대신 `RiderInquiryAnswered`(inquiryId·caseId·riderId·answeredAt — 라이더 알림함, ADR-0066). 직원 목록 `requesterType` 거르기, 고객 문의를 못 보는 직원의 `orderId` 거르기는 403(통합 조회 그대로).

- **라이더 문의(2026-11-16, 계약 0.13.0, V11 — 라이더 앱 요청 §11-4, 사용자 "추천대로", 작성 PC 5)**: `/inquiries` 를 라이더 토큰에도 연다. `cs_inquiry.requester_type`(CUSTOMER·RIDER, customer_id 열 = 남긴 주체), 사건 종류 RIDER_INQUIRY(사건함·교대 스냅샷 밖). 분류 `InquiryRules.RIDER_CATEGORIES`(SETTLEMENT·ACCOUNT·APP_ERROR·OTHER — 고객은 예전 7개, 서로 쓰면 400), 라이더는 orderId 400. 보기 `InquiryRules.readableTypes`(CS_AGENT 고객·RIDER_OPS 라이더·관리자 전부), 답하기 `answerRole`(라이더 문의 = RIDER_OPS — 사건함 결정의 지원센터). 라이더 문의 답변은 `InquiryAnswered`(customerId 필수 — 고객 알림)를 내지 않는다. 직원 목록 `requesterType` 거르기, 고객 문의를 못 보는 직원의 `orderId` 거르기는 403(통합 조회 그대로).

- **배송 신고(2026-11-17, 계약 0.16.0, V14 — 사용자 결정 "기존 문의로, 배송 연결", 작성 PC 5)**: 고객 `POST /inquiries` 에 `deliveryIssue`(DELAYED·NOT_RECEIVED·MISDELIVERED)·`deliveryId`(선택, 참고값 — 주문 소유만 확인, 배송과 주문이 맞는지는 보지 않음). category DELIVERY·orderId 가 있는 고객 문의에서만(아니면 400). 응답·목록에 같은 값. 받는 사람은 지금처럼 상담원(CS_AGENT) — 사건함 RIDER_HELP 와는 따로.
