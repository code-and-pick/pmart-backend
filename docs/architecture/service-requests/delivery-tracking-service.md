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
