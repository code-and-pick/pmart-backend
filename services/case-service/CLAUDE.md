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
  - 아직: 이관·재개·의무·행위 요청(501).
- **사가·결제 이상(2026-11-16, 계약 0.5.0 — R-3, 사용자: 기한 1시간·"이상만"·멈춘 사가는 지금 안 함)**: `CaseInboxService.onOrderException`·`CaseRules.orderExceptionKind`. 마이그레이션 없음(같은 표).
  - 구독 `sagaEvents`(order.saga.v1 `ReconciliationRequired` 전부·`CompensationExecuted` 중 result ≠ SUCCESS), `paymentEvents`(order.payment.v1 `PaymentMismatch` 전부·`PaymentResultUnknown` 중 escalated=true). 앞 셋 → RECONCILIATION, 결제 불명 → PAYMENT_UNKNOWN. 정상 보상·조회 중 결제 불명은 버린다.
  - 주문(없으면 사가·결제 시도) + 종류마다 열린 사건 하나(dedupe `<종류>:<대상>`). 재발은 RECURRED 이력, 결제 불명 반복(15분마다)은 이력도 없음. refs = orderId·sagaId·paymentAttemptId·resourceId, 금액·상세 없음.
  - 팀 ORDER_OPS = **OPERATOR_ADMIN 만** 보고 집고 닫는다(RIDER_OPS·CS_AGENT 에게는 404). 기한 `pmart.cases.sla.[RECONCILIATION]`·`[PAYMENT_UNKNOWN]` PT1H(표시만).
  - **사건은 알림·기록, 조치는 처리 대기(BO-12, backoffice-bff)·각 메뉴** — 겹치지 않게. 업무 거절로 멈춘 사가(ESCALATED)는 사건으로 열지 않는다(처리 대기에 보인다, 알리는 사건도 아직 없음).

## 다른 서비스의 요청
- MFC·주문 이행: `docs/architecture/service-requests/case-service.md`(R-1 결품 CS 이관 — ADR-0049 로 취소됨; R-2 진행 중 주문의 고객센터 취소, 2026-11-08). 구현 때 그 문서의 "보충 목록" 도 같이.
