# case-service 에 대한 요청 (MFC·주문 이행 쪽에서)

case-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다. 구현을 시작할 때 아래를 함께 넣는다. 요청한 쪽이 지금 임시로 어떻게 버티는지와, case-service 가 생기면 그쪽에서 바꿀 것을 같이 적는다.

## R-1. ~~결품 "선택 없음" 주문의 CS 이관~~ — 취소됨 (2026-11-08 ADR-0049: 결품은 무조건 부분 취소·환불, 상신 없음)

아래는 기록으로 남긴다. 결품 때문에 case-service 가 할 일은 지금 없다.


- **배경**: 피킹 중 결품(`LineShortageReported`)과, 피킹 시작 때 남아 있는 예약 보류분(같은 사건, reason `BLOCKED`)은 결품 사가(order-orchestration, ADR-0045)가 고객 사전 선택대로 처리한다. 선택이 `품절 상품만 취소`·`전체 취소`면 자동이다. **선택이 없거나 불명이면** 원문(OMS 8.2)은 "출하를 보류해 고객센터로 이관"이다.
- **지금(임시)**: 결품 사가가 `ESCALATED`(사유 `NO_PREFERENCE`)로 멈춘다. PICK 작업은 BLOCKED, 이행부족은 BLOCKED 조각으로 남는다(약속 보존). 운영자 수동 처리 화면(BO-12)도 없다 — 사람이 사가 조회(`GET /internal/sagas?orderId=`)·주문 "이행 확인 필요" 목록(order `GET /fulfillment-issues`)으로 찾는다.
- **case-service 에 필요한 것**
  1. 사건 접수: `LineShortageReported` 중 결품 사가가 상신한 건(또는 orchestration 이 case 에 직접 `POST` — 둘 중 하나로 결정)을 케이스로 연다(`CaseOpened`, 유형 `FULFILLMENT_SHORTAGE`, 주문·주문 줄·SKU·결품 수량·사유·MFC).
  2. 상담원이 고객과 통화한 결과를 기록: `품절 상품만 취소` / `전체 취소`(대체 상품은 원문상 금지 — 선택지에 넣지 않는다).
  3. 결과를 결품 사가에 돌려준다 — 사가가 그 선택으로 이어서 처리(부분 취소·환불 또는 전체 취소). 사가 쪽 입력 계약(예: `POST /internal/sagas/{id}/shortage-decisions`)은 함께 정한다.
  4. 응답 시간 제한(예: 배송 약속 슬롯 시작 − N분까지 결정 없으면) 처리 정책은 **사용자 결정 필요**.
- **case-service 가 생기면 요청한 쪽에서 바꿀 것**: 아래 "MFC·주문 쪽 보충 목록" R-1.

## R-2. 진행 중 주문의 고객센터 취소 (2026-11-08, 사용자: "중도 고객 취소는 아주 예외 — 고객센터를 통해서만 제한적으로 허용")

> **거둠(2026-11-13, ADR-0060)** — 사용자 "고객센터 취소 못하는 걸로 정할 거야"(범위: 상품준비 뒤 ~ 인계 전). 만들지 않는다. 인계 뒤 회수 요청(D-7)은 claim CL-1 쪽.

- **지금**: 고객 자율 취소는 상품 준비 시작(PreparationStarted, 주문 PREPARING) 전까지만(claim `CancelRules` — CONFIRMED). 준비 시작 뒤에는 시스템 취소(결품·작업 거절)만 있고, **고객센터가 고객 요청으로 취소하는 경로는 없다**.
- **case-service 에 필요한 것**: 상담원이 케이스에서 "준비 중 주문 취소"를 요청 → claim 에 운영자 취소(channel CS, 사유·근거 필수, 권한 있는 상담원만) → 기존 S3(현장 중지 → 집품분 취소 복귀 → 주문 취소 → 환불). 허용 조건(어느 단계까지·누가 승인)은 **사용자 결정 필요**.
- **현장 쪽은 준비됨**: 집품 뒤 전체 중지·취소 복귀(IV-08), 인계 허가가 만료됐지만 확인이 없는 포장은 점원 확인까지 중지 PENDING(D-1 안 C). 이미 인계된 포장이 있으면 전체 중지는 거절(회수는 별도).

## MFC·주문 쪽 보충 목록 (case-service 구현 때 같이)
| # | 서비스 | 바꿀 것 |
|---|---|---|
| R-1 | order-orchestration | 결품 사가 `NO_PREFERENCE` 상신 → case 에 케이스 열기 + 케이스 결정 받으면 그 선택으로 계속 |
| R-1 | mfc-fulfillment | 없음(BLOCKED 유지 → 사가 결과로 shortage-resolutions·작업 중지가 온다) |
| R-1 | order | "이행 확인 필요" 에 케이스 ID 연결(선택) |
| R-2 | claim | 운영자(고객센터) 취소 명령 — channel CS, PREPARING 허용, 권한·사유 검사 |
| R-2 | order-orchestration | 없음(기존 S3) |

## 사건함 공통 결정 (사용자 2026-11-16 "너 추천대로")
- **유형(처음 다섯)**: 라이더 도움요청 `RIDER_HELP`·전달 실패 `DELIVERY_FAILURE`(R-4), 위치 끊김 `RIDER_LOCATION_STALE`(R-6), 오배송 확인 `MISDELIVERY`(R-7), 사가·결제 이상 `RECONCILIATION`·`PAYMENT_UNKNOWN`(R-3 — 계약 CaseKind 에 있던 이름을 씀, 2026-11-16 구현). 우선순위는 유형·category 로(사고 ACCIDENT 긴급).
- **담당**: 유형별 팀 — 앞 넷은 **`RIDER_OPS`(지원센터 겸함, 2026-11-16 결정)**, 사가·결제 이상은 `OPERATOR_ADMIN`. 배정은 **팀 공용 목록에서 직원이 집어 간다**(자동 배정 없음 — 집으면 담당자 기록).
- **처리 기한**: 유형별 기한을 **표시만** 하고 넘긴 건은 목록에서 강조. 알림(Slack 등)은 나중. **값 확정(사용자 2026-11-16 "그대로 할게")**: 도움요청 10분·위치 끊김 10분·전달 실패 30분·오배송 확인 30분(설정 `pmart.cases.sla`). 사가·결제 이상(R-3)은 **1시간**(사용자 2026-11-16 "한시간으로").

## R-3. 사가·결제 상신 받기 (2026-11-09 기록)

- **요청한 곳**: order-orchestration, payment.
- **지금**: 아래 사건은 **실제로 발행되지만 아무도 구독하지 않는다**. 사람이 사가 행 `last_error`·`GET /internal/sagas` 로 찾는다.
- **case-service 가 할 것**
  1. `ReconciliationRequired`·`CompensationExecuted`(orchestration), `PaymentResultUnknown`(장기 불명)·`PaymentMismatch`(payment) 구독 → 케이스 열기.
  2. 사가 ESCALATED(S1·S3·결품 사가의 업무 거절) 상신 경로 — 사건으로 받을지 직접 호출로 받을지 함께 정한다.
- **사용자 결정**: 문서에 없음 — 케이스 유형·담당·처리 기한은 case 구현 때 질문.
- 근거: `docs/architecture/events.md`, `docs/business/flows/cancel-vs-handover.md`, `services/order-orchestration-service/CLAUDE.md`(상태).
- **구현됨(2026-11-16, 보낸 영역 backoffice — case 계약 0.5.0, PR 대기)**. 사용자 결정(같은 날): 처리 기한 **1시간**(표시만), 받을 범위 **"이상만"**, 업무 거절로 멈춘 사가(ESCALATED)는 **지금은 사건으로 열지 않음**(처리 대기 BO-12 에 보인다 — 2번 항목은 하지 않음).
  - `ReconciliationRequired` 전부·`CompensationExecuted` 중 result ≠ SUCCESS·`PaymentMismatch` 전부 → `RECONCILIATION`, `PaymentResultUnknown` 중 escalated=true → `PAYMENT_UNKNOWN`. 담당 OPERATOR_ADMIN(팀 ORDER_OPS) 만.
  - 주문마다 같은 종류 열린 사건 하나(재발은 이력). 사건은 알림·기록, 조치는 처리 대기 버튼(BO-12)·각 메뉴.
  - **PC 1 이 할 것**: 설정 저장소 `case-service.yml` 에 바인딩 `sagaEvents-in-0`(order.saga.v1)·`paymentEvents-in-0`(order.payment.v1), `spring.cloud.function.definition` 에 두 함수, `pmart.cases.sla."[RECONCILIATION]"`·`"[PAYMENT_UNKNOWN]"` PT1H — 시험 사본(`services/case-service/src/integrationTest/resources/pmart-config/case-service.yml`)과 같게. 배포 전 소비 그룹 case-service 의 두 토픽 시작 위치 latest(지난 이상은 처리 대기에 이미 보인다).

## R-4. 라이더 도움요청·전달 실패 등록 → 지원센터 사건 (2026-11-13, 보낸 영역 delivery — 사용자 결정 "1번")

- **요청한 곳**: delivery-execution-service(PC 5).
- **사용자 결정(2026-11-13)**: 라이더 도움요청은 delivery-execution 이 받아 **자기 DB 에 기록하고 사건 `DeliveryIncidentRaised` 를 낸다**. case-service 가 그 사건을 받아 지원센터 사건으로 만든다 — "나중에 case-service 가 생겨도 이벤트만 받으면 되니 다시 고칠 것이 적다". (같은 모양의 구현 사례: hr-service `GrantRequested` → approval-service `intakeGrantRequest`.)
- **지금(구현됨, execution 계약 0.3.0)**
  - `POST /api/v1/deliveries/{id}/help-requests`(라이더) → `help_request` 기록 + `DeliveryIncidentRaised`(토픽 delivery.delivery.v1, 집계 Delivery·키 deliveryId). payload: `incidentId`(= `<deliveryId>:<도움요청 ID>` — 도움요청 ID 는 앱 Idempotency-Key 라 배송마다만 유일, 2026-11-14), `deliveryId`, `riderId`, `type: HELP_REQUEST`, `category`(CUSTOMER_UNREACHABLE·ADDRESS_ISSUE·GOODS_ISSUE·PHOTO_UPLOAD_FAILED·ACCIDENT·APP_ERROR·STORE_WAITING·STATUS_CORRECTION·OTHER), `stage`(요청 때 배송 수행 상태), `orderId`, `mfcId`, `detail`(라이더 글 — 개인정보가 들어갈 수 있으니 로그 금지), `evidenceRefs`(execution 사진 photoRef), `raisedAt`.
  - 전달 실패 등록(`POST …/failures`)도 같은 사건을 낸다 — `category: DELIVERY_FAILURE`, `stage: FAILURE_REPORTED`, `evidenceRefs` = 실패 사진. 배송은 운영자 확정 전까지 진행 중이다(확정·반려는 지금 `POST …/failure-decisions` — 백오피스 권한자).
  - 라이더 조회 `GET …/help-requests/{id}` 의 `caseId` 는 비어 있고 state 는 OPEN 으로 남는다(사건을 받는 곳이 없다). 지원센터 화면도 없다.
- **case-service 에 필요한 것**
  1. 구독 `deliveryEvents`(delivery.delivery.v1, `EventConsumers.of(e -> "DeliveryIncidentRaised".equals(e.eventType()), …)` — 같은 토픽의 다른 배송 사건은 버린다) → `InboxGuard` 안에서 지원센터 사건 열기(유형 예 `RIDER_HELP` / `DELIVERY_FAILURE`, 참조 deliveryId·orderId·incidentId, 우선순위는 category 로 — 예 ACCIDENT 높음). 같은 incidentId 는 한 번.
  2. 사건을 열면 `CaseOpened`(refs 에 `{refType: "incidentId", refId}`·`{refType: "deliveryId", refId}`) — delivery-execution 이 받아 도움요청에 caseId 를 연결하고 state 를 IN_PROGRESS 로(그쪽 구현은 case-service 가 생길 때 delivery 영역이 한다). 사건 종료(`CaseClosed`) → RESOLVED.
  3. 지원센터 화면에서 전달 실패 확정·반려 — 결정은 delivery-execution `POST /api/v1/deliveries/{id}/failure-decisions`(CONFIRM·REJECT, note, custody)를 부른다(사건을 정본으로 두고 결정만 execution 에). **지원센터 역할 = `RIDER_OPS` 가 겸한다**(사용자 2026-11-16 — 따로 두지 않음). execution 권한 검사(`Access.allCenters` — OPERATOR_ADMIN·CS_AGENT·RIDER_OPS, PR #17)에 이미 들어 있다.
  4. 고객 연락(안심번호 5시간·시도 기록 — 원문 §9.1), 대체 장소 승인, 회수·취소 결정은 원문대로 case 쪽 — 결정 결과를 어디로 보낼지(claim 회수 승인 등)는 case-service 설계 때.
- **case-service 가 생기면 요청한 쪽(delivery-execution)에서 바꿀 것**: `CaseOpened`·`CaseClosed` 구독 → `help_request.case_id`·state 갱신(지금 테이블·조회 칸은 있다).

## R-5. 라이더 컴플레인 기록 → `RiderComplaintRecorded` 발행 (2026-11-14, 보낸 영역 delivery — RS-3)

- **사용자 결정(2026-11-14)**: 라이더에 대한 컴플레인 — **신고·오배송·파손 전부** — 이 **같은 종류로 7일 안 3건 이상** 쌓이면 시스템이 제한 검토 대상으로 올리고, 백오피스 운영자가 확인 뒤 계정을 제한한다(자동 제재 없음).
- **받는 쪽(구현됨, delivery PC 5 — rider-service 0.3.0)**: `RiderComplaintRecorded`(계약 `contracts/events/RiderComplaintRecorded.v1.json`, 토픽 platform.case.v1, aggregateType Case)를 받아 센다 — 종류마다 서로 다른 배송 3건, 같은 `complaintId` 는 한 번. 검토 목록·닫기는 rider `GET /riders/restriction-reviews`·`POST …/{reviewId}/decisions`.
- **case 가 할 것**: 컴플레인을 사건으로 기록할 때(고객 앱 신고·상담원 접수·오배송·파손 확정) 한 건마다 발행 — `complaintId`(한 건 ID), `riderId`(그 배송 수행 라이더 — dispatch `GET /internal/deliveries/{id}/assignment` 로 채움), `deliveryId`, `complaintType`(REPORT 신고 / MISDELIVERY 오배송 / DAMAGE 파손), `recordedAt`, 있으면 `caseId`·`orderId`·`source`. 내용·고객 정보는 싣지 않는다.
- **정할 것(case 구현 때)**: 고객 신고 접수 화면·API(지금 case 에는 일반 문의 `/inquiries` 만), 오배송·파손을 "확정"할 때 낼지 접수할 때 낼지. 사용자 결정은 "신고 포함 전부"라 접수 때 내도 된다(검토는 운영자가 한다).
- **구현됨(2026-11-15, case 계약 0.3.0, V3 — 사용자 결정 2026-11-15 "상담원이 백오피스에서 기록")**: **접수 때 낸다**(확정 단계 없음 — 검토는 rider 쪽 운영자).
  - `POST /api/v1/rider-complaints`(역할 CS_AGENT·OPERATOR_ADMIN, Idempotency-Key·X-Source-System) — 본문 `deliveryId`·`complaintType`(REPORT·MISDELIVERY·DAMAGE)·선택 `orderId`·`note`(≤1000, 개인정보 가능 — 로그·사건 금지)·`inquiryId`(문의에서 넘어왔으면 그 문의의 사건 `caseId` 를 잇는다, 없는 문의 422 INQUIRY_NOT_FOUND). 라이더는 dispatch `GET /api/v1/internal/deliveries/{id}/assignment`(`DispatchClient`, 설정 `pmart.cases.delivery-dispatch-base-url`)로 채운다 — 배정 없음 422 RIDER_NOT_ASSIGNED, 배송 없음 422 DELIVERY_NOT_FOUND, dispatch 응답 불가 503.
  - 표 `rider_complaint` 한 행 + 같은 트랜잭션 Outbox `RiderComplaintRecorded`(aggregateType Case, aggregateId = complaintId(`RCP-…`), 토픽 platform.case.v1). payload: `complaintId`·`riderId`·`deliveryId`·`complaintType`·`source: CS_AGENT`·`recordedAt`, 있으면 `caseId`(연결한 문의의 사건)·`orderId`. 값이 없는 선택 칸은 뺀다.
  - 조회 `GET /api/v1/rider-complaints?riderId=&deliveryId=&complaintType=&from=&to=&size=&cursor=`(최근 순, 쪽은 저장소 관례대로 cursor)·`GET …/{complaintId}`(메모 포함) — 역할 CS_AGENT·OPERATOR_ADMIN·RIDER_OPS. 그 밖 직원 403.
  - 코드: `RiderComplaintService`·`domain/RiderComplaintRules`·`RiderComplaintStore`·`DispatchClient`, 시험 `RiderComplaintsIT`.
  - **남은 것**: 고객 앱 신고 경로(지금은 상담원 기록만, source 는 CS_AGENT 하나), 컴플레인 자체의 사건(Case) 만들기 — 지금 caseId 는 문의에서 넘어온 때만.

## R-6. 위치 끊긴 라이더 → 지원센터 알림 (2026-11-15, 보낸 영역 delivery — TR-2 받는 쪽)
- **사용자 결정(2026-11-15 "추천대로")**: 배달 중 위치가 3분 넘게 없는 라이더(`RiderLocationStale`)는 **지원센터(운영자)에게 알림만** 한다. **배정을 자동으로 풀지 않는다**(터널·엘리베이터 등 잠깐 끊김이 흔해서) — 그래서 dispatch 는 구독하지 않는다.
- **보내는 쪽(구현됨, delivery-tracking V3)**: `RiderLocationStale`(계약 `contracts/events/RiderLocationStale.v1.json`, 토픽 delivery.tracking.v1, aggregate Rider) — riderId·deliveryId·lastSeenAt·detectedAt. 끊김 한 번에 한 번, 위치가 다시 오면 다음 끊김에 또 낸다. 상태는 바꾸지 않는다.
- **case 가 할 것**: 구독해 지원센터가 볼 곳에 올린다(사건함·알림 — 사건함이 아직이면 백오피스 알림이나 목록). 같은 라이더·배송의 끊김이 이어지면 묶어도 된다. 라이더 연락은 사람이 한다. 위치 좌표는 사건에 없다(필요하면 tracking 내부 위치 조회).

## R-7. 오배송 확인 기록 → 회수 배차 사건 (2026-11-15, 보낸 영역 delivery — DD-2 앞단)
- **사용자 결정(2026-11-15 "추천대로")**: 오배송 "확인"은 **상담원·지원센터가 확인해서 기록**한다(라이더 앱 화면만으로 확정하지 않는다 — 오판 위험). 그 기록이 들어오면 dispatch 가 근처 다른 라이더로 **회수 배차를 자동으로** 낸다(DD-2, ADR-0052).
- **case 가 할 것**: 상담원·지원센터의 오배송 확인 기록 API(배송 ID, 잘못 간 곳 — 주소·좌표는 개인정보라 사건에는 참조만, 확인 근거 사진·통화 메모 참조)와 사건 하나(예 `MisdeliveryConfirmed` — 이름·필드는 case 가 계약으로 정하고 delivery 에 알림). R-5 오배송 컴플레인(`RiderComplaintRecorded` MISDELIVERY)과는 별개 — 컴플레인은 라이더 검토용, 이 사건은 실물 회수용(같은 화면에서 둘 다 기록해도 된다).
- **delivery 가 할 것(받은 뒤)**: dispatch 회수 배송(픽업지 = 오배송지, 도착지 = MFC) 만들기·인근 라이더 탐색(DD-2), execution 회수 수행(지금 501). MFC 새 발송(원주소)은 일반 배차로 따로.

- **회수 진행 연결(2026-11-16, 보낸 영역 backoffice — case 0.8.1)**: dispatch·execution 의 회수 배송 사건(DeliveryCreated·ReturnPickedUp·ReturnDelivered, purpose MISDELIVERY_RECOVERY·caseId)을 받아 오배송 사건에 회수 배송 번호(recoveryDeliveryId)와 이력. 상태·후속 처리는 자동으로 바꾸지 않는다. delivery 쪽 할 일 없음(이미 caseId 를 싣는다).
## 배차 실패 → 사건함 (2026-11-16, 보낸 영역 backoffice — 사용자 요청 "배차 실패도 사건함으로")
- **사용자 결정(같은 날)**: 받을 범위 **중간안** — 시스템 장애(자동 재배차 없음)는 첫 실패에, 라이더 없음·반복 거절·응답 만료(10분마다 자동 재배차)는 같은 배송의 **두 번째 실패부터**. 담당 **지원센터(RIDER_OPS)**, 처리 기한 **10분**(표시만).
- 근거: 업무 원문 Delivery §3.6(배차 지연 시 MFC 관리자·고객센터 안내 — 상담원이 배송 쪽 사건을 볼 수 있다), 사건함 공통 결정(배송 쪽 = RIDER_OPS). 한계: MFC 관리자는 사건함이 없다(배차 취소는 MFC 백오피스).
- **구현됨(case 계약 0.5.0, V5, PR #21)**: `DispatchFailed`(delivery.delivery.v1) → `DISPATCH_FAILED`. 배송별 실패 횟수는 case 가 센다(`attempts` 는 재배차 시도마다 다시 셈). 배송마다 열린 사건 하나, 재발은 이력. 자동 종결 없음.

## R-8. 반품 회수 실패 → 상담원 재연락 사건 (2026-11-17, 보낸 영역 platform — CL-1)
- **결정(사용자 2026-11-17 "추천대로" — CL-1 ③)**: 인계 뒤 반품(고객센터 접수)의 **회수가 두 번 실패하면(고객 부재 등) 상담원이 고객에게 다시 연락**한다. 회수비는 한 번만 받는다.
- **claim 이 주는 것(구현, 2026-11-17 — claim 0.2.0, 미배포)**: `ReturnRecoveryFailed`(order.claim.v1, 키 claimId — 계약 `contracts/events/ReturnRecoveryFailed.v1.json`): claimId, orderId, accountId(고객), mfcId, attempts, lastDeliveryId, lastFailureReason, failedAt. 클레임은 RECOVERY_FAILED. 연락 뒤 조치는 claim `POST /claims/{claimId}/recovery-actions`(CS_AGENT·OPERATOR_ADMIN — `RETRY_PICKUP` 새 회수 요청 / `CANCEL` 반품 취소, 사유 note 필수).
- **case 가 할 것(PC 2)**: 구독 → 고객센터(CS_AGENT) 사건(예 종류 `RETURN_RECOVERY_FAILED`, 대상 = claimId·orderId·고객) — 같은 클레임은 열린 사건 하나, 또 실패하면 이력. 사건 화면에서 claim 조회(`GET /claims/{id}`)·위 조치로 이어지게. 클레임이 다시 IN_PROGRESS·CLOSED·CANCELLED 가 되면(사건 `ClaimClosed` 또는 claim 조회) 닫을지는 PC 2 가 정한다(자동 종결 규칙은 사건함 공통 결정을 따른다).
- **구현(2026-11-17, case 0.22.0 — PC 2, 사용자 "상담원 팀"·"1시간"·"사람이 닫기")**: 구독 `claimEvents`(order.claim.v1, `ReturnRecoveryFailed` 만 — pmart-config `case-service.yml` 바인딩 PC 1) → 사건 `RETURN_RECOVERY_FAILED`(subjectType CLAIM·subjectRef claimId·centerId mfcId, P2, 기한 1시간 표시만). 새 팀 **CS** = 상담원(CS_AGENT)이 보고 집고 닫는다(관리자 모두, 지원센터·센터 관리자에게 안 보임, 이관·교대 인계 대상 아님). 클레임마다 열린 사건 하나 — 또 실패하면 RECURRED 이력, 닫은 뒤 실패하면 새 사건(previousCaseId). **자동 종결 없음**(클레임이 IN_PROGRESS·CANCELLED·CLOSED 가 돼도 사람이 닫는다 — 조치는 행위 요청 기록으로 남길 수 있다). refs claimId·orderId·customerId·lastDeliveryId.
- **백오피스 화면(PC 2)**: 고객센터 메뉴에 반품 접수(`POST /claims` type RETURN — 본인 확인 방법·회사 잘못 사유 선택)와 회수 조치. handoff `docs/handoff/clients/all/2026-11-17-request-return-recovery.md`.

## R-9. 배차 지연 → 고객센터 사건 (2026-11-19, 보낸 영역 delivery — 사용자 "1,2,3 전부")
- 근거: 원문 Delivery §3.6 "배차지연시간이 일정기준(20분)을 넘으면 MFC관리자·고객센터에 안내". dispatch 는 `DispatchDelayed`(delivery.delivery.v1 — deliveryId·orderId·mfcId·delayedMinutes·threshold·detectedAt, 배송마다 한 번)를 내는데 **구독자가 없었다**(74분 지연에도 아무 안내 없음 — 사용자 지적).
- 요청: `DispatchDelayed` → 고객센터(CS) 사건 `DISPATCH_DELAYED`(배송마다 하나 — 열린 `DISPATCH_FAILED` 가 있으면 그 사건에 이력만도 됨, case 가 정함). 사건에 mfcId(센터 관리자도 보게 — DD-MFC), 고객이 전화하면 상담원이 바로 찾게 주문 연결. 고객 안내는 notification 이 이미 보냄(`DISPATCH_DELAYED` — 아래).
- 취소는 사건에서 claim "배차 불가 취소"(claim-service.md CL-5)로 — 전액 환불 + 2,000원 쿠폰.

- **상태: 구현(2026-11-19, case 0.27.0 — PC 2)**: 상담원 팀 사건 `DISPATCH_DELAYED`, 센터 = mfcId(센터 관리자 자기 센터 보기), refs deliveryId·orderId, 배송마다 하나. 같은 배송의 배차 실패(지원센터) 사건과는 따로 연다(팀·할 일이 다름). 기한 1시간(표시만 — 사용자 확정 2026-11-19). 취소 버튼은 웹이 claim CL-5 가 나온 뒤 연결.

## R-10. 결정 대기 전달 불가 — 접수 건 닫기 막기·다시 열기 (2026-11-19, 보낸 영역 delivery — 사용자 "연락불가로 전달할 수 없다는 도움요청 넣고 나서 지원센터에서 처리해버리면 라이더가 … 반려해 달라고 해도 연락이 안 가지고 전달도 못하는 상황")
- PC 5 구현(execution 0.31.2): 배송이 FAILURE_REPORTED 인 동안 그 전달 불가가 연 도움요청은 처리함으로 못 닫음(409 FAILURE_DECISION_PENDING). 닫혀 있어도 라이더가 대화를 쓰거나 재개를 요청하면 다시 열고 **`HelpRequestReopened`**(delivery.delivery.v1 — deliveryId·requestId·incidentId(= DeliveryIncidentRaised.incidentId)·riderId·caseId·reopenedAt) 를 낸다.
- 요청(PC 2 case):
  1. `HelpRequestReopened` 구독 → 그 incidentId 의 접수 건(RIDER_HELP·DELIVERY_INCIDENT)이 닫혀 있으면 다시 연다(CaseReopened — execution 은 이미 받아 IN_PROGRESS 로 맞춤). 담당 팀 그대로, 맨 위로.
  2. 닫기 막기: 전달 불가(category DELIVERY_FAILURE)로 열린 접수 건은, 그 배송이 아직 FAILURE_REPORTED 면 닫기(`/cases/{id}/closures`)를 409(예 FAILURE_DECISION_PENDING). 배송 상태는 execution `GET /deliveries/{id}`(서비스 토큰 — state·failure.decision)로.
- **상태: 구현(2026-11-19, case 0.28.0 — PC 2 #240, main 28707d99)**. 사용자 "둘다 너 추천대로": ② 닫기 막기는 요청대로(RIDER_HELP·DELIVERY_INCIDENT, execution `GET /deliveries/{id}` 를 닫는 직원 토큰으로 — FAILURE_REPORTED 면 409 FAILURE_DECISION_PENDING, 못 읽으면 503). ① 은 **자동 재개하지 않고** `HelpRequestReopened` → 이력 RIDER_REOPENED 만(상태 그대로 — 2026-11-16 "재개는 사람이 손으로만" 유지, 지원센터가 보고 다시 처리하기).
- **바뀜(2026-11-20, case 0.30.0)**: 사용자 "지원센터에서 처리완료하면 아예 배차 취소되게" → PC 5 가 "사건을 닫으면 실패 확정"(execution 1a2f0feb)으로 바꿈. 그래서 ② 닫기 막기는 case 에서 **뺐다**(남겨 두면 닫기가 409 라 확정이 일어나지 않음). 라이더가 계속 전달해야 하면 닫기 전에 반려. ① 재개 이력은 그대로.


## CS-MFC-Q. 품질 사고 고객 영향 → 고객 사건 열기 (2026-11-19, 보낸 영역 mfc — PC 3, MB-2 2차 R6)
- **왜**: MFC 백오피스 품질 사고(파손·변질 등 — `GET /quality-incidents`)의 영향 LOT 이 이미 고객에게 나갔으면 고객센터가 연락·환불을 판단해야 한다. 원 요청 pmart-clients `docs/handoff/mfc-backoffice/2026-11-19-backend-quality-incidents.md` R6 — "서버가 case-service 사건을 열고 `caseId` 를 채운다. 센터 관리자는 사고 상세에서 case 상태를 보기만".
- **사건(이미 나감 — mfc-quality 0.8.0, 브랜치 `mfc/return-arrivals`)**: `QualityIncidentImpactAssessed`(mfc.quality.v1, 계약 `contracts/events/QualityIncidentImpactAssessed.v1.json`) — incidentId·mfcId·kind(DAMAGE·SPOILAGE·…)·origin·lots[{lotId, sku}]·**affectedOrders[{orderId, lines[{orderLineId, sku, lotId, qty}]}]**·affectedOrderCount·assessedAt. 영향 주문이 바뀔 때마다 **전체 목록**으로 다시 나간다(aggregateVersion = 판단 번호 — 더 큰 것만). 0건도 처음 한 번 나간다(열지 않으면 됨).
- **요청**:
  1. 구독 → affectedOrderCount > 0 이면 고객센터 사건(예 종류 `MFC_QUALITY_IMPACT` — 이름·담당 팀·주문마다 하나인지 사고마다 하나인지는 case 가 정함). 센터 = mfcId(센터 관리자가 자기 센터 사건을 보는 기존 방식 그대로), refs incidentId·orderId.
  2. 다음 판단에서 주문이 늘면 그 주문만 더함(이미 연 것은 그대로). 줄어드는 일은 없다(인계는 되돌리지 않음).
  3. **caseId 되돌려 주기**: 사건을 열면 case 사건(예 `CaseOpened` 에 refs incidentId)을 mfc-quality 가 받아 `QualityIncident.caseId` 를 채운다 — 사건 이름·모양을 정해 주면 PC 3 가 구독을 붙인다(사고 하나에 사건이 여럿이면 첫 사건 또는 묶음 사건 ID 하나).
  4. 센터 관리자 조회: 사고 상세에서 case 상태를 보기만 — 기존 센터 관리자 사건 조회(`DISPATCH_DELAYED` 와 같은 길)로 되면 따로 필요 없음.
- 급함: 중간(지금은 영향 주문 수만 보이고 고객 사건은 안 열림).
- **상태: 구현(2026-11-20, case 0.31.0 — PC 2)**. 사용자 "추천대로": ① 종류 `MFC_QUALITY_IMPACT`, ② **주문마다 사건 하나**(dedupe `MFC_QUALITY:<사고>:<주문>`, 닫힌 것까지 — 늘어난 주문만 새로, 닫은 주문은 다시 안 엶, 0건 무시), ③ 상담원 팀 CS(관리자 모두, 지원센터 안 보임), ④ 센터 관리자는 자기 센터 보기만(R-9 와 같은 길 — 4번 따로 필요 없음), ⑤ P2·기한 1시간(표시만), ⑥ 사람이 닫기. subject ORDER(orderId), refs incidentId·orderId·lotId, 고객 ID 없음(이벤트에 없음).
  - **caseId 되돌려 주기(PC 3 할 일)**: 기존 `CaseOpened`(platform.case.v1) — payload `type` = `MFC_QUALITY_IMPACT`, `refs` 에 `{refType: incidentId, refId: <사고>}`·`{refType: orderId, …}`. 사고 하나에 사건이 주문 수만큼 나오니 **첫 caseId** 를 채우면 된다(새 사건 없음).
  - 설정: pmart-config `case-service.yml` 에 `qualityEvents-in-0`(destination mfc.quality.v1)·function definition `;qualityEvents` — PC 1.


## R-11. 직원 문의 목록 라이더 거르기 `riderId` (2026-11-21, 보낸 영역 delivery — 사용자 "응")
- **왜**: 라이더 백오피스 라이더 상세에 그 라이더의 도움요청·문의 기록을 모아 보인다(사용자 결정 2026-11-21). 도움요청은 execution 0.35.0 `GET /delivery-help-requests?riderId=&state=ALL&sort=NEWEST` 로 됨. 문의는 `GET /inquiries` 에 라이더 거르기가 없어 지원센터 목록을 따로 찾아야 한다(`customerId` 는 0.29.0 에 있음).
- **요청**: `GET /inquiries` 에 `riderId`(string, maxLength 64) — 직원(RIDER_OPS·OPERATOR_ADMIN) 거르기, 그 라이더의 **라이더 문의**만(requesterType RIDER), 최근 접수 순(지금과 같음). `customerId` 와 같은 방식. 볼 수 없는 직원(CS_AGENT 등)은 지금처럼 빈 목록 또는 403 — case 가 정함.
- **탈퇴한 라이더도** 기록은 riderId 로 남으니 그대로 나와야 한다(rider 0.20.0 탈퇴 라이더 상세에서 씀).
- 받는 화면: pmart-clients `web/rider-backoffice/src/features/riders/RiderPages.tsx` 라이더 상세 "문의" 구역(PC 5 — 이 요청이 배포되면 붙임).
- 급함: 낮음(지금은 지원센터 메뉴에서 찾으면 됨).
- **구현**(2026-11-21 PC 2, case-service 0.32.0 — PR 대기): `GET /inquiries?riderId=` 그 라이더의 라이더 문의만(탈퇴한 라이더도 — customer_id 열 = 남긴 라이더), 최근 접수 순. 라이더 문의를 못 보는 직원(CS_AGENT 등) **403**(빈 목록으로 보이지 않게 — customerId 와 같음). customerId·orderId·deliveryIssue·requesterType=CUSTOMER 와 같이 주면 400. 고객·라이더 토큰은 무시. 경로 변경 없음.

## R-12. 라이더 컴플레인 수·CS 후속 목록 센터 거르기 `centerId` (2026-11-23, 보낸 영역 platform — 사용자 "주문에 붙은 것만", 통합 백오피스 센터 고르기 #390)
- **왜**: 2호점 목포 MFC — 통합 백오피스 센터 고르기(pmart-clients #390). 아래는 서버에 센터 조건이 없어 "모든 센터"로만 보인다. `GET /cases` 는 이미 `centerId` 가 있다.
- **요청**: `GET /rider-complaints/counts`·`GET /obligations`(CS 후속)에 선택 `centerId` — 주문·배송에 붙은 건의 센터(사건의 centerId 와 같은 뜻). 센터 없는 건은 센터를 고르면 빠진다(그 뜻을 계약에 적기).
- **범위 밖(사용자 결정)**: 고객·라이더 문의, 문의 만족도, 교대 인계, 접수 건 통계(sla 외) — 센터 없는 건이 많아 "모든 센터" 그대로.
- **구현**(2026-11-23 PC 2, case-service 0.33.0 — PR 대기): `GET /rider-complaints`·`/counts`·`GET /obligations` 에 선택 `centerId`. 라이더 컴플레인은 새 칸 `center_id`(V21) — 기록 때 dispatch 배정 `mfcId` 를 저장, 0.33.0 전 기록은 NULL(센터를 고르면 빠짐 — 보정 없음). 후속 처리는 그 사건의 centerId(센터 관리자는 자기 센터 안에서만). 응답 RiderComplaint(Summary) 에 `centerId`. 경로 변경 없음.

## R-13. 고객 상담 메모 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **사용자 결정(2026-11-23)**: 상담사가 쓴다. **고치기·지우기는 쓴 사람만.**
- 고객 단위 내부 메모(특이사항·인수인계) — 접수 건 메모(0.10.0)와 따로. 받는 쪽 bff 고객 상세.

## R-14. 문의 욕설 자동 인식 → 계정 정지 사건 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "욕설이니까 자동으로 인식해서")
- **사용자 결정**: 욕설 사용은 자동으로 인식해 계정을 7일 정지(CU-7)하고 고객에게 "7일 계정 정지"를 알린다. 자동이 어려우면 상담원·관리자가 사유를 적고 건다.
- **할 것(PC 2)**: 고객 문의·추가 메시지를 받을 때 금칙어 목록(설정 — 새 프레임워크 없이 단어 목록 대조)과 맞춰 보고, 맞으면 이벤트(`CustomerProfanityDetected` — customerId·inquiryId·감지 시각, 글·단어는 싣지 않음)를 낸다. 계정 상태는 customer(CU-7)가 바꾼다.
- **결정(2026-11-23 사용자 "추천대로, 응")**: 금칙어 목록은 설정 저장소(pmart-config `case-service.yml`)에 단어 목록으로 두고, 바꿀 때는 사용자 승인 뒤 PC 1 이 반영한다. 오인식(욕설이 아닌데 걸림)은 상담원·관리자가 사유를 적고 직접 해제(CU-7 직원 해제).
- 리뷰 욕설 인식은 review-service(PC 1) 몫.
