# delivery-tracking-service 에 대한 요청 (MFC·주문 쪽에서)

delivery-tracking-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다.

## TR-1. 라이더 ETA (2026-11-09 기록)

- **요청한 곳**: mfc-fulfillment(점원 화면 `Shipment.riderEta`, 지금 비어 있음 — DD-1 에서 선택 사항), order(고객 조회 ETA, `events.md`).
- **필요한 것**: `DeliveryEtaUpdated` 발행. 받는 쪽 구독은 각 서비스가 붙인다.
- **사용자 결정**: 필요 없음(선택 기능) → 2026-11-16 사용자 "진행해"(PC 5 다음 할 일 추천 3번)로 만듦.
- **구현(2026-11-16, PC 5)**: tracking V4·`EtaService` — 진행 중 배송마다 `pmart.tracking.eta.interval` 간격으로 라이더 마지막 위치(3분 안·정상 품질)에서 카카오 길찾기(라이더당 한 번 — 한 출발지 → 여러 도착지). 픽업 전 etaPickup = 라이더 → 픽업지, etaDrop = etaPickup + 픽업지 → 도착지(배차가 잰 도로 시간, 머무는 시간 없음, basis `RIDER_TO_PICKUP+PICKUP_TO_DROP`), 픽업 뒤(PickupCompleted·ReturnPickedUp) etaDrop = 라이더 → 도착지(`RIDER_TO_DROP`, 묶음도 바로 가는 시간). 처음과 `min-change` 넘게 바뀔 때만 `DeliveryEtaUpdated`(delivery.tracking.v1, aggregate Delivery). 좌표는 dispatch 0.4.4 `GET /internal/deliveries/{id}/route-points`(한 번 읽어 진행 중 동안만 보관). **두 설정값이 없으면 꺼짐**(카카오 사용량 — PC 1 이 켤 때 정한다). 받는 쪽(order 고객 조회·mfc 점원 `riderEta`·notification 지연)은 아직 구독 안 함.

## TR-2. 위치 끊김·위치 보존 기간 (2026-11-14, delivery PC 5 — 사용자 결정)
- **결정(사용자 2026-11-14)**: 배달 중(배정 ~ 완료·실패 확정 전) 위치가 **3분** 넘게 안 오면 끊김("그대로 해" — 추천값), 위치 기록 **보존 30일**.
- **받는 쪽 결정(2026-11-15, 사용자 "추천대로")**: 지원센터(운영자)에게 **알림만**, 배정 자동 해제 없음 → dispatch 는 구독하지 않고 case 가 받는다(`case-service.md` R-6).
- **구현**: tracking V3 — `delivery.delivery.v1` 구독(`deliveryEvents-in-0`: 배정·배정 변경·픽업 전 취소·완료·실패 → 진행 중 배송), `RiderWatch` 30초마다 판정 → `RiderLocationStale`(delivery.tracking.v1, 끊김 한 번에 한 번, 기준 = 마지막 수신(품질 무관)과 배정 시각 중 늦은 것). 1시간마다 서버 수신 30일 지난 위치 점·그동안 새 위치 없는 마지막 위치·닫힌 배송 기록 지움.
- **받는 쪽(아직)**: dispatch·case 는 이 사건을 구독하지 않는다(dispatch 는 이미 위치 2분 기준으로 후보에서 뺀다). 지원센터 알림은 case(R-4 와 같이, PC 1).

## TR-3. 고객·직원 ETA 조회·실시간 구독 API (2026-11-17, platform PC 1 → delivery PC 5)
- **요청한 곳**: 고객앱(배송 현황 화면 — 앱은 이미 `GET /deliveries/{id}/eta`·`/stream` 을 부르고, 실패하면 "확인 중"·폴링으로 버틴다), 백오피스 실시간 배송(`latestEta`, BFF). 근거: 고객앱 레퍼런스 대비 빠진 기능 `docs/handoff/clients/customer-app/2026-11-17-notice-reference-gap.md` B-1.
- **사용자 결정(2026-11-17)**: "PC 5에 요청" — tracking 은 delivery 영역이라 PC 1 이 고치지 않는다. 백엔드 추천 순서 1번(도착 예정 시간·실시간 위치).
- **필요한 것**(계약 `delivery-tracking-service.yaml` 에 이미 있음 — 지금 501):
  - `GET /deliveries/{deliveryId}/eta` — 고객은 **본인 주문 배송의 dropEta 만**, 직원(그 센터 운영자·`OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS` — execution `Access` 와 같게)은 pickupEta·dropEta. 남의 배송은 404. 라이더 좌표는 고객에게 주지 않는다. 라이더 미배정·위치 미수신·길찾기 실패·**ETA 계산 꺼짐**이면 status `LOCATING`/`ETA_PENDING`, 수치 비움.
  - `GET /deliveries/{deliveryId}/stream`(SSE) — 같은 범위로 ETA 갱신·진행 단계, 끝나면 서버가 닫음, `Last-Event-ID` 재개.
  - 고객 소유 확인: 배송 → 주문 → 고객. 조회 방법(execution·order 내부 API 또는 배송 사건 사본)은 PC 5 가 정한다.
- **PC 1 쪽(같이 할 일)**: ETA 계산 켜기 — `pmart.tracking.eta.interval`·`min-change` 값(카카오 길찾기 사용량)은 사용자 결정 뒤 pmart-config 에 넣는다. 게이트웨이 경로 `/api/v1/deliveries/**` 가 tracking 의 `/eta`·`/stream` 으로 가는지(SSE 버퍼링 끄기 포함) 병합 때 PC 1 이 확인한다.
- **상태**: **구현(PC 5, 2026-11-17 — tracking 0.5.0, V5, 브랜치 `delivery/tr-3-eta`)**. 고객 소유 = order 내부 `GET /internal/orders/{id}` accountId(배송 사본의 주문으로 — 사본은 tracking 이 배송 사건으로 만든다, 배포 전 배송은 404). 스트림은 SSE 만(WebSocket 없음) — 3초마다 사본을 읽어 바뀐 것만, 15초 HEARTBEAT, 끝나면 STREAM_END 뒤 닫힘, 30분 넘으면 닫힘(다시 접속). ETA 값은 PC 1 이 `pmart.tracking.eta.*` 를 넣어야 나온다(그 전 ETA_PENDING). 게이트웨이는 이미 `/api/v1/deliveries/*/eta`·`/stream` → tracking — SSE 버퍼링은 PC 1 확인.

