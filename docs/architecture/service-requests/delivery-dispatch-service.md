# delivery-dispatch-service 에 대한 요청 (MFC 쪽에서)

delivery-dispatch-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다. 구현을 시작할 때 아래를 함께 넣는다.

## DD-1. 배차 요청 받기 (2026-11-08, 사용자 결정 D-5)

- **결정**: 배차는 점원이 포장을 시작할 때 예상 포장 시간(분)과 함께 요청한다(Delivery §3.1 — 라이더가 포장이 끝날 즈음 도착). 포장이 끝날 때까지 요청이 없으면 출하 대기(READY)가 되는 순간 자동으로 요청한다. 출고당 한 번.
- **MFC 가 주는 것(구현됨, mfc-fulfillment 0.4.5)**: `DispatchRequested`(mfc.fulfillment.v1, 집계 Shipment = shipmentId) — orderId·shipmentId·mfcId·trigger(MANUAL/AUTO_ON_READY)·estimatedPackMinutes(MANUAL)·estimatedReadyAt·requestedAt·requestedBy. 그 뒤 `PackingFinished`·`ShipmentReady`(포장 목록·무게·봉투)는 그대로 나간다. **2026-11-13 부터 점원 요청만(trigger 늘 MANUAL)** — 요청 없이 READY 가 된 출고는 `DispatchRequested` 가 없다(점원이 READY 뒤에 요청할 수 있음, mfc-fulfillment 0.4.11).
- **delivery-dispatch 가 할 것**
  1. `DispatchRequested` 구독 → 배송 생성(DeliveryCreated, shipmentId 포함 — MFC 는 이것으로 배정을 투영한다) → `estimatedReadyAt` 에 맞춰 라이더 후보 탐색·제안(DispatchOfferSent).
  2. `ShipmentReady` 가 먼저 오는 경우(자동 요청과 같은 순간)도 같은 출고 하나로.
  3. **포장 중 결품·취소**: 출고가 취소되면(주문 취소 → mfc `WorkStopResult`, 결품으로 출고 수량이 줄면 `PackingFinished` 내용) 배차를 취소·조정한다. 배정된 라이더가 있으면 `DeliveryCancelledBeforePickup`(MFC 는 이것으로 배정을 푼다).
  4. 라이더 단계(D-4): 일시 중지·계약 종료 라이더의 배정을 풀고(`DeliveryAssignmentChanged`) 재배정. 신규배차 제한 라이더는 배정분 그대로.
- **MFC 쪽 보충(delivery-dispatch 가 생기면)**: 없음. 필요하면 점원 화면에 라이더 ETA(`Shipment.riderEta`, 지금 비어 있음)를 delivery 사건으로 채운다.

- **진행(2026-11-13, delivery PC 5 — DD-1 첫 묶음)**: 1(구독 → 배송 생성 DeliveryCreated·제안 DispatchOfferSent·수락 DeliveryAssigned)·2(ShipmentReady 먼저 와도 출고 하나 — 단 **배차는 점원 요청 DispatchRequested 로만 시작**, BR-51 2026-11-13: ShipmentReady 만 오면 CREATED 로 기록만)·3(WorkStopResult 모든 포장 STOPPED → DeliveryCancelledBeforePickup) 구현. 결품으로 줄어든 포장은 PackingFinished 로 목록만 갱신(조정 배차 없음). 4(라이더 단계 → 배정 풀기)는 **2026-11-14 구현**(RS-1 — `RiderRestricted` 구독, 모든 단계 픽업 전 배정 풀고 재배차·픽업한 배송은 전달까지, 사용자 2026-11-14). 상세 `services/delivery-dispatch-service/CLAUDE.md`.

## DD-1 에 덧붙임 (2026-11-09 조사)
- S3 취소 사가의 배차 취소 단계 — 모든 포장이 STOPPED 일 때만(`package-handover-contract.md`, `cancel-vs-handover.md`, orchestration `CancellationRules`).
- ~~DeliveryType 불일치~~ — 2026-11-10 결정: FORWARD/RETURN/RESHIPMENT 로 통일(사건 스키마 수정).
- ~~DispatchFailed 사유 값~~ — 2026-11-10 결정: NO_RIDER/REPEATED_REJECTION/EXPIRED/SYSTEM_FAILURE.
- 첫 기동 전 `mfc.fulfillment.v1` 소비 그룹 시작 위치 = 2026-11-10T00:30Z, 출고당 배송 하나(멱등) — `delivery-kickoff-from-mfc.md` §6.

## DD-2. 확인된 오배송 — 회수 라이더 자동 배차 (2026-11-09, ADR-0052)
- 오배송이 확인되면(라이더 확인·사진 위치 다름·잘못 받은 사람 연락) **인근 다른 라이더**를 자동 배차해 오배송지에서 회수하고 MFC 로 반납한다(원래 라이더는 다른 코스로 이동했을 가능성이 높음).
- 같은 때 MFC 의 새 발송(원주소)은 일반 배차 요청(DD-1)으로 따로 나간다 — 회수품을 원주소로 다시 보내지 않는다.
- 필요한 것: 오배송 확인 사건(누가 기록하나 — ADR-0052 남은 것), 회수 배송 유형(픽업지 = 오배송지, 도착지 = MFC), 인근 라이더 탐색. DD-1 구현 뒤.
- **결정(2026-11-15, 사용자 "추천대로")**: 오배송 확인은 **상담원·지원센터가 확인해서 기록**한다(case — 요청 `case-service.md` R-7). 그 사건이 오면 회수 배차는 자동. 라이더 앱만으로 확정하지 않는다.

## DD-2 갱신 (2026-11-16, 보낸 영역 platform) — 오배송 확인 사건 생김
- case-service 가 **`MisdeliveryConfirmed`** 를 낸다(case 0.4.0, 계약 `contracts/events/MisdeliveryConfirmed.v1.json`, 토픽 platform.case.v1, 키 caseId): caseId, deliveryId, riderId(그때 배정 라이더 — 없을 수 있음), confirmedBy, confirmedAt, evidenceRefs. 주소·좌표 원문은 없다.
- dispatch 가 구독해 회수 배송(픽업지 = 오배송지, 도착지 = MFC)·인근 다른 라이더 배차(DD-2)를 만든다. 오배송지 좌표가 필요하면 tracking 위치·execution 증빙에서(사건에는 없음).


## 확인 요청 (2026-11-16, 보낸 영역 backoffice) — LAN 시험 배송이 배차 실패로 미배정
- **본 것**: LAN 시험 주문 `ORD-0e67de38-7acf-3e71-8a28-7aeadd986bb0` 의 배송 `DLV-74422b51-7820-457d-8dbf-9d66395010a2` — 백오피스 배송 상세에서 수행 "미배정", 배차 "배차 실패", 시도 "2026-11-14 02:05 · FAILED · 시스템 오류", 배정 라이더 없음. 그 뒤 다시 시도한 기록이 없다.
- **궁금한 것**: 실패 원인(LAN 에 라이더가 없어서인지 — rider-service 확인 요청 참고 — 아니면 다른 오류인지), 다시 배차할 길(자동 재시도·운영자 재요청)이 있는지. 이 배송은 처리 대기(BO-12)·사건함 어디에도 나오지 않는다 — 배차 실패를 운영자가 볼 곳이 필요하면 알려 주면 사건함 종류(`DISPATCH_FAILED`, case 계약에 이름만 있음)로 받는 것을 사용자에게 묻겠다.
- **갱신(같은 날)**: 사용자 결정으로 case 가 `DispatchFailed` 를 사건함(`DISPATCH_FAILED`, 지원센터·10분)으로 받는다 — 시스템 장애는 첫 실패, 그 밖은 두 번째 실패부터(`service-requests/case-service.md` 끝). dispatch 쪽 변경은 필요 없다. 원인·재배차 방법 질문은 그대로.

## DD-MFC. `DispatchFailed` 에 센터(`mfcId`) 싣기 (2026-11-16, 보낸 영역 backoffice)
- **왜**: 사용자 결정(2026-11-16 "추천대로") — 센터 관리자(MFC_SUPERVISOR)가 **자기 센터의 배차 실패 사건**을 백오피스 사건함에서 본다(배송 원문 §3.6 "배차 지연·불가는 MFC 관리자·고객센터에 안내"). case 0.5.2 는 사건의 센터를 `DispatchFailed.payload.mfcId` 로 정하는데, 지금 payload 에 없다(deliveryId·orderId·reason·attempts·failedAt) → 센터 관리자에게 하나도 안 보인다.
- **할 것(작음, 필드 추가만)**: `DispatchEvents.failed(...)` 가 `p.put("mfcId", before.mfcId())` — 계약 `contracts/events/DispatchFailed.v1.json` payload 에 `mfcId`(선택, `_defs` CenterId) 추가(필드 추가라 하위 호환). `DispatchDelayed` 도 같은 이유로 있으면 좋다(지금 사건함은 구독 안 함).
- **case 쪽**: 이미 읽는다(값이 없으면 센터 없음 → 지원센터·관리자만 봄). 바꿀 것 없음.
