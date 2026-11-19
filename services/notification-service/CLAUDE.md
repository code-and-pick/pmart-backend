# notification-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `notification-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/notification-service.yaml` → `openApiGenerate` 가 `com.pmart.notification.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.notification.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:notification-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:notification-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8502.

## 상태
3단계 스캐폴딩(2026-10-23).
- **고객 앱 알림함 1차(2026-11-16, 계약 0.2.0, ADR-0064, V2)**: `application/NotificationService`, 문구 `domain/Notices`, 표 `infrastructure/NotificationStore`(`notification`·`refund_link`·`pending_refund_result`), 받는 고객 찾기 `OrderClient`(order `/internal/orders/{id}`, 설정 `pmart.notification.order-base-url`).
  - 구독(`NotificationEventConsumers`): orderEvents(order.order.v1 — 확정·준비·취소·결품 부분 취소), deliveryEvents(delivery.delivery.v1 — PickupCompleted·DeliveryCompleted·**RedeliveryPaymentRequired**(N-3, 2026-11-16 — 오배송 다시 배송비 결제 필요, 분류 PAYMENT)), claimEvents(order.claim.v1 — RefundIntentCreated 저장), paymentEvents(order.payment.v1 — RefundResult SUCCESS·FAILED), caseEvents(platform.case.v1 — InquiryAnswered). 같은 환불 건은 `pg_advisory_xact_lock` 으로 의도·결과를 한 줄로(결과가 먼저 오면 pending).
  - API: `/me/notifications`(목록·unreadCount·category·unreadOnly), 상세, 읽음(멱등). 설정은 모두 켜짐·잠김(끄기 422 TRANSACTIONAL_CANNOT_DISABLE, IN_APP 밖 422 CHANNEL_NOT_APPROVED). 운영자 403, 라이더는 빈 알림함.
  - 문구에 개인정보 없음, 결과 모르는 일을 완료로 쓰지 않음. 알림 실패로 원래 업무를 다시 하지 않는다.
  - 아직 501: 설정 변경 결과 조회, `/internal/*`(범용 요청·발송 이력·배차 알림 기록). 푸시·발행(NotificationDelivered·Failed) 없음.
- **단말 푸시 토큰(2026-11-17, 계약 0.3.0, 클라이언트 요청 BR-09, V3)**: `application/PushTokenService`, 표 `infrastructure/PushTokenStore`(`push_token` — PK 주인 종류·주인·deviceId, 토큰은 `token_sha256` 유일), `api/PushTokensController`.
  - API: `PUT /me/push-tokens/{deviceId}`(등록·교체 — 같은 단말이면 토큰 교체, 같은 토큰이 다른 주인·단말에 있으면 옮김), `DELETE`(본인 단말만, 멱등 204), `GET /me/push-tokens`(토큰 값 없이 단말 목록). 고객·라이더만(운영자 403), Android 만, deviceId `[A-Za-z0-9._:-]{1,128}`.
  - 주인당 단말 수 `pmart.notification.push-token.max-devices`(설정 저장소 기본 5) — 넘으면 가장 오래 갱신되지 않은 단말 삭제. 설정 없으면 PUT 422 CONFIG_REQUIRED.
  - **토큰만 저장 — 발송(FCM) 없음**(자격증명·발송 정책 미정). APP_PUSH 는 여전히 CHANNEL_NOT_APPROVED. 토큰 원문은 로그·응답에 넣지 않는다(생성 DTO `PushTokenRegistration.toString` 에 토큰이 있으니 요청 본문을 로그로 남기지 않는다).
  - Idempotency-Key·X-Source-System 은 받지만 `RequestIdempotency` 기록은 하지 않는다(이 서비스의 다른 명령과 같음 — PUT·DELETE 가 본래 멱등).

## 다른 서비스의 요청
- MFC·주문 이행: `docs/architecture/service-requests/notification-service.md`(N-1 결품 처리 고객 안내·보류 단계 안내 여부, 2026-11-08). 구현 때 그 문서의 "보충 목록" 도 같이.
- **배송 지연 가능 안내(CP-1, 2026-11-16)**: 구독 `slotEvents-in-0` ← delivery.slot.v1 `SlotCapacityReduced` → 영향 주문마다 "배송이 늦어질 수 있어요"(DELIVERY, 목적지 ORDER, 도착 시간대 KST, 상품 준비 전까지 취소 안내 — 시간대 변경은 아직 없어 안 씀). 한 사건에 주문이 여럿이라 주문 ID 로 한 번씩.
- **예약 시간 변경 알림(2026-11-16 — S4, PR #64 뒤)**: orderEvents 의 `ReservationTimeChanged` → "예약 시간이 바뀌었어요"(ORDER, 새 도착 시간대). 배송 지연 안내(CP-1)는 도착 시간대가 있는 주문이면 "다른 시간대로 바꾸거나" 취소 안내.
- **라이더 알림함(2026-11-16, 계약 0.4.0, ADR-0066 — 라이더 앱 요청 §11-5)**: 같은 표·같은 `/me/notifications`(라이더 토큰), recipient_type RIDER(마이그레이션 없음). `deliveryEvents` 에 `HelpRequestReplied`(지원센터 답장), `caseEvents` 에 `RiderInquiryAnswered`·`CaseClosed`(type RIDER_HELP 만 — refs 의 riderId·deliveryId·requestId), **새 구독 `riderEvents-in-0` ← delivery.rider.v1 `RiderRestricted`**(설정 저장소 바인딩 필요). 문구 `Notices.helpRequestReplied·helpRequestClosed·riderInquiryAnswered·riderRestriction` — 내용·사유 없음, 제한 기한은 KST, 분류 CASE·SYSTEM, 목적지 HELP_REQUEST(`<deliveryId>:<requestId>`)·INQUIRY·NONE.
- **수행료 조정 알림(2026-11-17)**: `deliveryEvents` 에 `RiderEarningRecorded` 더함 — `adjustment` 가 있을 때만(지원센터 조정) 라이더 알림함 `RIDER_EARNING_ADJUSTED`(지급이면 금액·지급 예정일, 미지급이면 그 사실만 — 사유 없음, 목적지 NONE). 일반 기록·확정은 알리지 않는다.
- **고객 반품 회수 알림(2026-11-17)**: `deliveryEvents` 에 `DeliveryCreated`·`ReturnPickedUp`·`ReturnPickupFailed` 더함 — purpose CLAIM_RETURN 일 때만 고객 알림함: 회수 기사 배정 중(`RETURN_PICKUP_DISPATCHING`), 회수함(`RETURN_PICKED_UP` — 환불은 확인 뒤), 고객 사정 회수 실패(`RETURN_PICKUP_MISSED`, customerReason true 만 — ORIGIN_NOT_DELIVERED 같은 시스템 거절은 안 알림). 목적지 ORDER, 일반 배송 생성·오배송 회수는 지나간다.

- **라이더 휴대폰 푸시(2026-11-17 — 사용자 "발송 코드만 먼저, 키는 나중")**: 라이더 알림함에 새 알림이 저장되면 **커밋 뒤** 그 라이더의 단말 토큰마다 FCM HTTP v1(`infrastructure/FcmSender` — 새 의존성 없이 JDK RS256 서명으로 OAuth 접근 토큰, 캐시) → `application/RiderPush`. 제목·본문은 알림함과 같고(개인정보 없음) data 에 notificationId·templateKey·destinationType·destinationRef, android priority HIGH. 404·UNREGISTERED 토큰은 지움, 실패는 기록만(알림함이 정본, 다시 보내지 않음). **키 `pmart.notification.fcm.service-account-json`(Firebase 서비스 계정 JSON, 설정 저장소 {cipher}) 이 없으면 꺼짐** — LAN 은 2026-11-17 켬(Firebase `pmart-123`, pmart-config 370cac1). 고객 푸시는 아래 줄.
- **고객 휴대폰 푸시(2026-11-17 사용자 "응 해줘", ADR-0067, 계약 0.4.1 — 설명만)**: `application/CustomerPush` — 고객 알림함에 새 알림이 저장되면 커밋 뒤, **정한 종류만**(`pmart.notification.fcm.customer-templates` — 설정 저장소 공통: 배송 출발·완료·결품·주문 취소·환불 완료·늦어짐) 그 고객 단말마다. 나머지는 알림함만, 목록이 비면 없음. **키는 고객 앱 Firebase 프로젝트 것** `pmart.notification.fcm.customer-service-account-json`(LAN {cipher} — 2026-11-18 Firebase 프로젝트를 `pmart-123` 하나로 합쳐 라이더와 같은 키, pmart-config b625d04). 발송기는 PC 5 `FcmSender` 를 고치지 않고 고객 키로 하나 더 만든다(`RiderPush`·`RiderPushIT` 는 PC 5 것 그대로). 시험 `CustomerPushTest`·`CustomerPushIT`(고객 프로젝트로만, 정하지 않은 종류는 안 보냄).
- **받았는지 묻기(2026-11-17 — PC 5)**: 구독 `trackingEvents`(delivery.tracking.v1 `DeliveryReceiptCheckRequested` — **pmart-config 바인딩 `trackingEvents-in-0`·definition 추가 필요(PC 1)**, 시험 사본은 고침) → 고객 알림함 `DELIVERY_RECEIPT_CHECK` "상품을 받으셨나요?"(ORDER).
- **리뷰 요청 알림(2026-11-18 — 사용자 결정 "A해줘", 계약 0.4.2 설명만, V4)**: `deliveryEvents` 의 `DeliveryCompleted` 가 완료 알림과 같은 order 조회로 고객을 찾아 `review_request`(주문 키 — 주문당 한 줄, 분할·다시 배송·재전달은 그대로)에 PENDING 을 둔다. 회수 목적(purpose CLAIM_RETURN 등)·취소된 주문(order status CANCELLED)은 두지 않는다(`domain/ReviewRequestRules`).
  - `application/ReviewRequestSweeper`(`@Scheduled` — `review-request.sweep-interval` 기본 PT5M·`sweep-initial-delay` PT1M): 완료 + `pmart.notification.review-request.delay`(설정 저장소 PT1H) 지난 줄마다 review `GET /internal/orders/{id}/review-status?accountId=`(`infrastructure/ReviewClient`, `pmart.notification.review-base-url`) → 안 썼고 쓸 품목이 있으면 같은 트랜잭션에서 SENT + 고객 알림함 `REVIEW_REQUESTED`(ORDER, 목적지 ORDER, dedupe `ReviewRequested:<orderId>`), 썼으면(삭제 포함) SKIPPED_REVIEWED, 쓸 품목 없으면 SKIPPED_NOT_ELIGIBLE. review 장애·이상한 답은 PENDING 그대로(attempts·last_error) 이번 정리를 멈추고 다음에, 완료 + `max-age`(P3D) 지나면 EXPIRED.
  - **delay·max-age·review-base-url 중 하나라도 없으면 보내지 않는다**(CONFIG_REQUIRED 를 로그에 한 번 — 줄은 쌓이고, 설정이 생기면 max-age 안의 것만 보낸다). 주기·첫 시작만 운영값이라 자리표시자 기본값.
  - **앱 알림함만** — `fcm.customer-templates` 에 REVIEW_REQUESTED 를 넣지 않는다(사용자 결정). 수신 설정은 지금처럼 ORDER·IN_APP 잠김(끌 수 있는 설정이 아직 없음 — 리뷰 요청을 MARKETING 으로 두면 customer 마케팅 동의가 필요해 ORDER 로 둠).
  - 시험 `ReviewRequestRulesTest`·`ReviewRequestSweeperTest`·`ReviewRequestIT`(시험 사본 notification-service.yml 에 delay·max-age).
  - **오배송 보류(2026-11-18 사용자 "추천대로", 0.4.3 설명만)**: `caseEvents` 에 `MisdeliveryConfirmed` 더함 — 그 주문의 PENDING 을 ON_HOLD(`ReviewRequestStore.hold`, 기다림이 지나도 안 보냄). 그 주문의 다음 `DeliveryCompleted`(다시 배송 — purpose MISDELIVERY_REDELIVERY 등, 회수 목적 제외)가 오면 PENDING 으로 되돌리고 완료 시각·사건을 새것으로(`resume` — 기다림 다시). 환불로 끝나면 ON_HOLD 그대로(안 보냄). 마이그레이션 없음(state 문자열). 시험 `ReviewRequestIT.misdeliveryHoldsReviewRequestUntilRedeliveryCompletes`.
- **알림함 응답 templateKey(0.4.4, 2026-11-18 — 고객 앱 R1)**: `Notification.templateKey`(알림 종류 — 표의 template_key 그대로, `InboxController`). 리뷰 요청은 sourceEventType 이 배송 완료와 같아 앱이 가를 수 없었다. 시험 `ReviewRequestIT`(REVIEW_REQUESTED).
- **배차 제안 푸시·발송 형식(2026-11-17 — 라이더 앱 요청 P1·P2)**: `deliveryEvents` 에 `DispatchOfferSent` 더함 → `RiderPush.offer` — 알림함에 넣지 않고 푸시만, data type DISPATCH_OFFER·offerId·deliveryId·expiresAt, android HIGH·TTL = 만료까지(이미 만료면 안 보냄)·tag dispatch_offer. 알림함 푸시는 NORMAL, data 에 type·ref(+destinationType·destinationRef). 채널 ID 는 서버가 정함: dispatch_offer·support·account·settlement(`RiderPush.channel`). 회신 `docs/handoff/clients/rider-app/2026-11-17-reply-rider-push-send.md`.
- **일반배차모드 새 배달 푸시(2026-11-18)**: `riderEvents`(delivery.rider.v1) `DeliveryListUpdated` → `RiderPush.listUpdated` — 알림함 없이 푸시만, data type DELIVERY_LIST·newCount, 채널 dispatch_offer·HIGH·TTL 60초·tag delivery_list.
- **라이더 공지(2026-11-18, 0.5.0·V5 — 사용자 "대상 고르기 + 알림함·푸시")**: `POST/GET /rider-notices`(`RiderNoticeService`·`RiderNoticesController`) — RIDER_OPS·OPERATOR_ADMIN, 제목 40·본문 500자, 대상 ALL·MFC → rider `/internal/riders/recipients`(`infrastructure/RiderClient`) → 라이더마다 알림함 RIDER_NOTICE(SYSTEM, NONE) + 커밋 뒤 푸시, `rider_notice`(보낸 사람·수, 같은 직원+Idempotency-Key 한 번).
- **수행료 지급 결과 알림(2026-11-18)**: `RiderEarningRecorded` status PAID·PAYMENT_FAILED + payoutDate → 라이더 알림함 `RIDER_PAYOUT_PAID`·`RIDER_PAYOUT_FAILED`(SYSTEM, 정산 채널) — 묶음(라이더 × 지급 예정일)마다 한 번(dedupe `RiderPayout:<status>:<rider>:<date>`), 금액·실패 사유 없음.
- **사고·파손 재발송 안내(N-4, 2026-11-19 — ADR-0069, 사용자 "응")**: `deliveryEvents` 에 `DeliveryFailed` 더함 — `domain/DeliveryFailureRules.noticeReshipment`(order-orchestration `ReshipmentRules.reshipsOnFailure` 와 같은 기준: reason RIDER_INCIDENT·DAMAGED + deliveryType FORWARD, 또는 RESHIPMENT·purpose MISDELIVERY_RESHIPMENT)일 때만 고객 알림함 `DELIVERY_INCIDENT_RESHIPMENT` "배송 중 문제가 생겨 새 상품으로 다시 보내 드려요"(DELIVERY, 목적지 ORDER — "보낼 예정"으로 씀, 출발은 출발 알림). 그 밖 사유·회수·오배송 다시 배송은 알리지 않는다(상담원). 푸시 목록 `fcm.customer-templates` 에 넣음. 시험 `DeliveryFailureRulesTest`·`NotificationsIT.incidentFailureNotifiesReshipment`.
- **소명·이의 결정 알림(2026-11-18)**: riderEvents `RiderAppealDecided` → 라이더 알림함 `RIDER_APPEAL_DECIDED`(SYSTEM, 계정 채널) — 결과만, 설명은 앱의 내 신청에서.
- **레퍼런스 배송 알림(2026-11-19, 계약 0.5.1 설명만 — 고객 앱 레퍼런스 `10. 알림` 3-1~3-5, 사용자 "응 해줘")**: `PREPARATION_STARTED` 문구 — order 내부 조회(0.14.1 `deliveryMode`·`arrivalWindow`, `OrderClient.OrderInfo`)로 "주문하신 상품(첫 상품명 외 N개)을 준비하고 있어요" + 예약이면 도착 시간대, 휴대폰 푸시에 넣음. `DELIVERY_STARTED` 목적지 DELIVERY(deliveryId — 배송 현황). 새 종류 `DELIVERY_HEADING`(deliveryEvents `DeliveryHeadingToCustomer` — PC 5 DE-6)·`DELIVERY_ARRIVING_SOON`(trackingEvents `RiderApproachingCustomer` — PC 5 TR-4, 300m) — 고객 배송 purpose(DELIVERY·없음·MISDELIVERY_RESHIPMENT·MISDELIVERY_REDELIVERY)만, 목적지 DELIVERY, 푸시 포함. 두 사건은 PC 5 가 내야 보인다. 시험 `NoticesTest.referenceDeliveryNotices`·`NotificationsIT.headingAndArrivingSoonOpenTracking`.
- **리뷰 요청 10분·푸시(2026-11-19 사용자 "레퍼런스처럼", 계약 0.5.2 설명만 — 설정만)**: pmart-config `review-request.delay` PT1H → **PT10M**, `sweep-interval` PT1M(기본 5분이면 10~15분 뒤), `fcm.customer-templates` 에 `REVIEW_REQUESTED` 추가 — 위 11-18 줄의 "1시간·알림함만"을 바꿈. 문구는 그대로(레퍼런스의 "최대 500원 적립"은 포인트가 없어 안 씀).
- **배차 지연 고객 안내(2026-11-19 — 원문 §3.6, 사용자 "1,2,3 전부")**: deliveryEvents `DispatchDelayed` → 고객 알림함 `DISPATCH_DELAYED`(DELIVERY, 목적지 ORDER — "배송이 늦어지고 있어요 … 약 N분(5분 단위 올림)", 취소는 고객센터·전액 환불). 휴대폰 푸시는 `fcm.customer-templates` 에 DISPATCH_DELAYED(시험 사본 고침, **pmart-config 는 PC 1**).
- **취소 알림 사유별(2026-11-19, CL-5)**: `OrderCancelled.cancelReason`(없으면 reason)로 `Notices.orderCancelled` — DISPATCH_UNAVAILABLE "배달 기사를 찾지 못해 주문이 취소되었어요 / …전액 환불돼요", OUT_OF_STOCK 품절 문구(전에는 reason 이 늘 CANCEL_BEFORE_SHIP 이라 안 나감). 쿠폰 문구는 promotion 이 생기면. 시험 `NoticesTest.cancelNoticeByReason`.
- **실패 확정 되돌리기 정정(2026-11-19)**: deliveryEvents `DeliveryFailureReverted` — 재발송 안내 기준(`DeliveryFailureRules`, originalReason)이면 고객 알림함 `DELIVERY_FAILURE_REVERTED` "배송을 이어서 진행해요".
