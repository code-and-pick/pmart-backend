# delivery-tracking-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-tracking-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-tracking-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.tracking.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.tracking.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-tracking-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-tracking-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8406.

## 상태
2026-11-13 DD-1 첫 묶음(계약 0.2.0, V2): 위치 배치 수신·내 마지막 위치·내부 라이더 위치(하나·여럿)·거리 행렬 구현. ETA 조회 API·실시간 구독은 501. ETA 사건은 아래 TR-1.
- 위치: 단말 시각 순 저장, `domain/LocationQuality`(LOW_ACCURACY `pmart.tracking.low-accuracy-m` 기본 100m·TIME_SKEW 시계 앞섬 `max-clock-ahead` 1분 또는 시간 역전). 버리지 않고 표시만, 마지막 위치(`rider_latest_location`)는 더 늦은 정상 점만. 같은 라이더 업로드는 advisory lock 으로 줄 세움.
- 거리 행렬 = 카카오모빌리티 길찾기(`infrastructure/KakaoMobilityClient`, ADR-0058) — 도착지 하나면 다중 출발지, 아니면 출발지마다 다중 목적지. **직선거리로 대신하지 않는다**: 키 없음·401/403 → 422 CONFIG_REQUIRED, 429·400 code -10(카카오 일 사용량 초과의 실제 응답 — 2026-11-14) → 429 QUOTA_EXCEEDED(dispatch 가 quota-backoff 5분), 그 밖 4xx·5xx·시간 초과 503. 구간 결과 코드 ≠ 0 은 NO_ROUTE. 자동차 도로 기준(수단별 경로 없음).
- 키 `pmart.tracking.kakao-mobility.rest-api-key` — pmart-config `delivery-tracking-service-<프로필>.yml` {cipher}(PC 1). 로컬은 환경변수로.
- **위치 끊김·보존(2026-11-14, V3 — 사용자 "배달 중 3분"·"보존기간은 30일")**: 구독 `deliveryEvents-in-0` ← delivery.delivery.v1(배정·배정 변경·픽업 전 취소·완료·실패만) → `rider_active_delivery`(ACTIVE / RELEASED 배정 해제 — 행과 배정 버전을 남겨 늦게 온 옛 배정이 다시 열지 않게, 더 높은 버전만 다시 연다 / EXPIRED 배정 뒤 `watch-max-age`(5시간, 사용자 2026-11-14 "5시간이 지나면 감시 종료") 지나 끝 / CLOSED 완료·실패·취소 — 다시 열지 않음). **감시 시작 `since` = 사건에 적힌 배정 시각**(acceptedAt·changedAt — 소비한 시각이 아니다, PC 1 PR #15 검토: 옛 사건을 다시 읽어도 지금 배정된 것처럼 되지 않게). 업로드마다 `rider_signal.last_received_at`(품질 무관). `RiderWatch.checkStale`(`stale-check-interval` 30초): 진행 중 배송이 있고 기준(마지막 수신·배정 시각 중 늦은 것)이 `stale-after`(3분) 지났으면 `RiderLocationStale`(**토픽 delivery.tracking.v1** — 계약대로 명시, aggregate Rider, 버전 = 알림 횟수 — `UPDATE … RETURNING alert_count` 로 올리면서 읽는다) — 끊김 한 번에 한 번(`alerted_for`), 알림만 하고 상태는 안 바꾼다. `RiderWatch.purge`(`purge-interval` 1시간): 서버 수신 `retention`(30일) 지난 점(5000행씩)·그동안 새 위치 없는 마지막 위치·감시 중이 아닌(RELEASED·EXPIRED·CLOSED) 배송 기록 지움. 처음 켜기 전 소비 그룹 시작 위치 2026-11-10T00:30Z.
- 아직 없음: 권역 밖(OUT_OF_ZONE) 판정, ETA 사건 받는 쪽(조회 API 는 아래 TR-3), 끊김 사건 받는 쪽 — 사용자 2026-11-15 "추천대로": 지원센터 알림만(case R-6), 배정 자동 해제 없음이라 dispatch 는 구독하지 않는다.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.
- **정한 시각의 위치 점(0.4.0, 2026-11-16 — DD-2 오배송 회수지)**: `GET /internal/riders/{id}/location-at?at=` — `pmart.tracking.location-at-window`(3분) 앞뒤에서 단말 기록 시각이 가장 가까운 점(품질 무관), 없으면 404. dispatch 가 원래 라이더가 전달 완료를 누른 곳을 회수지로 쓴다. `ReturnDelivered`(회수 반납)도 감시 끝 사건.
- **라이더 ETA(TR-1, 2026-11-16 — 사용자 "진행해", V4)**: `EtaService.tick`(`eta-check-interval` 15초) — `rider_active_delivery` 에 픽업 시각(PickupCompleted·ReturnPickedUp)·픽업지/도착지 좌표(dispatch `route-points` 사본, 진행 중 동안만)·마지막 낸 도착 예정·사건 버전. 라이더 마지막 위치(`stale-after` 안, GOOD·LOW_ACCURACY)에서 카카오 `fromOne`(라이더당 한 번) → 픽업 전 etaPickup + 픽업지→도착지 시간 / 픽업 뒤 라이더→도착지. 처음과 `eta.min-change` 넘게 바뀔 때만 `DeliveryEtaUpdated`(delivery.tracking.v1). **`pmart.tracking.eta.interval`·`min-change` 가 없으면 꺼짐**(카카오 사용량 — 직선거리 대체 없음, 길찾기 실패·초과면 그 회차 건너뜀). 좌표 조회 주소 `pmart.tracking.dispatch-base-url`(기본 `http://delivery-dispatch-service:8404`).
- **고객·직원 ETA 조회·실시간 구독(TR-3, 2026-11-17 — 사용자 "PC 5에 요청", 계약 0.5.0, V5)**: `ProgressService` — 배송 사건(DeliveryCreated·배정·배정 변경·가게 도착·픽업·회수지 받음·고객 도착·완료·실패·픽업 전 취소·회수 반납, 같은 `deliveryEvents` 소비)으로 `delivery_progress` 사본(주문·센터·도착 시간대·단계 — `domain/ProgressRules` 앞으로만, 픽업 전 배정 해제·재배정만 되돌림, DONE 끝). `GET /deliveries/{id}/eta` — 고객은 배송 → 주문 → order 내부 `GET /internal/orders/{id}` accountId = 토큰 sub 일 때만(`infrastructure/OrderClient`, `pmart.tracking.order-base-url` 기본 `http://order-service:8201`), dropEta 만. 직원은 execution `Access` 와 같은 범위(아니면 404), pickupEta(픽업 전)도. 라이더·서비스 403. ETA 값은 TR-1 의 `eta_pickup`(V5)·`eta_drop` — 계산이 꺼져 있으면 ETA_PENDING, 위치 끊김이면 LOCATING, DONE 이면 FINAL. 라이더 좌표는 주지 않는다. `GET /deliveries/{id}/stream`(SSE, `api/EtaStreams`) — 생성 메서드가 `ResponseEntity<StreamEvent>` 라 서블릿 비동기로 직접 쓴다: 접속 때 권한 판정 → 지금 상태 → `stream-poll-interval`(3초)마다 다시 읽어 STAGE_CHANGED·ETA_UPDATED, `stream-heartbeat`(15초) HEARTBEAT, DONE·`stream-max-duration`(30분)이면 STREAM_END 뒤 닫음, `stream-max-connections`(1000) 넘으면 503 STREAM_LIMIT. id = 상태 지문(Last-Event-ID 같으면 첫 이벤트 생략). 이 배포 전에 만들어진 배송은 사본이 없어 404. 사본은 DONE 뒤 `retention` 지나 지움. WebSocket 없음.
- **라이더 위치 공개(2026-11-17 — 0.6.0, 사용자 결정 "픽업 뒤 정확한 위치", 고객앱 빠진 기능 C)**: `/eta`·`/stream` 의 `riderLocation`(lat·lng·recordedAt·staleSeconds, `riderLocationQuality`) — 라이더 마지막 위치 한 점(동선 없음). 고객은 TO_CUSTOMER·AT_CUSTOMER 만, 직원은 TO_STORE 부터, DONE·미배정은 null(`ProgressService.showsRider`). 스트림 지문에 위치 기록 시각을 넣어 위치가 바뀌면 ETA_UPDATED.
- **실시간 구독 전용 스레드(2026-11-17, PC 1 #75 검토 후속)**: `EtaStreams` 갱신은 `@Scheduled` 공용 스레드가 아니라 자기 스레드(`eta-stream-poll`, `stream-poll-interval`)에서 돈다 — 멈춘 클라이언트에 쓰다 막혀도 ETA 계산·위치 끊김 감시가 늦어지지 않게. **쓰기는 쓰기 스레드 묶음**(`stream-write-threads` 4, PC 1 #89 검토 후속): 갱신 스레드는 보낼 것만 정하고, 연결마다 한 번에 하나씩 쓴다 — 앞 쓰기가 안 끝난 연결은 그 회차를 건너뛰고 `stream-write-timeout`(30초) 넘게 막히면 닫는다. 멈춘 연결 하나가 다른 구독 갱신을 늦추지 않는다(`EtaStreamsTest`).
- **고객 반품 회수 실패(2026-11-17, CL-1)**: `ReturnPickupFailed` 도 진행 끝(DONE·FAILED, `ProgressRules`)·위치 감시 끝(`RiderWatch`).
- **라이더 본인 도착 예정(2026-11-17, 0.7.0 — 라이더 앱 요청 §16-4 "앱을 다시 켜면 예정 시각이 없다")**: `/eta`·`/stream` 에 RIDER — 지금 배정된 라이더(`delivery_progress.rider_id` = 토큰 sub)만 직원과 같은 범위(픽업 예정·도착 예정·자기 위치), 남의·배정 풀린 배송 404. 구독은 `ProgressService.refresh(…, viewerRider)` 가 배정이 바뀌면 null → 닫힘(남의 위치를 보지 않게).
- **라이더 관제(2026-11-17, 계약 0.8.0 — ADR-0068)**: `GET /rider-locations?mfcId=`(`RiderControlService`·`ControlController`·`RiderClient`) — RIDER_OPS·OPERATOR_ADMIN 만(그 밖 403). 라이더는 매 요청 rider `/internal/riders/on-duty`(설정 `pmart.tracking.rider-base-url`, 기본 `http://rider-service:8403`)가 정함 → 운행 중만, 위치 없는 라이더도 나옴(stale). 마지막 위치·끊김 초(`stale-after` 기준)·감시 중 배정(`LocationStore.activeOf` — 단계·ETA). 이력 없음, no-store, 좌표 로그 금지.
- **상태 정정 반영(2026-11-17)**: `DeliveryStatusCorrected` → `ProgressRules.stageOfState(to)` 로 진행 단계를 맞춘다(뒤로도, DONE 이면 그대로).
- **고객에게 받았는지 묻기(2026-11-17, V6)**: `ReceiptCheck`(`receipt-check-interval` 1분, `receipt-check-after` 20분) — 진행 단계 TO_CUSTOMER·AT_CUSTOMER 이고 아직 안 물은 배송, 기준 = 라이더 ETA(`eta_drop`) 없으면 약속 시간대 끝 → `DeliveryReceiptCheckRequested`(delivery.tracking.v1, 버전 = eta_version) 한 번(`receipt_asked_at`).
- **관제 운행 시작·대기(2026-11-18, 0.9.0)**: `ControlRider.onDutySince`(rider 값 그대로)·`waitingSince`(`RiderControlService.waitingSince` — 신규배차 켬·진행 중 0건이면 (켠 시각, `LocationStore.lastEndedOf` 마지막 끝난 배송) 중 늦은 때).
- **관제 라이더 번호(2026-11-18, 0.10.0 — ADR-0070)**: `ControlRider.riderNumber`(rider 운행 중 목록 값 그대로).
- **관제 배송 번호(2026-11-18, 0.11.0·V7 — ADR-0070)**: `delivery_progress.delivery_seq`(DeliveryCreated.deliverySeq)·`order_number`(DeliveryAssigned.orderNumber) 사본 → `ControlRider.activeDeliveries[].deliveryNumber`.
- **라이더 위치 실시간(2026-11-19, 0.11.1 설명 — 사용자 "고객앱 카카오맵에 실시간")**: SSE 가 위치 시각이 바뀌면 ETA_UPDATED 로 riderLocation 을 보냄(이미 구현 — 지문에 위치 시각). 라이더 앱 배송 중 5초·고객 앱 SSE 로 마커(알림 `docs/handoff/clients/all/2026-11-19-notice-live-rider-location.md`).
- **실패 확정 되돌리기(2026-11-19)**: `DeliveryFailureReverted` → 진행 단계 DONE → restoredState 단계(끝에서도), 위치 감시 다시(`LocationStore.reopenDelivery`).

- **관제 위치 공유(2026-11-19, 0.12.0 — 사용자 "신규배차도 끄고 배송 중인 것도 없으면 위치 공유가 안 되어야")**: `RiderControlService.shares` — 진행 중 배송이 있거나 신규배차 켬일 때만 `location`, 쉬는 중이면 null·`locationShared` false·stale false. 라이더 앱도 그때 올리지 않게 요청.
- **TR-4 곧 도착(2026-11-19, V8 — 사용자 300m)**: `ApproachWatch`(`approach-check-interval` 10초) — 픽업 뒤 감시 중 배송, 라이더 마지막 위치(정상·안 끊김)가 전달지 직선 `approach-distance-m` 안 처음이면 `RiderApproachingCustomer`(delivery.tracking.v1 — deliveryId·orderId·purpose(DeliveryCreated 사본 `delivery_progress.purpose`)·distanceM·at, 배송당 한 번 `approach_notified_at`). **설정 없으면 꺼짐** — pmart-config 에 300(PC 1).
