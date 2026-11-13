# delivery-dispatch-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-dispatch-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-dispatch-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.dispatch.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.dispatch.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-dispatch-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-dispatch-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8404.

## 상태
2026-11-13 DD-1 첫 묶음(계약 0.2.0, V2 — ADR-0058): MFC 사건 → 배송 생성 → AI 추천 제안·일반 배차모드 배달리스트 → 수락·배정 → 인계(PICKED_UP), 작업 중지 → 픽업 전 취소. 배차 요청 REST(`/dispatch-requests`)·`POST /internal/deliveries` 는 501.
- **배차 취소**(2026-11-13, `CancellationService`, V4 — 원문 §5.4): `POST /deliveries/{id}/assignment-cancellations`(배정 라이더 본인·그 센터 운영자·서비스, 사유 OPERATOR 는 approvalRef 필수). 픽업 전 reassign(기본) → 배차 대기 + 새 시도 REASSIGN(취소한 라이더 `excluded_rider_id` 는 뺀다) + `DeliveryAssignmentChanged`(newRiderId null, reason `CANCELLED_<사유>`), reassign=false → `DeliveryCancelledBeforePickup`. 픽업 뒤 → 기록하고 422 PICKED_UP_CASE_REQUIRED. 결과 `GET …/{requestId}`.
- **배차 지연 안내**(원문 §3.6): 첫 시도부터 `pmart.dispatch.delay-alert-after`(20분 — 원문 "ex.20분", 사용자 확인 필요) 넘게 배차 대기면 `DispatchDelayed` 한 번(`delivery.delay_alerted_at`).
- **구독** `fulfillmentEvents-in-0` ← mfc.fulfillment.v1(`FulfillmentEventHandler`): **배차는 점원 요청 DispatchRequested 로만 시작**(BR-51, 사용자 2026-11-13 "자동요청빼" — mfc-fulfillment 0.4.11). ShipmentReady 가 먼저 오면 배송을 CREATED 로만 둔다(사건·시도 없음), 요청이 오면 DISPATCHING + 시도 + DeliveryCreated. 출고당 하나(`shipment_id` 유일 + advisory lock), PackingFinished(포장 목록), WorkStopResult(배송이 아는 포장이 모두 STOPPED 면 픽업 전 취소 — 포장을 모르면 그대로·경고), PackageHandedOver(모든 포장 인계 → PICKED_UP). **처음 켜기 전 소비 그룹 시작 위치 2026-11-10T00:30Z**(kickoff §6).
- **발행**(delivery.delivery.v1): DeliveryCreated(shipmentId, dropAddressRef = `order:<orderId>`)·DispatchOfferSent·DeliveryAssigned(assignmentVersion)·DeliveryCancelledBeforePickup·DispatchFailed. 주소·좌표는 싣지 않는다.
- **판단 주기** `DispatchEngine`(`pmart.dispatch.tick-interval` 5초): 만료 제안 EXPIRED(상세를 열었으면 거절 횟수 — 원문 §4.4) → 차례 된 시도를 MFC 별로 → 배차 가능 시간(08~24시 Asia/Seoul — 사용자 2026-11-13) 밖이면 다음 시작까지 → 좌표 준비 한 번(center-master CM-1 MFC 좌표, order O-4 배송지 → serviceability 정확 일치 좌표, tracking 카카오모빌리티 MFC → 고객 도로 거리 — 실패는 `geo_error` + 30초 뒤, 주소 없음·변환 0건·길 없음은 SYSTEM_FAILURE) → 후보(rider 적격 + AI 추천 모드 + 살아 있는 제안 없음 + 맡은 배송 < 3(하나씩 배달이면 1) + tracking 위치 2분 안 + 그 시도 단계의 **직선 반경** + 이번 회차 미제안) → 라이더 → MFC 카카오 도로 시간, 알뜰배달 고객 사이 카카오 도로 시간 → `BundleProblem`(**알뜰배달 BUNDLED 는 1~3건 묶음** — 원문 §5.3·사용자 2026-11-13 "묶음 배정으로 진행", 방문 순서는 순열 중 Σ도착 최소, 어느 배송이든 바로 갈 때보다 `bundle-max-extra`(설계값 20분) 넘게 늦으면 안 묶음. 한집·예약은 1건. 비용 = 라이더 → MFC + 출하 대기 대비 늦음×2/기다림 + Σ고객 도착) → `OrToolsAssignmentSolver`(CP-SAT 라이더 ↔ 묶음, 네이티브를 못 올리면 규칙 기반 + ERROR) → 배송마다 제안 1분(같은 `bundle_id`·`stop_seq`) + DispatchOfferSent. 못 지으면 `SearchRules`: 단계 1분 머묾 → 다음 단계 → 3단계 뒤 재요청(10분 뒤, 최대 2회, 회차가 바뀌면 거절한 라이더에게도) → 실패(REPEATED_REJECTION/EXPIRED/NO_RIDER).
- **라이더 API**(`OfferService`): `GET /offers`(만료 전 SENT, pollAfterSec — 신규배차 못 받으면 null), `GET /offers/{id}`(viewedAt), `POST /offers/{id}/responses`(배송 행 잠금 아래 `AcceptRules` — 한 명만, LATE 이유 4가지, 거절은 연속 거절 +1·5회 안내). **묶음은 전체가 한 번에** — 하나 수락이면 모두 배정(배송마다 DeliveryAssigned, `delivery.bundle_id`·`stop_seq`)·하나라도 안 되면 전체 LATE, 거절·만료도 전체(연속 거절 1회), 상세 조회는 묶음 전체 viewedAt. 일반 배차모드 `GET /delivery-list`(내 위치 → MFC 직선거리 ≤ 그 배송 단계 반경), `POST /delivery-list/{id}/acceptances`(배차 이력에 source DELIVERY_LIST 제안 한 줄).
- 예상 배달료 `domain/FeeTable` = 원문 §7.1 표(MFC → 고객 도로 거리, 구간 해석은 클래스 주석). 정산 수행료 정본은 execution.
- 조회: `GET /deliveries/{id}/dispatch`(그 센터 운영자·OPERATOR_ADMIN·배정/제안받은 라이더 — 라이더에게는 자기 제안만), `GET /internal/deliveries/{id}/assignment`.
- **AI 추천 계산(2026-11-13, ADR-0058 결정 13)**: `pmart.dispatch.ai.base-url` 이 있으면 먼저 `AiOptimizerClient`(Python `ai-services/delivery-dispatch-optimizer` — RouteFinder) → `BundleProblem.check` 로 검증(라이더·배송 중복, 묶음 크기, 2건 이상은 알뜰배달만, 용량·반경, 길 있음, 지연 한도 — 어긴 배정은 버림) → 통과한 묶음으로 제안(solver `AI ROUTEFINDER…`). 응답 없음(3초)·오류·전부 무효면 OR-Tools CP-SAT. 지표 `pmart.dispatch.ai.fallback`·`pmart.dispatch.ai.dropped`. 비우면 처음부터 OR-Tools.
- 아직 없음: 배달리스트에서 묶음째 고르기(지금은 한 건씩, 최대 3건), 묶음 배달료 할인·할증(지금은 배송마다 §7.1 표), 라이더 단계(RS-1 정지·계약 종료 → 배정 풀기), 반복 배차취소 → 계정 제한 연결(DeliveryIncidentRaised), 추가·분할 배차, 회수 배차(DD-2), 주류·보냉·용량 거르기.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.

## 이미지 (ADR-0053)
- **OR-Tools 사용 중(2026-11-13, `libs.ortools.java` — CP-SAT 배정)** → GraalVM Native 로 만들지 않고 JVM 으로 실행한다(ADR-0053 표 갱신함). PC 5 Windows 에서 네이티브 로드 확인(`OrToolsAssignmentSolverTest`).
