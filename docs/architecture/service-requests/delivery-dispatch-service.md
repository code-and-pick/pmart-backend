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

## DD-2 구현 (2026-11-16, PC 5 — 브랜치 `delivery/dd-2-misdelivery-recovery`)
- **사용자 결정(2026-11-16 "추천대로")**: (a) 회수 라이더 수행료 = 일반 배달 표(원문 §7.1) 그대로, 거리 = 회수지 → MFC 도로 거리(원배송 수행료와 별도). (b) 회수지 = 원래 라이더가 전달 완료를 누른 곳(tracking 위치 기록), 없으면 원래 배송지 좌표 — 정확한 호수는 지원센터가 전화로. 회수 라이더에게 원래 완료 증빙(놓은 곳·사진 참조)을 보여 준다. (c) 회수 실패(부재·거부)는 도움요청 → 지원센터 사건함, 자동 재시도 없음(끝내기는 운영자 배차 취소 reassign=false). (d) MFC 반납 = mfc-inbound 점원 수령 확인이 생기기 전까지 MFC 도착으로 끝.
- **dispatch 0.4.0**(V8): `caseEvents-in-0` ← platform.case.v1 `MisdeliveryConfirmed` → 회수 배송(type RETURN, purpose MISDELIVERY_RECOVERY, 배송 ID 는 caseId 로 정함 — 같은 사건은 하나, shipment `RCV-<caseId>`) + 시도(원래 라이더 제외) + DeliveryCreated(purpose·caseId·originDeliveryId, dropAddressRef `mfc:<mfcId>`). 원래 배송이 없거나 픽업 전이면 만들지 않는다(경고 — 사건함에서 사람이). 좌표 준비 = tracking `GET /internal/riders/{원래 라이더}/location-at?at=<완료 시각>` → 없으면 원래 배송지 좌표, 회수지 → MFC 카카오 거리. 판단은 따로(회수지 중심 반경, 원래 라이더·이번 회차 제외, 회수지까지 가장 빠른 사람 1명, 묶지 않음, 배달리스트에 안 나옴). `GET /deliveries/{id}/dispatch` 에 `purpose`·`recovery`(caseId·originDeliveryId, 회수지 좌표는 배정 라이더·직원만, originRiderId 는 직원만), 제안에 `deliveryType`·`purpose`. `ReturnPickedUp` → PICKED_UP, `ReturnDelivered` → CLOSED. 전달 완료 시각 `completed_at`(회수지 기준).
- **execution 0.5.0**(V7): 회수 배송 수행 — 배정 → 회수지에서 사진(ASSIGNED 에서 올릴 수 있음) → `returns/pickups`(RETURN_PICKED_UP, ReturnPickedUp — lines = 원래 배송이 전달한 줄) → `returns/store-deliveries`(RETURNED_TO_STORE, ReturnDelivered, 수행료 = 수락 당시 금액). 회수 배송에 일반 단계는 409, 전달지는 409 NO_CUSTOMER_DROP(원래 고객 정보 안 엶). 조회에 purpose·caseId·originDeliveryId·recoveryOrigin(원래 완료 증빙).
- **tracking 0.4.0**: `GET /internal/riders/{id}/location-at?at=`(앞뒤 3분 안 가장 가까운 점), `ReturnDelivered` 로 회수 라이더 감시 끝.
- **사건 계약**: `DeliveryCreated` purpose·caseId·originDeliveryId 추가. `ReturnPickedUp`·`ReturnDelivered` — claimId 를 필수에서 뺌(구독자 구현 전 확인), purpose·caseId·originDeliveryId·orderId(·mfcId) 추가.
- **같은 날 더함(사용자 2026-11-16 "원래 고객의 전화는 회수 라이더에게 보여야 해", "사진보기 기능 넣어")**: execution `GET /deliveries/{id}/recovery-contact`(회수 라이더 — 원래 고객 전화번호만), `GET /deliveries/{id}/evidence-photos/{photoRef}`(사진 원본 — 배정 라이더·회수 라이더·직원).
- **남은 것**: MFC 점원 수령 확인(mfc-inbound MI-1), 새 발송(order O-3), 회수 라이더 앱 화면(handoff `docs/handoff/clients/rider-app/2026-11-16-request-misdelivery-recovery.md`).


## 확인 요청 (2026-11-16, 보낸 영역 backoffice) — LAN 시험 배송이 배차 실패로 미배정
- **본 것**: LAN 시험 주문 `ORD-0e67de38-7acf-3e71-8a28-7aeadd986bb0` 의 배송 `DLV-74422b51-7820-457d-8dbf-9d66395010a2` — 백오피스 배송 상세에서 수행 "미배정", 배차 "배차 실패", 시도 "2026-11-14 02:05 · FAILED · 시스템 오류", 배정 라이더 없음. 그 뒤 다시 시도한 기록이 없다.
- **궁금한 것**: 실패 원인(LAN 에 라이더가 없어서인지 — rider-service 확인 요청 참고 — 아니면 다른 오류인지), 다시 배차할 길(자동 재시도·운영자 재요청)이 있는지. 이 배송은 처리 대기(BO-12)·사건함 어디에도 나오지 않는다 — 배차 실패를 운영자가 볼 곳이 필요하면 알려 주면 사건함 종류(`DISPATCH_FAILED`, case 계약에 이름만 있음)로 받는 것을 사용자에게 묻겠다.
- **갱신(같은 날)**: 사용자 결정으로 case 가 `DispatchFailed` 를 사건함(`DISPATCH_FAILED`, 지원센터·10분)으로 받는다 — 시스템 장애는 첫 실패, 그 밖은 두 번째 실패부터(`service-requests/case-service.md` 끝). dispatch 쪽 변경은 필요 없다. 원인·재배차 방법 질문은 그대로.
- **회신(2026-11-16, PC 5)**:
  - **원인**: 라이더가 없어서가 아니라 **배송지 주소가 비어 와서**다. 주문이 02:05(배차 가능 시간 08~24시 밖)에 들어와 08:00 에 첫 시도(AUTO) → order 배송지 조회(O-4)의 도로명주소가 늘 null 이던 버그로 좌표를 못 구해 `SYSTEM_FAILURE`(주소 없음). 시스템 장애는 자동 재요청 없이 DISPATCH_FAILED 로 멈춘다(dispatch 규칙 — 라이더 없음은 10분 뒤 자동 재요청). 버그는 #15(2026-11-15 배포)에서 고쳤다.
  - **다시 배차할 길**: 운영자 `POST /api/v1/deliveries/{deliveryId}/assignment-cancellations` `reassign=true`(OPERATOR_ADMIN·RIDER_OPS, MFC 관리자는 자기 센터). DISPATCH_FAILED 도 이 길로 새 시도(REASSIGN)가 열린다.
  - **이 배송은 끝났다**: 2026-11-16 13:35 PC 5 LAN 라이더 E2E(`deploy/lan/checks/rider_e2e.py`)가 위 길로 다시 배차 → 시험 라이더 `RD-claude-97fb21e37325` 수락 → 포장 QR 인계 → 픽업 → 전달 완료. 지금 배차 CLOSED, 수행 COMPLETED. 주문은 아직 PREPARING — order 가 인계·완료 사건을 받지 않아서다(O-1 대기).

## DD-MFC. `DispatchFailed` 에 센터(`mfcId`) 싣기 (2026-11-16, 보낸 영역 backoffice)
- **왜**: 사용자 결정(2026-11-16 "추천대로") — 센터 관리자(MFC_SUPERVISOR)가 **자기 센터의 배차 실패 사건**을 백오피스 사건함에서 본다(배송 원문 §3.6 "배차 지연·불가는 MFC 관리자·고객센터에 안내"). case 0.5.2 는 사건의 센터를 `DispatchFailed.payload.mfcId` 로 정하는데, 지금 payload 에 없다(deliveryId·orderId·reason·attempts·failedAt) → 센터 관리자에게 하나도 안 보인다.
- **할 것(작음, 필드 추가만)**: `DispatchEvents.failed(...)` 가 `p.put("mfcId", before.mfcId())` — 계약 `contracts/events/DispatchFailed.v1.json` payload 에 `mfcId`(선택, `_defs` CenterId) 추가(필드 추가라 하위 호환). `DispatchDelayed` 도 같은 이유로 있으면 좋다(지금 사건함은 구독 안 함).
- **case 쪽**: 이미 읽는다(값이 없으면 센터 없음 → 지원센터·관리자만 봄). 바꿀 것 없음.
