# mfc-inbound-service 에 대한 요청

## MI-1. 오배송 회수품 MFC 반납 받기 (2026-11-16, 보낸 영역 delivery — DD-2 뒷단)
- **요청한 곳**: delivery-execution(오배송 회수 — `docs/architecture/service-requests/delivery-dispatch-service.md` DD-2 구현).
- **지금**: 회수 라이더가 MFC 에 도착해 `POST /deliveries/{id}/returns/store-deliveries` 를 누르면 회수 배송이 끝난다(RETURNED_TO_STORE, 수행료). **점원 수령 확인은 없다** — 사용자 2026-11-16 "추천대로": mfc-inbound 가 생기기 전까지 MFC 도착 = 반납.
- **사건(이미 나감)**: `ReturnDelivered`(delivery.delivery.v1, 계약 `contracts/events/ReturnDelivered.v1.json`) — purpose `MISDELIVERY_RECOVERY`, deliveryId(회수 배송), originDeliveryId(오배송된 원래 배송), caseId(오배송 확인 사건), orderId, mfcId, **lines = 원래 배송이 전달한 줄·수량**(회수 대상 — 실제 상태·수량은 검수가 정한다), deliveredAt. 앞서 `ReturnPickedUp`(회수지에서 받음)도 같은 모양.
- **mfc-inbound 가 할 것**:
  1. `ReturnDelivered`(MISDELIVERY_RECOVERY) 구독 → 회수품 입고 대기.
  2. 점원 수령 확인 → 검수: **신선·냉장·냉동은 폐기, 상온 미개봉품만 검수 후 재고 복구**(ADR-0052 결정 4). 회수품을 원래 고객에게 다시 보내지 않는다.
  3. (나중) 점원 수령 확인 참조를 라이더 반납과 잇기 — execution 은 `receivedByStaffRef` 를 이미 받는다. 확인을 반납 조건으로 바꾸려면 사용자 결정.
- **사용자 결정**: 검수 규칙은 ADR-0052 에 있음. 수령 확인을 반납 조건으로 할지는 나중.

## MI-2. 반납 도착 대기 목록 — 점원이 회수품이 온 줄 알게 (2026-11-17, 보낸 영역 delivery — 고객 반품 회수 DD-3·오배송 회수 DD-2)
- **지금 빈 곳**: 회수 라이더가 MFC 에 도착해 `returns/store-deliveries` 를 누르면 delivery 는 `ReturnDelivered`(delivery.delivery.v1)를 내고 배송을 끝낸다. 점원이 회수품을 격리 반입하는 `POST /return-receipts`(claimId·returnPickupId·줄)는 있지만, **mfc-inbound 가 `ReturnDelivered` 를 구독하지 않아 점원 화면에 "반납 도착"이 보이지 않는다** — 점원이 claimId·returnPickupId 를 알 길이 없어 반입·검수·환불(claim `ReturnItemReceived`→`ReturnInspected` 뒤)이 이어지지 않는다(2026-11-17 PC 5 점검).
- **사건 모양**(이미 나감): `ReturnDelivered` — purpose `CLAIM_RETURN`(claimId, lines = claim 이 요청한 줄 — orderLineId·qty·claimLineId·sku) 또는 `MISDELIVERY_RECOVERY`(caseId·originDeliveryId, lines = 원래 배송 전달 줄), deliveryId(회수 배송 = returnPickupId), orderId, mfcId, riderId 는 ReturnPickedUp 에, deliveredAt.
- **mfc-inbound 가 할 것(PC 3)**:
  1. `ReturnDelivered` 구독(두 purpose) → 그 MFC 의 "반납 도착" 대기 행(회수 배송 ID·claimId 또는 caseId·줄·도착 시각).
  2. 점원 화면(mfc-app)에 대기 목록 → 고르면 `POST /return-receipts` 를 그 값으로 채워 반입(격리). 반입하면 대기에서 빠짐.
  3. 오배송 회수(MISDELIVERY_RECOVERY)는 claim 이 없으니 MI-1 검수 규칙(신선·냉장·냉동 폐기, 상온 미개봉만 재고 복구)으로.
- delivery 는 바꿀 것 없음. 점원 수령 확인을 반납 조건으로 바꾸는 것(execution `receivedByStaffRef`)은 나중 사용자 결정.
- **보충(2026-11-17 PC 5)**: 세 번째 purpose `FAILED_DELIVERY_RETURN` — 지원센터가 전달 실패를 확정하고 상품이 라이더 손이면 같은 라이더가 MFC 에 반납한다(originDeliveryId = 실패한 배송, lines = 그 배송이 인계받은 줄, claim·case 없음). 대기 목록에 같이 올리고, 검수는 MI-1 규칙(신선·냉장·냉동 폐기, 상온 미개봉만 재고 복구) — 이 주문의 환불·재배송은 고객센터가 정한다(자동 환불 없음).

- **회신(PC 3, 2026-11-19 — 브랜치 `mfc/return-arrivals`, mfc-inbound 0.13.0 V10·mfc-quality 0.7.0 V5)**: MI-1·MI-2 구현.
  - `deliveryEvents-in-0` = delivery.delivery.v1 `ReturnDelivered`(세 목적 모두) → `return_arrival` 대기 건(arrivalId = 회수 배송 ID). `GET /return-arrivals?mfcId=&state=WAITING|RECEIVED|ALL`(그 센터 운영자, 상품명 표시용).
  - `POST /return-receipts` 에 `arrivalId` — claimId·returnPickupId·orderId·주문 줄 번호를 대기 건에서 채움(줄 생략 가능, 본문 값이 다르면 400, 이미 반입이면 409 `ARRIVAL_ALREADY_RECEIVED` — 재고가 거절한 반입이면 다시). claim 없는 반납(오배송·전달 실패)도 반입(재고 trfId = caseId → originDeliveryId → 회수 ID).
  - 검수는 quality 의 기존 규칙 그대로(신선·냉장·냉동·개봉 재판매 불가 — MI-1 규칙과 같음), claimId 선택. 자동 환불은 claim 이 있을 때만(claim 은 claimId 없는 사건을 무시).
  - 점원 수령 확인을 반납 조건으로 바꾸는 것(execution `receivedByStaffRef`)은 하지 않음 — 나중 사용자 결정. **PC 1**: pmart-config `mfc-inbound-service.yml` 바인딩 `deliveryEvents-in-0`(delivery.delivery.v1, definition 에 `deliveryEvents`), 게이트웨이 `/api/v1/return-arrivals` → inbound.
  - 화면: mfc-app 요청서 `docs/handoff/clients/mfc-app/2026-11-19-request-return-arrivals.md`.

## MI-3. [LAN 시험 전용] 승인 없이 시험 입고 예정 만들기 — 시뮬레이터가 재고 채우기 (2026-11-19, 보낸 영역 platform — MFC 시뮬레이터 R-23)
- **요청한 곳**: MFC 시뮬레이터(pmart-mfc-simulation `docs/backend-requests.md` R-23, 중간). 실서버로 주문·가상 작업자를 계속 돌리면 재고가 줄어 품절 — 지금 재고를 넣는 길은 PC 1 의 seed(`Seed.java --initial-stock`)뿐.
- **사용자 결정(시뮬레이터 쪽 2026-11-19)**: "정식 구현은 아니고 mfc 재고를 채우는 기능만". PC 1 사용자 "요청서 쓰고"(2026-11-19).
- **필요한 것**(이름·경로·한도는 PC 3 이 정한다):
  1. **시험 입고 예정 만들기** — 설정을 켠 LAN 에서만(예 `pmart.inbound.dev-arrivals.enabled`, 운영 설정에 없음 → 404). 본문 예 `{ mfcId, lines: [{ sku, qty }] }` → **INITIAL_STOCK 승인이 만드는 것과 같은 모양의 입고 예정(EXPECTED)** 하나(출처 표시 예 trfId `DEV-<id>`). 응답 `receiptId`. 조회는 지금처럼 `GET /initial-stock-loads?mfcId=&state=EXPECTED`.
     - 그 센터가 취급하는 SKU 만, 줄 수·수량 상한(시뮬레이터 예 40줄·SKU 당 500). **재고는 지금 수령 → ConfirmReceipt → INSPECT → PUTAWAY 그대로**(조정 `/internal/adjustments` 로 넣지 않는다 — seed 원칙 D2).
     - 호출자: 그 센터 운영자 토큰(시뮬레이터 관찰 계정 `claude-mfc-wrk`) 또는 내부 경로 — PC 3 이 정함.
  2. (선택) SKU 를 서버가 고르기 — `{ mfcId, auto: { maxLines, targetQty } }` 면 출하 가능 수량이 낮은 SKU 부터. 어려우면 1 만.
  3. 확인 — 그 입고 예정의 현장 수령 `POST /initial-stock-loads/{receiptId}/receipts` 를 가상 작업자 `mfcw-pp-01~10` 토큰으로 불러도 되는지. 안 되면 1 에 `receive: true`(수령 위치로 바로) 선택지.
- **하지 않는 것**: 정식 입고(`/inbound-promises`)·CDC·SCM 흉내. 정식 입고가 생기면 이 시험 입구는 없앤다.
- 회신: 시뮬레이터 handoff `docs/handoff/simulator/mfc/` 에(PC 3 이 써도 된다).
- **회신(PC 3, 2026-11-19 — 브랜치 `mfc/return-arrivals`, mfc-inbound 0.14.0)**: `POST /dev-initial-stock-loads`(설정 `pmart.inbound.dev-arrivals.enabled` 켠 곳만, 그 밖 404 — **PC 1: LAN `mfc-inbound-service-lan.yml` 에 켬, 라우트 `/api/v1/dev-initial-stock-loads`**). 그 센터 운영자, 줄 1~40·SKU 당 1~500, 모르는 SKU 400 UNKNOWN_SKU. EXPECTED 하나(approvalId `DEV-…`) → 현장 수령은 가상 작업자 토큰으로 됨(그래서 `receive: true` 없음). 선택 2(auto)는 안 함. 회신 `docs/handoff/simulator/mfc/2026-11-19-simulator-reply-r23-dev-stock.md`.

## MI-4. 센터 발주(여러 줄) → 센터 관리자 승인 → 입고 예정, 발주 수량 추천 조회 (2026-11-24, 보낸 영역 platform — PC 1, 받는 쪽 mfc PC 3)
- **계기**: MFC 시뮬레이터 R-48(pmart-mfc-simulation `docs/backend-requests.md`) — 사용자 "입고할 때 사용자 지정하여 입고 발주를 넣을 수 있는 기능, 동시에 mfc 추천 시스템의 값을 불러와 자동으로 채우는 기능, 입고 전에 입고 수량을 볼 수 있는 기능이 필요해."
- **사용자 결정(2026-11-24, PC 1 이 올린 안 중 "B")**: SCM(replenishment·demand-planning) 없이 **MFC 안에서 작게 시작**한다. `forecast-based-supply-request.md` 의 보류는 이 범위만큼 푼다. Q-15 의 승인 주체는 지금 **그 센터 관리자(MFC_SUPERVISOR)** 로 한다. 최종 목표는 그대로다 — SCM 이 생기면 추천·승인을 replenishment 로 옮긴다.
- **필요한 것**
  1. **여러 줄 발주**: 센터 관리자가 SKU·수량 여러 줄을 한 번에 낸다(지금 공급 요청 WK-14 는 한 줄). 지금 `/supply-requests` 를 넓히든 새 자원을 만들든 PC 3 가 정한다.
  2. **센터 관리자 승인 → 입고 예정**: 승인되면 줄마다 입고 예정(EXPECTED — 지금 `GET /initial-stock-loads?state=EXPECTED` 와 같이 수령 전 수량 조회)이 생기고, 수령은 지금 입고 흐름 그대로. 부분 승인(줄별 수량 줄이기)·거절 사유. 승인은 approval-service 를 거칠지 센터 안에서 끝낼지 PC 3 가 정해 적는다.
  3. **발주 수량 추천 조회**(직원 API — 시뮬레이터·MFC 백오피스가 "추천값으로 채우기"에 쓴다): 입력 센터·(선택) 온도대·SKU 목록 → SKU 별 추천 수량 + 근거(예측 일 판매량, 지금 판매 가능 수량, 입고 예정, 목표 재고, 계산 기준 시각, 추천 없음 사유). 산식은 `forecast-based-supply-request.md` 의 S = d × (R + L) + SS, Q = max(0, S − IP)(데모 `demos/mfc-demand-replenishment` #411 의 계산을 서비스로).
     - 예측 d 는 이번에는 센터 안의 단순 이동평균(판매 = 인계 실적)으로 둔다 — **임시**이고, demand-planning 이 생기면 그 예측을 읽는다(서비스 경계 메모: 보류 문서 "MFC 안에서 예측을 계산하면 서비스 경계를 어긴다" — 사용자가 이 범위에서 받아들임).
     - R·L·SS·박스 입수·MOQ 값은 원문에 "승인 전" 이다 — 값이 없으면 추천하지 않고 사유를 준다(0·무제한으로 채우지 않는다). 시작 값은 PC 3 가 데모 값으로 제안하고 사용자 확인을 받는다.
     - 데모의 센터 ID 는 옛 `MFC-SEED-01` — 지금 1호점은 `MFC-BJG-01`(R-46).
- **받는 쪽**: MFC 시뮬레이터(계약이 생기면 추천 채우기·발주를 바꿈), MFC 백오피스(PC 3).
- **상태**: 구현·LAN 배포(2026-11-24 PC 3 #424 → PC 1 B 방식 075b069f·7fce390e, pc3 mfc-inbound Flyway v14, pmart-config f317f6f 라우트 `/supply-recommendations`). 추천 조회 LAN 500(availability `asOf` 객체를 문자열로 읽음)은 0.21.1(#432 08e3a731)로 고쳐 LAN 200 확인.
- **회신(PC 3, 2026-11-24)**:
  1. 여러 줄 발주: 지금 `POST /supply-requests` 가 이미 여러 줄(1~200)이다 — 그대로 쓴다(같은 센터·SKU 미해결 요청이 있으면 409 `DUPLICATE_OPEN_REQUEST`).
  2. 승인·거절: `POST /supply-requests/{id}/approvals` `{lines?: [{sku, approvedQty}], note?}`(생략 줄 = 요청 수량 그대로, 줄이면 note 필수, 전부 0 은 400) · `POST …/rejections` `{reason}`. 그 센터 MFC_SUPERVISOR, SUBMITTED 만. **approval-service 는 거치지 않고 센터 안에서 끝낸다**(Q-15 = SCM 전까지 센터 관리자, 요청자 본인 승인 허용). 승인 → 상태 APPROVED, `approvedLines`·`decidedBy`·`decisionNote`·`linkedReceiptId` + 입고 예정 EXPECTED 하나 — 수령 전 수량은 `GET /initial-stock-loads?mfcId=&state=EXPECTED`(또는 `?approvalId=<요청 ID>`), 수령·품질검사·적치는 지금 흐름.
  3. 추천: `GET /supply-recommendations?mfcId=&storageType=&sku=…`(그 센터 운영자, SKU 1~100) → `recommendedQty` + 근거(forecastDailyQty·historyDays·availableQty·expectedQty·pendingRequestedQty·inventoryPosition·safetyStockQty·targetQty·shortfallQty·R·L·SS일·배수·최소·policySource·calculatedAt·salesThrough), 추천 못 하면 null + `reason`(POLICY_MISSING·STORAGE_TYPE_UNKNOWN·INSUFFICIENT_HISTORY·AVAILABILITY_UNKNOWN·SKU_NOT_HANDLED). d = 인계 실적(`PackageHandedOverPosted`) 이동평균, **품절 날은 빼지 않는다**(임시).
  4. 시작값(사용자 확인 2026-11-24, 승인 전 시험값): 전 센터 기본 R 1일·L 1일·SS 1일치·발주 배수 1(박스 상품은 박스 SKU 라 1)·최소 1·이동평균 28일·최소 이력 7일 — 표 `supply_policy`(센터 줄이 기본보다 우선). 바꾸는 API 는 아직 없다.
  5. 주의: 인계 실적은 이 버전이 배포된 뒤부터 쌓인다 — LAN 에서 7일이 지나기 전에는 모든 SKU 가 INSUFFICIENT_HISTORY. 데모 #411 의 센터 ID 는 `MFC-BJG-01` 로 바꿨다.

## MI-5. CDC 이관 입고 종결 알리기 — "이 TRF 는 더 받을 것 없음" (2026-11-26, 보낸 영역 cdc — D 클론 cdc 세션, 받는 쪽 mfc PC 3)
- **계기**: WMS CDC-F-027 "손실 확정 전 양측 확인 — 아직 수령될 가능성이 있는 동일 잔량을 MFC 가 수령 처리하는 중에 WMS 가 손실로 종결하지 않는다". 사용자 결정(2026-11-26) "MFC 입고 종결 확인을 받은 뒤에만" 운송 손실을 받는다.
- **CDC 쪽(구현, cdc-inventory 0.14.0)**: `POST /internal/transit-closures` `{trfId, departureId, mfcId, closedAt, sourceRef?}`(Idempotency-Key·X-Source-System, TRF 하나에 한 번 — 같은 본문 그대로, 다른 본문 409 TRANSIT_CLOSURE_CONFLICT, CDC 출발 전 404 DEPARTURE_NOT_POSTED). 이 기록이 있어야 운영자가 `POST /cdcs/{cdcId}/transit-losses` 로 남은 미정산을 승인 손실로 닫는다. 종결 뒤 늦게 온 수령도 그대로 대사한다.
- **필요한 것**: mfc-inbound 가 CDC 이관(TRF) 입고 예정을 "더 받을 것 없음"으로 끝낼 때(전량 수령·검수 끝, 또는 관리자가 나머지 미도착을 종결) 위 API 를 부르거나, 사건(예 `TransferReceivingClosed` — 이름·발행 방식은 PC 3 가 정해 적기, 사건이면 CDC 가 구독으로 바꾸고 API 는 복구용)을 낸다. 아직 처리 중인 수령(미판정 포함)이 남아 있으면 종결하지 않는다.
- **받는 쪽**: cdc-inventory(운송 손실), 뒤에 DC 백오피스(손실 화면).
- **상태**: 요청(2026-11-26). MFC 쪽 결정 전까지 CDC 운영자는 복구용으로 같은 API 를 직접 부를 수 있다(시험·LAN).
