# delivery-execution-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-execution-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-execution-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.execution.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.execution.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-execution-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-execution-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8405.

## 상태
2026-11-13 계약 0.2.0(V2, PR #8 검토 H2 — 픽업한 배송은 전달 완료까지 라이더 "진행 중"):
- **구독** `deliveryEvents-in-0` ← delivery.delivery.v1(dispatch): DeliveryCreated → UNASSIGNED, DeliveryAssigned → ASSIGNED, DeliveryAssignmentChanged → 새 라이더 ASSIGNED / 해제(newRiderId null) UNASSIGNED(픽업 전만), DeliveryCancelledBeforePickup → CANCELLED(픽업 전만). `assignmentVersion` 이 지금보다 낮거나 같으면 버린다. `fulfillmentEvents-in-0` ← mfc.fulfillment.v1: ShipmentReady·PackingFinished → `shipment_package`(출고 포장 목록), PackageHandedOver → `package_handover`(같은 handoverId 한 번, 배송보다 먼저 와도 남김). 이 서비스가 내는 사건도 같은 토픽이라 종류로 거른다. **처음 켜기 전 두 소비 그룹 시작 위치 2026-11-10T00:30Z**.
- **픽업 완료**(`PickupJudgement`, DE-1·D-6): 출고 포장 목록을 알고 그 포장 전부에 인계 사실이 있을 때만 PICKED_UP + PickupCompleted(시각 = 마지막 인계). 사건이 올 때마다 다시 판정(순서 무관). 가게 도착을 안 눌렀어도(ASSIGNED) 인계는 실물 사실이라 받는다. `POST …/pickups` 는 상태를 바꾸지 않고 확인만 — 보낸 handoverIds 가 이 배송·이 라이더 인계가 아니거나 포장이 남았으면 422 HANDOVER_NOT_CONFIRMED / HANDOVER_RIDER_MISMATCH.
- **라이더 전이**(배정 라이더 본인, RequestIdempotency, X-Expected-Version = 수행 버전): 가게 도착(ASSIGNED → ARRIVED_AT_STORE, RiderArrivedAtStore), 고객 도착(PICKED_UP → ARRIVED_AT_CUSTOMER, RiderArrivedAtCustomer), 증빙 사진(PICKED_UP·ARRIVED_AT_CUSTOMER, JPEG·PNG·WEBP 앞부분 판정·10MB, `deliveries/{id}/{photoRef}` — 저장소 없으면 503), 전달 완료(ARRIVED_AT_CUSTOMER → COMPLETED, photoRefs 는 같은 라이더가 이 배송에 올린 것만 — 아니면 422 EVIDENCE_REQUIRED, DeliveryCompleted lines = 인계 줄 합계, evidenceRef kind PHOTO). 거절은 기록하지 않는다(사건이 온 뒤 같은 키로 다시 눌러도 되게).
- **조회**: `GET /deliveries/{id}`(배정 라이더·그 센터 운영자·OPERATOR_ADMIN·CS_AGENT·서비스, 아니면 404), `GET /riders/me/deliveries`(배정 최신순, date = Asia/Seoul 배정일, cursor = 건너뛸 수).
- **사진 저장소** `pmart.storage.s3.*`(customer-service 와 같은 키, 버킷만 다르게 — 예 `pmart-delivery-evidence`). 비우면 사진 올리기만 503.
- 아직 501: 전달 실패·도움요청·회수·수행료·정산·운영자 정정. aggregateVersion 은 이 서비스 수행 버전(dispatch 의 배송 버전과 별개 흐름 — PC 1 검토 L3 과 같은 문제, 구독자는 종류별로 다룬다).

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.
