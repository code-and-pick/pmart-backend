# delivery-execution-service 에 대한 요청 (MFC 쪽에서)

delivery-execution-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다. 구현을 시작할 때 아래를 함께 넣는다.

## DE-1. 픽업 완료 판단 (2026-11-08, 사용자 결정 D-6) — **완료 2026-11-13**(계약 0.2.0, `PickupJudgement`)

> 처리: 2·3·4 는 이 서비스가 한다 — PackageHandedOver(점원 확인 TAKEN_BY_RIDER 포함, 같은 규칙) + 출고 포장 목록(ShipmentReady·PackingFinished)으로 모든 포장이 인계됐을 때만 PICKED_UP, 버튼(`POST …/pickups`)은 확인만. 1(포장별 handover-commits 호출·409 다시 찍기 안내)은 라이더 앱 몫 — `docs/handoff/clients/rider-app/2026-11-13-notice-execution-apis.md`.

- **결정**: 라이더 앱의 "픽업 완료" 버튼이 포장마다 MFC 인계 확정(`POST /api/v1/packages/{id}/handover-commits`)을 부르고, 배송 상태 PICKED_UP 은 **그 배송의 모든 포장 `PackageHandedOver` 가 왔을 때만** 바꾼다 — 버튼만 눌리고 재고는 안 넘어간 상태가 생기지 않게.
- **MFC 가 주는 것(구현됨)**: 인계 허가·확정 API(6c, ADR-0047), `PackageHandedOver`(mfc.fulfillment.v1, packageId·shipmentId·deliveryId·riderId·inventoryCommitId·lines). 확정 응답이 UNKNOWN_RESULT 면 MFC 가 같은 인계 ID 로 다시 보내고, 결과는 사건으로 온다.
- **delivery-execution 가 할 것**
  1. 픽업 완료 버튼 = 포장별 handover-commits 호출(라이더 토큰). 일부 포장이 409(만료 등)면 그 포장은 다시 찍기 안내(같은 라이더는 확인 없이, D-1 안 C).
  2. `PackageHandedOver` 구독 → 배송의 포장 전부가 오면 PickupCompleted(PICKED_UP). 버튼만으로 PICKED_UP 하지 않는다.
  3. 점원 확인 TAKEN_BY_RIDER(D-1 — 라이더가 확정 없이 들고 간 포장)로 온 `PackageHandedOver` 도 같은 규칙.
  4. 순서 "가게 도착 → QR → 픽업 완료"는 라이더 앱·이 서비스가 지킨다(D-3 — MFC 는 도착을 보지 않음).
