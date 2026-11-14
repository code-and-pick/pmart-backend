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
- **배송 약속·지연 안내**(사용자 2026-11-13 "주문한지 50분 이내 전달, 안내기준보다 20분 더 늦춰지면 지연 안내", V6): `delivery.promised_at` = 한집·알뜰은 주문 확정(order O-4 `confirmedAt`) + `delivery-promise`(50분), 예약은 도착 시간대 끝(사용자 확인 2026-11-13 "끝시각으로 둬" — 18:00~19:00 이면 19:00, 19:20 넘을 예상이면 안내). 배차 대기 중 예상 전달(지금 또는 출하 대기 + MFC → 고객 도로 시간 — 가장 빠른 경우)이 약속 + `delay-alert-after`(20분)를 넘으면 `DispatchDelayed` 한 번. 배정 뒤 지연(라이더 ETA)은 tracking ETA(TR-1) 때.
- **알뜰배달 묶음 조건**: 2건 이상 묶음은 그 라이더로(MFC 도착·출하 대기 중 늦은 때 출발) 모든 고객이 약속 안에 받을 때만(`BundleProblem.withinPromise` — AI 안 검증도 같음). 1건은 늦어도 보낸다. `bundle-max-extra`(50분)는 보조 상한.
- **배차 실패 → 자동 재요청**(사용자 2026-11-13 M2): 재요청 2회까지 못 구하면 `DispatchFailed`(알림)만 내고 배송은 배차 대기 그대로, `retry-interval`(10분) 뒤 새 시도 RETRY(회차 0부터 — 거절한 라이더에게도). 시스템 장애(주소 없음 등)만 DISPATCH_FAILED 로 멈춘다. **재배차 없이 취소**는 MFC 백오피스 관리자가 배차 취소 API(reassign=false, 배정 전 배송도 — 운영자만)로, DISPATCH_FAILED 다시 배차는 reassign=true.
- **묶음 후보 줄이기**(2026-11-13, `pmart.dispatch.bundle-neighbors` 기본 4): 알뜰배달 2·3건 후보를 고객마다 가까운(고객 → 고객 도로 시간) 4명 안에서만. 모든 조합이면 대기 20~40건에서 후보가 수천 개라 CP-SAT 가 2초 안에 80~90% 못 풀고 규칙 기반으로 떨어졌다 — 4 면 0건, 배정 +7~10%, 시간 0.4~0.6초(`LargeMfcBenchmarkTest` — `PMART_BENCH=1` 때만, `docs/delivery/ai-optimizer-evaluation.md`). 0 = 모든 조합.
- **카카오 호출 줄이기**(M3, `infrastructure/RouteCache`): 라이더 → MFC 는 (MFC, 약 100m 칸)마다 5분, 고객 ↔ 고객은 배송 쌍마다 저장(메모리). 제안할 사람 없을 때 다시 보기 `search-interval` 30초, 카카오 429 면 `quota-backoff` 5분.
- **반복 배차취소 → 배차 알림 감소**(사용자 2026-11-13, 제재 아님): 최근 `cancellation-window`(7일) 라이더 본인 배차취소 1건마다 AI 추천 비용 +`cancellation-penalty`(3분 — 다른 라이더가 먼저), 일반 모드 배달리스트에 새 배송이 `list-delay-per-cancellation`(1분)씩 늦게 보임. 값은 사용자 확인(2026-11-13 "배차취소 패널티는 그대로").
- **구독** `fulfillmentEvents-in-0` ← mfc.fulfillment.v1(`FulfillmentEventHandler`): **배차는 점원 요청 DispatchRequested 로만 시작**(BR-51, 사용자 2026-11-13 "자동요청빼" — mfc-fulfillment 0.4.11). ShipmentReady 가 먼저 오면 배송을 CREATED 로만 둔다(사건·시도 없음), 요청이 오면 DISPATCHING + 시도 + DeliveryCreated. 출고당 하나(`shipment_id` 유일 + advisory lock), PackingFinished(포장 목록), WorkStopResult(배송이 아는 포장이 모두 STOPPED 면 픽업 전 취소. 피킹 전 중지는 전체·부분 모두 perPackage=[] 로 와 여기서는 판단하지 않는다 — PC 1 재검토 ①, 사용자 (가)), PackageHandedOver(모든 포장 인계 → PICKED_UP). **처음 켜기 전 소비 그룹 시작 위치 2026-11-10T00:30Z**(kickoff §6).
- **주문 잠금**(2026-11-14, PC 1 검토 후속): 배차 요청·출하 대기(배송 만들기 `ensure`)와 작업 중지(`onStop`)·주문 취소(`onOrderCancelled`)는 `pg_advisory_xact_lock('dispatch-order:'||orderId)` 를 먼저 잡는다(ensure 는 그 뒤 출고 잠금 — 순서 늘 주문 → 출고). 없으면 배차 요청이 "멈춘 주문 아님"을 본 뒤 아직 커밋 안 된 배송을 중지 처리가 못 봐 멈춘 주문이 배차될 수 있었다.
- **CREATED 배송 취소는 사건 없이**(2026-11-14): 점원 배차 요청 전(ShipmentReady 만, DeliveryCreated 를 낸 적 없음) 배송을 운영자 취소(reassign=false)·주문 전체 취소로 끝내면 `DeliveryCancelledBeforePickup` 을 내지 않는다(`DeliveryTransitions.cancelBeforePickup`).
- **구독** `orderEvents-in-0` ← order.order.v1(`FulfillmentEventHandler.onOrderCancelled`, 2026-11-14): `OrderCancelled` scope FULL → 주문 전체 취소 — `stopped_order` 기록(뒤에 온 배차 요청은 BLOCKED) + 픽업 전 배송 취소. PARTIAL 은 남은 상품을 배차하므로 그대로. 처음 켜기 전 소비 그룹 시작 위치 2026-11-10T00:30Z.
- **구독** `executionEvents-in-0` ← delivery.delivery.v1(`ExecutionEventHandler`, 2026-11-13 PC 1 검토 H2): delivery-execution `DeliveryCompleted`·`DeliveryFailed`(운영자가 확정한 전달 실패) → ASSIGNED·PICKED_UP 배송을 CLOSED — 라이더 "진행 중" 수(ASSIGNED·PICKED_UP)에서 빠진다(사용자 "배달 완료 전까지 진행 중"). 이 서비스가 내는 사건은 종류로 거른다. 처음 켜기 전 소비 그룹 시작 위치 2026-11-10T00:30Z.
- **발행**(delivery.delivery.v1): DeliveryCreated(shipmentId, dropAddressRef = `order:<orderId>`)·DispatchOfferSent·DeliveryAssigned(assignmentVersion, 수락 당시 fee·vehicleType·distanceM — execution 수행료 기준 2026-11-13)·DeliveryCancelledBeforePickup·DispatchFailed. 주소·좌표는 싣지 않는다.
- **판단 주기** `DispatchEngine`(`pmart.dispatch.tick-interval` 5초): 만료 제안 EXPIRED(상세를 열었으면 거절 횟수 — 원문 §4.4) → 차례 된 시도를 MFC 별로 → 배차 가능 시간(08~24시 Asia/Seoul — 사용자 2026-11-13) 밖이면 다음 시작까지 → 좌표 준비 한 번(center-master CM-1 MFC 좌표, order O-4 배송지 → serviceability 정확 일치 좌표, tracking 카카오모빌리티 MFC → 고객 도로 거리 — 실패는 `geo_error` + 30초 뒤, 주소 없음·변환 0건·길 없음은 SYSTEM_FAILURE) → 후보(rider 적격 + AI 추천 모드 + 살아 있는 제안 없음 + 맡은 배송 < 3(하나씩 배달이면 1) + tracking 위치 2분 안 + 그 시도 단계의 **직선 반경** + 이번 회차 미제안) → 라이더 → MFC 카카오 도로 시간, 알뜰배달 고객 사이 카카오 도로 시간 → `BundleProblem`(**알뜰배달 BUNDLED 는 1~3건 묶음** — 원문 §5.3·사용자 2026-11-13 "묶음 배정으로 진행", 방문 순서는 순열 중 Σ도착 최소, 어느 배송이든 바로 갈 때보다 `bundle-max-extra`(설계값 20분) 넘게 늦으면 안 묶음. 한집·예약은 1건. 비용 = 라이더 → MFC + 출하 대기 대비 늦음×2/기다림 + Σ고객 도착) → `OrToolsAssignmentSolver`(CP-SAT 라이더 ↔ 묶음, 네이티브를 못 올리면 규칙 기반 + ERROR) → 배송마다 제안 1분(같은 `bundle_id`·`stop_seq`) + DispatchOfferSent. 못 지으면 `SearchRules`: 단계 1분 머묾 → 다음 단계 → 3단계 뒤 재요청(10분 뒤, 최대 2회, 회차가 바뀌면 거절한 라이더에게도) → 실패(REPEATED_REJECTION/EXPIRED/NO_RIDER).
- **라이더 API**(`OfferService`): `GET /offers`(만료 전 SENT, pollAfterSec — 신규배차 못 받으면 null), `GET /offers/{id}`(viewedAt), `POST /offers/{id}/responses`(배송 행 잠금 아래 `AcceptRules` — 한 명만, LATE 이유 4가지, 거절은 연속 거절 +1·5회 안내). **묶음은 전체가 한 번에** — 하나 수락이면 모두 배정(배송마다 DeliveryAssigned, `delivery.bundle_id`·`stop_seq`)·하나라도 안 되면 전체 LATE, 거절·만료도 전체(연속 거절 1회), 상세 조회는 묶음 전체 viewedAt. 일반 배차모드 `GET /delivery-list`(내 위치 → MFC 직선거리 ≤ 그 배송 단계 반경), `POST /delivery-list/{id}/acceptances`(배차 이력에 source DELIVERY_LIST 제안 한 줄).
- 예상 배달료 `domain/FeeTable` = 원문 §7.1 표(MFC → 고객 도로 거리, 구간 해석은 클래스 주석). 정산 수행료 정본은 execution.
- 조회: `GET /deliveries/{id}/dispatch`(그 센터 운영자·OPERATOR_ADMIN·배정/제안받은 라이더 — 라이더에게는 자기 제안만, 배정(라이더 ID)은 배정된 본인에게만 — L4), `GET /internal/deliveries/{id}/assignment`.
- PC 1 검토 작은 것(2026-11-13): L2 같은 요청 ID 가 다른 X-Source-System 으로 와 배차 취소 기록이 겹치면 500 대신 409 IDEMPOTENCY_CONFLICT(그 요청 전체 되돌림), L3 `DispatchOfferSent`·`DispatchDelayed` 도 배송 버전을 올려(`bumpVersion`) 사건마다 aggregateVersion 이 다르다, L5 배달리스트는 방금 배차취소한 라이더에게 그 배송을 안 보이고 수락도 INELIGIBLE, 위치 품질(GOOD·LOW_ACCURACY)도 AI 추천과 같이 본다.
- **AI 추천 계산(2026-11-13, ADR-0058 결정 13)**: `pmart.dispatch.ai.base-url` 이 있으면 먼저 `AiOptimizerClient`(Python `ai-services/delivery-dispatch-optimizer` — RouteFinder) → `BundleProblem.check` 로 검증(라이더·배송 중복, 묶음 크기, 2건 이상은 알뜰배달만, 용량·반경, 길 있음, 지연 한도 — 어긴 배정은 버림) → 통과한 묶음으로 제안(solver `AI ROUTEFINDER…`). 응답 없음(3초)·오류·전부 무효면 OR-Tools CP-SAT. 지표 `pmart.dispatch.ai.fallback`·`pmart.dispatch.ai.dropped`. 비우면 처음부터 OR-Tools.
- 아직 없음: 배달리스트에서 묶음째 고르기(지금은 한 건씩, 최대 3건), 묶음 배달료 할인·할증(지금은 배송마다 §7.1 표), 라이더 단계(RS-1 정지·계약 종료 → 배정 풀기), 라이더 계정 제한 경로(지원센터 백오피스·승인 — 결정 대기), 추가·분할 배차, 회수 배차(DD-2), 주류·보냉·용량 거르기.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.

## 이미지 (ADR-0053)
- **OR-Tools 사용 중(2026-11-13, `libs.ortools.java` — CP-SAT 배정)** → GraalVM Native 로 만들지 않고 JVM 으로 실행한다(ADR-0053 표 갱신함). PC 5 Windows 에서 네이티브 로드 확인(`OrToolsAssignmentSolverTest`).
