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
- **라이더 전이**(배정 라이더 본인, RequestIdempotency, X-Expected-Version = 수행 버전): 가게 도착(ASSIGNED → ARRIVED_AT_STORE, RiderArrivedAtStore), 고객 도착(PICKED_UP → ARRIVED_AT_CUSTOMER, RiderArrivedAtCustomer), 증빙 사진(PICKED_UP·ARRIVED_AT_CUSTOMER, JPEG·PNG·WEBP 앞부분 판정·10MB, `deliveries/{id}/{photoRef}` — 저장소 없으면 503), 전달 완료(ARRIVED_AT_CUSTOMER → COMPLETED, photoRefs 는 같은 라이더가 이 배송에 올린 것만 — 아니면 422 EVIDENCE_REQUIRED, DeliveryCompleted lines = 포장마다 마지막 인계 줄의 합(출고 포장만, 비면 완료 거절 — PC 1 재검토 ③), evidenceRef kind PHOTO). 거절은 기록하지 않는다(사건이 온 뒤 같은 키로 다시 눌러도 되게).
- **조회**: `GET /deliveries/{id}`(배정 라이더·그 센터 운영자·OPERATOR_ADMIN·CS_AGENT·서비스, 아니면 404), `GET /riders/me/deliveries`(배정 최신순, date = Asia/Seoul 배정일, cursor = 건너뛸 수).
- **사진 저장소** `pmart.storage.s3.*`(customer-service 와 같은 키, 버킷만 다르게 — LAN `pmart-delivery-evidence`). 비우면 사진 올리기만 503. **올리기는 DB 트랜잭션 밖**(2026-11-14): 잠금 없이 확인 → 올리기 → 트랜잭션(멱등 기록·잠금 아래 다시 확인·참조 저장). 사진 참조는 (출처·키·라이더·배송·내용 해시)로 정해져 같은 요청 재시도는 같은 객체를 덮어쓰고, 트랜잭션이 실패하면 올린 객체를 지운다(실패해도 경고 로그만).
- **전달 실패(0.3.0, 사용자 결정 2026-11-13)**: 라이더 `POST …/failures`(PICKED_UP·ARRIVED_AT_CUSTOMER, 사유 + 이 배송에 올린 사진 1장 이상 + 고객 사유(CUSTOMER_ABSENT·UNREACHABLE·ADDRESS_ERROR·REFUSED)면 연락 2회 이상 — 파손·사고·기타는 사진만(사용자 확인 2026-11-13) — 아니면 422 EVIDENCE_REQUIRED·CONTACT_ATTEMPTS_REQUIRED) → FAILURE_REPORTED(응답 ACCEPTED, **아직 진행 중** — 완료도 안 됨) + DeliveryIncidentRaised(category DELIVERY_FAILURE). 운영자 `POST …/failure-decisions`(**관리자만** — 그 센터 `MFC_SUPERVISOR`·OPERATOR_ADMIN·CS_AGENT, 작업자는 403 — `Access.operatorCanDecide`, 사용자 2026-11-14): CONFIRM → FAILED + DeliveryFailed(custody — 지정 없으면 라이더가 적은 상품 위치로, dispatch CLOSED) + 수행료 산정 불가, REJECT → 등록 전 상태로. 이어지는 회수 배차는 새 배송.
- **도움요청(0.3.0, 사용자 결정 "1번")**: `POST …/help-requests`(배정 라이더, 배정~실패 등록 단계) → `help_request` + DeliveryIncidentRaised(type HELP_REQUEST, category·stage·detail·evidenceRefs, **incidentId = `<deliveryId>:<requestId>`** — 요청 ID 는 앱 Idempotency-Key 라 배송마다만 유일, 2026-11-14). case-service 가 받아 지원센터 사건으로(구현 전 — `docs/architecture/service-requests/case-service.md` R-4), `CaseOpened` 로 caseId 회신은 그때. 조회 `GET …/help-requests/{id}`(본인·운영자, 아니면 404). 내용은 로그 금지.
- **수행료(0.3.0, 사용자 결정 "수락 당시 금액 + 실패·취소는 산정 불가")**: dispatch DeliveryAssigned.fee·vehicleType·distanceM 을 `execution_delivery.accepted_fee` 등에. 전달 완료 → `rider_earning`(**배정당 하나** — 배송·라이더·배정 버전, V4 2026-11-14: 운영자 배차 취소로 산정 불가가 남은 뒤 같은 라이더가 다시 배정돼 완료하면 완료 수행료가 따로 생긴다) PENDING_CONFIRMATION, `confirm_at` = 완료 + `pmart.execution.confirm-after`(5시간), `payout_date` = 수행일(Asia/Seoul) + `payout-business-days`(3) 영업일(주말·법정공휴일 제외 — 사용자 결정 2026-11-13 "법정공휴일로 해", 기본값 `domain/KoreanPublicHolidays` 2026~2027 — 제헌절·6/3 지방선거·노동절 포함, **해마다 더해야 함**(목록 밖 해면 경고 로그). 임시공휴일은 설정 `public-holidays` 에 목록 전체로). `EarningService.confirmDue`(`confirm-interval` 1분) → CONFIRMED. 실패 확정·운영자/주문 사유 배차 취소 → 산정 불가(calculable false, 지원센터 조정), 라이더 본인 배차 취소는 수행료 없음. 사건 RiderEarningRecorded(만들 때·확정 때). 지급(PAYING·PAID)은 외부 회계 — 연결 없음. `GET /riders/me/earnings`(수행일 기간·상태, 합계는 산정 가능한 것만).
- 도움요청·수행료 사건처럼 상태를 바꾸지 않는 사건도 `bumpVersion` 으로 수행 버전을 올린다(aggregateVersion 겹침 없음).
- **라이더용 전달지(0.3.2, 2026-11-14 — 클라이언트 BR-24)**: `GET /deliveries/{id}/drop-info`(`DropInfoService`) — 배정 라이더 본인만(아니면 404, 직원 403), 상태 PICKED_UP·ARRIVED_AT_CUSTOMER·FAILURE_REPORTED 만(아니면 409 DROP_INFO_CLOSED). 매번 order `GET /internal/orders/{id}/drop-info`(O-5, `infrastructure/OrderClient`, `pmart.execution.urls.order` 기본 `http://order-service:8201`)에서 읽고 저장·로그 안 함(`Cache-Control: no-store`, record toString 가림). **연락처는 고객 전화번호 그대로**(안심번호 없음 — 사용자 2026-11-14). 사본 없음 404 DROP_INFO_NOT_FOUND, order 장애·거절 503.
- 아직 501: 회수(claim·dispatch DD-2 뒤)·운영자 상태 정정. aggregateVersion 은 이 서비스 수행 버전 — 같은 Delivery 의 dispatch 사건과 따로 오른다(발행 서비스마다 — `docs/architecture/events.md` §7 머리말, 2026-11-14). 서비스 사이 순서는 assignmentVersion·상태로.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.
