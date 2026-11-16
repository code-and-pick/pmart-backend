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

## 다른 서비스의 요청
- MFC·주문 이행: `docs/architecture/service-requests/notification-service.md`(N-1 결품 처리 고객 안내·보류 단계 안내 여부, 2026-11-08). 구현 때 그 문서의 "보충 목록" 도 같이.
