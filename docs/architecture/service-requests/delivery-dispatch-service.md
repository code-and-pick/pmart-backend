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
- **구현(2026-11-16, PC 5)**: `DispatchFailed`·`DispatchDelayed` payload 에 `mfcId`(선택 — 계약 필드 추가). DispatchFlowIT 로 확인.

## DD-3. 고객 반품 회수 배송 — `ReturnPickupRequested` 받기 (2026-11-17, 보낸 영역 platform — CL-1)
- **결정(D-7·ADR-0060 결정 2, 사용자 2026-11-17 "추천대로")**: 라이더 인계 뒤(픽업·배달 시작 뒤) 고객이 더는 원하지 않으면 **고객센터가 반품(회수)을 접수**한다. 회수비 9,000원은 고객 부담(회사 잘못이면 면제 — claim 이 판정·결제를 끝낸 뒤에만 회수를 요청한다). **회수 실패(고객 부재 등) = 2번 시도 뒤 상담원 재연락** — 첫 실패 뒤 두 번째 요청은 claim 이 자동으로 다시 보낸다.
- **claim 이 주는 것(구현, 2026-11-17 — claim 0.2.0, 미배포)**: `ReturnPickupRequested`(order.claim.v1, 키 claimId — 계약 `contracts/events/ReturnPickupRequested.v1.json`, 구독자 전이라 `deliveryId` 를 필수에서 뺐다): claimId, orderId, mfcId(반납 센터 = 원주문 센터), **pickupRequestId**(`<claimId>-P<n>` — 시도마다 하나), **attempt**(1 = 처음, 2 = 첫 실패 뒤 자동, 3~ = 상담원 재요청), purpose `CLAIM_RETURN`, feeWaived, lines[{orderLineId, qty(이번에 회수할 수량), claimLineId, sku}], pickupAddressRef `order:<orderId>`(회수지 = **원주문 배송지 고정**, 주소 원문 없음 — 좌표는 order `GET /internal/orders/{id}/delivery-destination`(O-4) 그대로), requestedAt.
- **delivery 가 할 것(PC 5)**
  1. dispatch: 구독 → 회수 배송(type RETURN, **purpose CLAIM_RETURN**, 배송 하나 = pickupRequestId 하나 — 같은 요청은 하나), 회수지 = 원주문 배송지, 도착지 = mfcId. `DeliveryCreated` 에 **claimId·pickupRequestId**(필드 추가)·purpose·dropAddressRef `mfc:<mfcId>`. 인근 라이더 배차·수행료는 DD-2 회수와 같은 규칙(일반 표, 회수지 → MFC)으로 보면 된다(다르면 PC 5 가 정해 알림).
  2. 원래 배송이 아직 전달 전(라이더가 들고 있음 — 주문 IN_DELIVERY)이면: 전달 완료 뒤 회수를 시작할지, 원래 라이더가 바로 MFC 로 가져갈지 PC 5 가 정한다(claim 은 어느 쪽이든 아래 사건만 받으면 된다).
  3. execution: 회수 수행 — 회수지 도착·사진·`returns/pickups` → **`ReturnPickedUp`**(purpose CLAIM_RETURN, **claimId**, deliveryId, lines = 실제 받은 줄·수량) → MFC 도착 `returns/store-deliveries` → **`ReturnDelivered`**(claimId). 지금 DD-2 는 오배송 회수만(`CLAIM_RETURN` 은 "아직 없음").
  4. **회수 실패**: 고객 부재·거부로 회수하지 못하면 운영자 실패 확정 → **`DeliveryFailed`**(그 회수 배송 deliveryId, reason, custody, failedAt) — **claimId·purpose CLAIM_RETURN 도 실어 주면 좋다**(필드 추가 — claim 은 deliveryId 로도 찾지만 DeliveryCreated 를 못 받았을 때 대비). claim 은 수거 전 실패만 회수 실패로 센다(1번째면 새 `ReturnPickupRequested` attempt 2, 2번째면 상담원 재연락). 자동 재시도는 dispatch 가 따로 하지 않는다(claim 이 다음 요청을 낸다).
  5. 회수 라이더에게 원래 고객 전화번호(execution `recovery-contact` 와 같은 방식)·회수 품목이 보여야 한다(회수지에서 고객에게 받아야 하므로).
- **MFC 쪽(구현됨)**: 반납된 회수품은 점원이 mfc-inbound `POST /return-receipts`(claimId·returnPickupId = 회수 배송 ID·줄)로 격리 반입 → `ReturnItemReceived` → 검수 `ReturnInspected` → claim 환불. MFC 앱이 ReturnDelivered 의 claimId 를 점원에게 보여 주는지는 PC 3 확인(MI-1).
- **claim 이 받는 delivery 사건(2026-11-17 구독, 설정 `deliveryEvents-in-0` ← delivery.delivery.v1)**: `DeliveryCreated`(purpose CLAIM_RETURN 만 — pickupRequestId 로 배송 번호 연결), `ReturnPickedUp`·`ReturnDelivered`(claimId 가 있고 purpose 가 없거나 CLAIM_RETURN), `DeliveryFailed`(회수 배송 번호 또는 claimId). 오배송 회수(MISDELIVERY_RECOVERY)는 지나간다.

### DD-3 구현(PC 5, #104) — 고객 반품 회수 배차 — CL-1 의 배송 쪽 (2026-11-17, PC 5 — 브랜치 `delivery/claim-return-pickup`)
- 요청 근거: `claim-service.md` CL-1 "구현 claim(PC 1), 고객 반품 회수 배송은 delivery(PC 5) 요청"(2026-11-17 사용자 "추천대로").
- **사용자 결정(2026-11-17, PC 5 물음)**: 회수 시기 = **바로 배차**(시간 약속은 상담원이 미리), 회수 라이더 수행료 = **일반 표·고객 집 → MFC**(DD-2 와 같음, 고객 회수비 9,000원과 따로), 회수 실패 = **실패마다 알림, 다시 시도는 반품 쪽**(claim 이 횟수를 세고 새 회수 요청을 보냄), 고객 사정(부재·거부) 실패의 라이더 수행료 = **수락 당시 금액 지급**.
- dispatch 0.5.0(V11): 구독 `claimEvents-in-0` ← order.claim.v1 `ReturnPickupRequested` → 회수 배송(type RETURN, purpose CLAIM_RETURN, shipment `CLR-<사건 ID>`, 배송 ID 도 사건 ID 로 — 같은 사건은 하나) + 시도(제외 없음) + DeliveryCreated(claimId·originDeliveryId·returnLines·pickupAddressRef). 같은 클레임에 끝나지 않은 회수 배송이 있으면 만들지 않음. 회수지 = 원래 배송지 좌표(없으면 주문 배송지 → 좌표), 도착 MFC, 예상 배달료 일반 표. 판단은 회수 배차와 같은 길(`runRecovery`). `ReturnPickupFailed`·`ReturnDelivered` → CLOSED.
- execution 0.10.0(V11): 받기 전 전달지(= 고객 집) 열림, 사진·받음(lines = 요청 줄) → 반납, 받기 전 실패 등록 → 확정 → `ReturnPickupFailed`(새 사건), 고객 사유면 수행료 지급. tracking: `ReturnPickupFailed` = 진행 끝.
- **PC 1 할 것**: pmart-config `delivery-dispatch-service.yml` 에 `claimEvents` 바인딩(order.claim.v1, 처음 켜기 전 시작 위치 latest — 시험 사본은 이 PR), 배포 순서 tracking → execution → dispatch. claim 은 `ReturnPickupRequested` 발행 때 `mfcId` 를 실어 주면 좋다(없으면 원래 배송의 MFC), 다시 보낼 때는 **새 사건**(같은 사건 ID 재발행은 무시됨), `ReturnPickupFailed` 구독(몇 번째 실패인지 세기).
- **~~어긋남~~ 해결(2026-11-17 15:05 — claim 020053c9 가 `ReturnPickupFailed` 구독, #113 이 DeliveryCreated 에 pickupRequestId)**. 옛 메모: 위 요청 4번은 회수 실패를 `DeliveryFailed`(+claimId)로 요청했는데, #104 는 새 사건 `ReturnPickupFailed`(deliveryId·claimId·reason 등)로 냈다. claim `ReturnService` 의 `case "DeliveryFailed"` 에 `ReturnPickupFailed` 를 더해야 회수 실패가 claim 에 닿는다(백엔드 세션 할 일 — claim 배포 전에). `DeliveryCreated` 에는 pickupRequestId 가 없지만 claim 은 마지막 회수 요청으로 잇는다.
- **DD-3 맞춤(2026-11-17, PC 5 — 브랜치 `delivery/dd-3-align`)**: 위 요청 1(배송 하나 = pickupRequestId 하나, DeliveryCreated 에 pickupRequestId) — 함. 요청 2(원래 배송이 전달 전) — **사용자 결정 2026-11-17 "배달 중이나 전달 완료 전에는 반품 불가"**: dispatch 는 원래 배송이 전달 완료 전이면 회수를 만들지 않고 경고만 남긴다 → **claim 접수에서 막아 주세요**(전달 완료 전 RETURN 접수 거절). 요청 4(실패 사건) — `ReturnPickupFailed` 로(claim 020053c9 가 구독함). 요청 5(회수 품목·전화) — 배송 조회 `returnLines`(execution 0.10.1)·전화는 drop-info(배정부터). 회수 줄의 claimLineId·sku 는 ReturnPickedUp·ReturnDelivered 까지 그대로.

## DD-4. 정시 배송률 지표 — 날짜별 약속 시간대 안에 전달한 배송 수 (2026-11-18, 보낸 영역 backoffice — PC 2, **결정 필요**)
- **왜**: 사용자 2026-11-18 "일단 그렇게 하고"(bff `views/metrics` 추천안). 정시 배송률 ON_TIME_DELIVERY 는 bff 가 다른 서비스 DB 를 못 읽어서 **약속과 전달 시각을 둘 다 가진 서비스**가 날짜별 수를 내야 한다. 지금 bff 는 501.
- **누가 셀지(결정 필요 — PC 5·PC 1)**:
  1. **delivery-dispatch(추천)**: 배차에 `arrivalWindow`(약속 시간대)가 있고, `DeliveryCompleted` 를 이미 구독한다(배송 CLOSED). 배송 단위로 센다.
  2. order: 주문에 `arrivalWindow` 가 있고 `DeliveryCompleted` 를 구독한다. 다만 한 주문에 배송이 여럿(부분·재발송)이면 주문 단위 판정을 따로 정해야 한다.
- **제안 정의**(정해지면 이 절을 고친다):
  - 대상(분모): 그 날짜(Asia/Seoul)에 약속 시간대가 **끝나는** 고객 배송(FORWARD·RESHIPMENT). 실패·지연도 넣는다. 주문 취소로 없어진 배송만 뺀다(BO-28 — 분모를 줄이지 않는다).
  - 정시(분자): `DeliveryCompleted.deliveredAt` ≤ 그때의 약속 시간대 끝. 고객이 시간을 바꿨으면(`ReservationTimeChanged`) 바뀐 시간대.
  - **판정 불가**(분모에서 빼고 따로 수): 완료 방법이 고객 수령 확인(CUSTOMER_CONFIRMED)·자동 완료(AUTO_COMPLETED)면 deliveredAt 이 실제 전달 시각이 아니다 — 라이더 실제 전달 시각이 따로 남는다면 그걸로 판정할 수 있으니 알려 달라.
  - 아직 끝나지 않은 배송(시간대는 지났는데 전달 전)은 "늦음"으로 넣을지, 끝날 때까지 미정으로 둘지도 정해 달라(추천: 시간대가 지났으면 늦음).
- **요청 모양**(예 — 경로·이름은 받는 PC 가 정한다): `GET /api/v1/metrics/on-time-deliveries?from=&to=&mfcId=` → 날짜·센터별 `due`(분모)·`onTime`(분자)·`unjudged`·`asOf`. 직원 토큰(본사 관리자 모든 센터).
- **받는 쪽(backoffice)**: bff `views/metrics?metric=ON_TIME_DELIVERY` 를 이 수로 채운다.
- 급함: 낮음(운영 참고 지표).
- **상태**: 결정 필요 — 누가 셀지(PC 1·PC 5·사용자).
