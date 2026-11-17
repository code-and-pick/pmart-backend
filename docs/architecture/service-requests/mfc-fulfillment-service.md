# mfc-fulfillment-service 에 대한 요청 (mfc-capacity 쪽에서)

## F-1. 운영 제한 사건 구독 (2026-11-09 기록)

- **요청한 곳**: mfc-capacity — `OperationRestrictionApplied`/`Lifted` 를 2026-11-08 부터 발행한다(BO-08).
- **지금**: fulfillment 는 `AssignmentPublished` 만 구독. 작업 거절 사유 `OPERATION_RESTRICTED` 는 계약에만 있고 내지 않는다(`WorkOrderService` 주석 "원천이 아직 없어" — 이제 원천 있음).
- **할 것**: 구독 → 제한 중 센터의 작업 접수 거절(`OPERATION_RESTRICTED`). 이미 진행 중인 작업을 어떻게 할지는 BO-08 규칙대로.
- **사용자 결정**: 진행 중 작업 처리가 문서에 없으면 질문.

## F-2. 배차 요청 시점 — 피킹 시작 전 포장소요시간 입력 (2026-11-13, 보낸 영역 delivery — 사용자 결정)
- **사용자 결정(2026-11-13, PC 1 검토 H1 답)**: "픽업 시작 전에 포장소요시간 입력해서 그 값이 배차요청할 때 넘어가게 해." — **"픽업"은 "피킹"(MFC 에서 상품 집기)의 오기, 피킹 시작 전이 맞다(사용자 확인 2026-11-13, PC 5 — PC 1 검토 질문)**. 점원이 **피킹을 시작하기 전에 예상 포장(준비) 소요시간을 입력**하고, 그 값이 `DispatchRequested.estimatedPackMinutes`·`estimatedReadyAt` 으로 배차에 넘어가게.
- 지금(0.4.11): `POST /shipments/{id}/dispatch-requests` 는 PACK IN_PROGRESS/COMPLETED 일 때만(포장 중 이후). 바뀔 것: 피킹 시작 전(또는 시작과 함께) 입력·요청 가능하게. 자동 요청 없음(BR-51)은 그대로.
- delivery 쪽: 받는 사건 모양은 그대로라 바꿀 것 없음. 배차 요청이 더 이르게 오면 라이더가 포장 끝날 즈음 도착하도록 `estimatedReadyAt` 을 비용에 이미 쓴다(`BundleProblem` 늦음/기다림).
- 함께 결정: **고객센터 취소는 하지 않는다**(피킹 시작 뒤) — `claim-service.md` C-? / ADR-0052 변경은 platform.

## F-3. 주문 번호로 현장 상태 보기 — 직원용 (2026-11-16, 보낸 영역 backoffice)
- **왜**: 사용자 결정(2026-11-16 "추천대로") — 백오피스 통합 조회(bff `/views/unified-lookup`, 0.3.0 배포됨)에 MFC 현장 칸을 넣는다. 업무 원문 BO-11 "주문 한 건에서 대조할 자료" 표의 **현장/재고**(현물 예약·슬롯·작업·실피킹/부족·유효 포장·실인계)가 반드시 구별할 항목인데, 지금 상담원은 "상품 준비 중" 주문이 현장 어디서 멈췄는지 볼 수 없다.
- **지금**: `GET /internal/work-orders/{fulfillmentId}`(작업 요청 조회 — Task·Shipment·Package 요약)가 있지만 서비스 간 내부 경로이고 주문 번호로 찾을 수 없다.
- **요청**: 직원 토큰용 조회 하나 — 예 `GET /api/v1/work-orders?orderId=`(또는 `/orders/{orderId}/fulfillment`). 권한: OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 는 모든 센터(백오피스), 그 센터 직원은 자기 센터. 응답(개인정보 없이): fulfillmentId·mfcId·workVersion·결정(ACCEPTED/REJECTED·사유)·출고(shipment)별 Task(PICK·PACK 상태·작업자 ID·시작/끝 시각)·부족(SKU·수량·처리)·포장(packageId·상태·인계 허가/확정 시각)·중지 요청 상태, 원천 기준 시각.
- **받는 쪽(backoffice)**: bff 통합 조회에 `matches.kind = FULFILLMENT`(원천 이름 `mfc-fulfillment-service`)로 붙인다. 재고 예약(mfc-inventory)은 필요하면 다음.
- 급하지 않음 — PC 3 일정에 맞춰. 모양을 바꿔야 하면 이 절에 적어 주면 맞춘다.
- **구현(PC 3, 2026-11-17, 계약 0.10.0 — 브랜치 `mfc/fulfillment-order-lookup`, F-4 PR 위에 쌓음)**: `GET /api/v1/work-orders?orderId=`.
  - 권한: OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 는 모든 센터, 그 밖 운영자는 토큰 센터의 작업 지시만(모두 다른 센터면 403). 작업 지시 없이 중지만 있는 주문은 본사 역할만. 작업 지시·중지 둘 다 없으면 404 `WORK_ORDER_NOT_FOUND` — bff 는 "항목 없음"으로.
  - 응답 `{orderId, workOrders[], stopRequests[], asOf}`:
    - `workOrders[]`(원래·오배송 재발송, 만든 순): fulfillmentId·kind·mfcId·workVersion·acceptance·rejectReason·rejectMessage·decidedAt·preparationStartedAt·createdAt·`shipments[]`
    - `shipments[]`: shipmentId·state·readyAt·dispatchRequestedAt·`tasks[]`(taskId·taskType·state·workerId·startedBy·startedAt·endedAt·blockReason·planned/completed/cancelled/remainingQty)·`shortages[]`(점원 결품 보고 — taskId·orderLineId·sku·qty·reason·reportedAt·executionState)·`packages[]`(packageId·state·sealedAt·fenceState·handoverAuthorizedAt·handoverStatus·handoverRejectReason·handedOverAt)
    - `stopRequests[]`: stopRequestId·scope·state·lastError·requestedAt·failedAt·resolvedAt
  - 결품 "처리"는 따로 기록이 없어 작업 줄 `cancelledQty`(결품 해결·중지로 뺀 양)와 PICK `BLOCKED`·`blockReason` 으로 본다. 처리 결정 정본은 주문·사가.
  - 개인정보 없음(이 서비스에 배송지·고객 정보가 없다), 점원 결품 메모·QR 은 싣지 않는다.
  - **bff 할 일(PC 2)**: 통합 조회 `matches.kind = FULFILLMENT`(원천 `mfc-fulfillment-service`)로 붙이기 — 직원 토큰 전달.

## F-4. 멈춘 중지 요청·거절된 인계 목록 — 처리 대기(BO-12)용 (2026-11-16, 보낸 영역 backoffice)
- **왜**: 사용자 결정(2026-11-16 "A안으로 진행") — BO-12 대상 2·3번(재고가 거절한 인계 재시도 D-8, 중지 실패 재시도)이 처리 대기(bff `GET /views/pending-work`)에 나오지 않는다. 재시도 API 는 있는데 **무엇이 멈췄는지 찾을 목록이 없다**.
  - 중지 실패: 판정이 `PENDING` + `lastError` 로 남고 자동 재시도에서 빠진다. 주문의 취소 사가는 RESOLVED 가 아니면 모두 기다림으로 보고 `WAITING_RESULT` 로 다시 조회만 한다 → 멈춘 사가 목록(ESCALATED·RECONCILIATION_REQUIRED·UNKNOWN_TRACKING)에도 안 들어가 **고객 취소·환불이 표시 없이 멈춘다**(피킹 뒤 취소·결품 전체 취소).
  - 인계 거절(D-8): 인계 사실이 `REJECTED` 로 멈추고 재인계·취소가 막힌다. 사가가 관여하지 않아 어디에도 나오지 않는다(ERROR 로그만).
- **요청**: 운영자(OPERATOR_ADMIN) 토큰용 목록 둘 — 이름·경로는 PC 3 이 정한다. 예:
  1. `GET /api/v1/stop-requests?state=PENDING&failed=true` — `lastError` 가 있는 중지 요청: stopRequestId·fulfillmentId·orderId·mfcId·범위·lastError(코드: 상세)·실패 시각·요청 시각.
  2. `GET /api/v1/package-handovers?status=REJECTED` — handoverId·packageId·fulfillmentId·orderId·deliveryId·mfcId·rejectReason·거절 시각.
  - 공통: 오래된 순, 커서, 원천 기준 시각. 개인정보 없이. 처리되면(RESOLVED·APPLIED) 목록에서 빠진다.
  - 재시도도 직원 토큰으로 부를 수 있게(지금 `/internal/…/retries` 는 서비스 간 경로): 예 `POST /api/v1/stop-requests/{id}/retries`·`POST /api/v1/package-handovers/{id}/retries`, OPERATOR_ADMIN, 판정·결과는 지금 응답 그대로.
- **받는 쪽(backoffice)**: bff 처리 대기에 두 묶음을 붙이고(원천 이름 `mfc-fulfillment-service`, 장애면 unavailableSources), "다시 시도" 버튼을 위 재시도로 잇는다(BO-12 결정: 메모 필수, 인계 재시도는 "실물 확인 → 재고 정정 → 재시도" 안내). "처리 완료로 표시"는 MFC 쪽에 그런 상태가 없으면 넣지 않는다 — 필요하면 이 절에 적어 주면 사용자에게 묻는다.
- 급하지 않음 — PC 3 일정에 맞춰. 모양을 바꿔야 하면 이 절에 적어 주면 맞춘다.
- **bff 쪽 준비됨(같은 날, backoffice-bff 0.4.0 PR)**: 위 예 경로·필드 이름으로 읽게 만들어 두고 설정(`pmart.bff.mfc-pending-lists`)으로 꺼 두었다 — 목록이 생기면 켠다(경로·필드가 다르면 bff 를 맞춘다). 기대하는 필드: 중지 `stopRequestId·orderId·mfcId·state·lastError·failedAt(없으면 requestedAt)`, 인계 `handoverId·orderId·mfcId·status·rejectReason·rejectedAt`, 둘 다 `items` 배열.
- **재시도는 이미 연결함**: bff 명령 `stop-retry`·`handover-retry` 가 지금의 `/internal/…/retries` 를 부른다(직원용 경로는 없어도 됨 — 위 "재시도도 직원 토큰으로" 는 취소). 대신 **재시도에 운영자 ID·사유를 받아 남겨 주기**를 요청한다 — BO-12 결정이 "재시도 메모 필수"인데 지금은 bff 가 받은 사유를 MFC 에 넘길 곳이 없어 저장되지 않는다. 예: 본문 `{operatorId, reason}`(선택 필드 — 다른 호출자는 그대로).

- **구현(PC 3, 2026-11-17, 계약 0.9.0 — 브랜치 `mfc/fulfillment-pending-lists`)**: 경로·필드는 bff 가 기대한 그대로.
  - `GET /api/v1/stop-requests?failed=true[&mfcId][&size≤200][&cursor]` → `{items, page:{nextCursor,size}, asOf}`. 항목 `stopRequestId·orderId·claimId·fulfillmentId·mfcId·state(PENDING)·scope·lastError·failedAt·requestedAt·lastRetry`. `failedAt` 은 0.9.0 부터 저장 — 그 전에 실패한 것은 null(bff 처럼 requestedAt).
  - `GET /api/v1/package-handovers?status=REJECTED[&mfcId][&size≤200][&cursor]` → 항목 `handoverId·packageId·shipmentId·fulfillmentId·orderId·deliveryId·riderId·mfcId·status(REJECTED)·rejectReason·rejectedAt·requestedAt·lastRetry`.
  - 둘 다 OPERATOR_ADMIN 토큰만(그 밖 403), 다른 필터 값은 400. 오래된 순, 커서 = 마지막 항목 ID. 처리되면(RESOLVED·다시 보냄) 빠진다.
  - 재시도 `/internal/stop-requests/{id}/retries`·`/internal/package-handovers/{id}/retries` 본문 선택 `{operatorId, reason}`(≤64·≤500자) — **실제로 다시 시도했을 때만**(중지 PENDING+실패 사유, 인계 REJECTED) `manual_retry` 에 남고 목록 `lastRetry{operatorId,reason,retriedAt}` 로 보인다. 본문 없는 호출자는 그대로.
  - **bff 할 일(PC 2)**: `pmart.bff.mfc-pending-lists` 켜기, 재시도 명령이 본문 `{operatorId: 토큰 주체, reason}` 을 보내게. "처리 완료로 표시"는 MFC 에 그런 상태가 없어 넣지 않았다.

## F-5. 포장 라벨·인계 화면에 화면용 주문번호 (2026-11-16, 보낸 영역 delivery)
- **왜**: 클라이언트 all 요청(`pmart-clients` `docs/handoff/all/2026-11-16-backend-display-order-number.md`, 사용자 결정 "배달앱의 주문 번호가 고객앱에도 똑같이") — 고객·라이더·점원·상담원이 같은 번호를 말하게. order 0.11.0(V14)이 주문마다 **숫자 8자리 `orderNumber`**(전체에서 겹치지 않는 무작위, 첫 자리 1~9)를 만든다.
- **이미 있는 것**: `OrderConfirmed`(order.order.v1) payload `orderNumber`(이 필드 전 주문은 없음 — 기존 주문은 order DB 에 채워졌지만 사건은 다시 나가지 않는다), order 내부 `GET /internal/orders/{id}` 의 `orderNumber`.
- **요청**: 작업 지시(fulfillment) 사본에 `orderNumber` 를 두고 포장 라벨·인계 화면·MFC 앱 작업 응답에 실어 주기. 없으면(이전 주문) orderId 그대로. 라이더 앱 요청 §5(포장 라벨 사람이 읽는 번호 입력)도 이 번호로 맞추면 라이더가 라벨 번호를 말할 때 같은 값이 된다 — 라벨 형식은 PC 3 이 정한다.
- 급하지 않음 — PC 3 일정에 맞춰.

## F-6. PDA 피킹 실적이 모두 거절됨 — 내려놓는 곳(toLocation)·카트 (2026-11-17, 보낸 영역 platform — 사용자 "PC3에서 할 것 같아")
- **증상(LAN 2026-11-17 10:52, `mfcw-pp-01`, 작업 `ORD-ac047fd4-…-S1-PICK`)**: 위치 바코드 → 상품 바코드를 찍으면 PDA 가 "거절된 실적이 있어요 — 관리자에게 알려 주세요" 후 진행 불가. 서버 `task_execution` 은 최근 6시간 **0건**(작업은 IN_PROGRESS 로 멈춤, 재고 변동 없음).
- **원인**: 서버 `TaskService.record` 는 PICK 에 `fromLocation`·**`toLocation`(포장 위치, PACKING 구역) 필수**(11-07부터, 없으면 400 VALIDATION_FAILED). mfc_app `pickPayload`(`features/tasks/data/task_repository.dart`)는 `toLocation` 을 보내지 않는다 — 앱 주석 "장바구니(`CRT-…`) 실행 본문에 넣을 곳이 없다(BR-52 R19-3) — 화면에만". 앱은 문제 응답(4xx)을 모두 "거절"로 표시(`executionFailureToSend`).
- **고칠 것(제안 — PC 3 가 정한다)**:
  1. 서버: PICK `toLocation` 으로 **카트(구역 CART, `CRT-…`)도 받기**(현장 흐름 "카트에 집고 → 포장대에서 포장", `deploy/lan/seed/labels/README.md` 스캔 표 3번). 포장 밀봉 때 카트 → 포장 위치 처리와 맞출 것. 계약 `TaskExecutionRequest.toLocation` 설명 갱신.
  2. 앱: 피킹 시작 때 찍은 카트 코드를 `toLocation` 으로 보내기(R19-3 해소).
  3. 앱: 400 같은 **입력 오류를 "거절된 실적"과 구분**해 표시(지금은 원인이 화면에 안 보임).
- **같이 고칠 서버 오류**: `GET /shipments/{id}` 가 `ready_at` 이 NULL 인 출고에서 500(`TaskStore.shipmentReadyAt` — `.query(...).stream().findFirst()` 가 null 원소로 NPE). LAN 에서 `ORD-bd33c14e-…-S1` 조회가 3초마다 500(PDA 포장 화면 반복 조회로 보임). `queryForList` + 첫 값 null 허용으로 고치면 된다.
- **멈춘 작업**: 고친 뒤 `ORD-ac047fd4-…-S1-PICK` 은 다시 진행하면 된다(서버 실적 없음). PDA 의 거절 표시 항목은 앱 큐에만 있다.
- **mfc-app 정식 요청(2026-11-17 11:24, PC 3 — pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-pick-to-cart.md`, 클라이언트 PR #55)**. 사용자 결정: **"장바구니, 집품위치, 상품바코드 모두 찍혀야 해"** — PDA 순서 ① 장바구니 → ② 집품 칸 → ③ 상품 바코드. 요청 표:
  - **P1** PICK `toLocation` = 장바구니(구역 CART·ACTIVE, 예 `CRT-A-01`) — mfc-inventory `TaskExecutionService` 의 `locations.requirePacking(toLocation)` → PICK 은 `requireCart`(IV-03 의 카트 검사). 반영: 선반 `NORMAL·STORED −q` → 장바구니 `NORMAL·PICKED +q`(예약 그대로). mfc-fulfillment `TaskService.record` 문구·판정도 같게.
  - **P2** 포장 밀봉(`POST /shipments/{id}/packages`)이 장바구니 → 패킹대(`packingLocation`, `PAK-…`)로 옮기며 PACKED — inventory PACK `fromLocation = 그 출고 PICK 도착지(장바구니)`, `toLocation = 패킹대`, `PICKED −q`/`PACKED +q`.
  - **P3** 계약: PICK 이면 `toLocation` 필수(구역 CART), 아니면 400 — 거절 코드(예 `TO_LOCATION_REQUIRED`·`NOT_CART`)와 `TaskDetail.packingLocations` 설명 갱신. 지금 계약은 `toLocation` 이 필수가 아닌데 서버가 필수로 판정(어긋남).
  - **P4** 한 출고 = 한 장바구니 — 다른 장바구니로 오면 409 `CART_MISMATCH`(이동 규칙과 같게). 필요 없다고 보면 이유 회신.
  - 같이 볼 곳: 복귀(RETURN) 작업의 `picks[].locations`(피킹 뒤·포장 전 취소면 장바구니), 집품 뒤 중지·인계의 위치(P2 뒤엔 패킹대), 결품만 있는 실적은 `toLocation` 불필요(지금 `anyPick` 조건).
  - 회신 받을 것: P1~P4 수용 여부·PDA 가 보낼 값, mfc-fulfillment·mfc-inventory 버전과 LAN 배포 — PDA 는 그 뒤 실서버로 피킹 → 포장 → 출하대기 확인.
- **상태**: **구현**(2026-11-17, PC 3) — 아래 회신. 처음엔 요청함(2026-11-17) — mfc-fulfillment·**mfc-inventory** 둘 다(IV 쪽은 `mfc-inventory-service.md` IV-3 이 이 절을 가리킨다).
- **회신(PC 3, 2026-11-17)**:
  - P1~P4 모두 수용 — PR #91 병합 `d5386b2f`, **mfc-fulfillment 0.8.0(V13)·mfc-inventory 0.19.0**, LAN 배포 11-17(PC 1, inventory → fulfillment 순서).
  - P1: PICK `toLocation` = 장바구니(구역 CART·ACTIVE) **또는** 포장 위치(PACKING — 예전 흐름 유지). inventory 반영 선반 NORMAL·STORED −q → 도착지 NORMAL·PICKED +q(예약 그대로). 집은 양이 있는데 `toLocation`·`fromLocation` 이 없으면 400 `TO_LOCATION_REQUIRED`(결품만 있는 줄은 불필요).
  - P2: 밀봉 때 집품분이 장바구니에 있으면 inventory PACK `fromLocation` = 장바구니·`toLocation` = 패킹대(`packingLocation`, PACKING 이어야 — 아니면 422 `PACK_REJECTED`). 장바구니 안에서 PACKED 금지. `package.source_location` 기록, 재포장·재구성은 그대로.
  - P3: 계약 `TaskExecutionRequest.toLocation`·`TaskDetail.packingLocations` 설명 갱신(0.8.0).
  - P4: 한 출고 = 한 장바구니 — 다른 도착지면 409 `CART_MISMATCH`.
  - 같이 본 곳: 집품 뒤 중지 복귀(RETURN) 출발 위치 = 집품 도착지(장바구니)·포장분은 패킹대(`CancelReturnRules.Packed`).
  - PDA 가 보낼 값: 첫 스캔으로 찍은 장바구니 코드(`CRT-…`)를 PICK `toLocation` 에. LAN 장바구니 40대(상온 CRT-A-01~20·냉장 CRT-C-01~12·냉동 CRT-F-01~08 — PC 1 seed).
  - 같이 고칠 서버 오류 `GET /shipments/{id}` ready_at NULL 500: **PR #108**(mfc-fulfillment, 계약 변경 없음 — `TaskStore.firstOrNull`, `returns` 도). 배포 필요(PC 1). 회신 `docs/handoff/clients/mfc-app/2026-11-17-reply-shipment-ready-at-npe.md`.
  - 앱 쪽 2·3(카트 코드 보내기·400 과 거절 구분)은 클라이언트(pmart-clients #55·#57).

## F-7. 작업자 본인 "오늘 업무 내역" (2026-11-17, mfc-app 요청 전달 — platform PC 1)
- 원 요청: pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-my-work-history.md`(PC 3 mfc-app, 사용자 "작업자마다 본인의 금일 작업내용을 체크할 수 있어야").
- **요청**(PC 3 가 정함):
  - R1 `GET /tasks`(assignee=me)에 기간 거르기(예 `activityFrom`·`To`, 서울 날짜).
  - R2 `TaskSummary.completedAt`.
  - R3 입고·검수·품질검사·적치·보충·이동·회수품·실사의 **내 처리 내역을 한 곳에서** — 예 mfc-capacity `GET /mfcs/{mfcId}/workers/me/activities?date=` (각 서비스 사건을 모으거나 각 서비스에 내 내역 조회). 서비스 경계는 PC 3 가 정한다.
  - R4 (선택) 오늘 처리 건수·수량 합계를 서버가.

## F-8. 포장 라벨에 사람이 읽는 숫자 — QR 스캔이 안 될 때 숫자로 인계 (2026-11-17, 보낸 영역 delivery — 라이더 앱 요청 §5)
- 사용자 요청(라이더 앱, 2026-11-16 — `pmart-clients` `docs/handoff/rider-app/2026-11-16-backend-trip-info-and-signup.md` §5): QR 스캔이 어려우면 **라벨에 찍힌 숫자를 입력해 인계**한다. 지금 라벨 QR 은 `PKG:<packageId>:<버전>`(BR-23)뿐이라 사람이 칠 수 있는 값이 없다.
- 요청(PC 3 — 방식은 PC 3 판단):
  1. 포장 라벨에 사람이 읽는 짧은 숫자(예: 6~8자리, 센터·당일 안에서 겹치지 않게)를 함께 찍기.
  2. 라이더 인계 승인(`authorize`)이 그 숫자로도 되게 — `scannedCode` 에 숫자를 그대로 받거나, 숫자 → 포장 조회 경로. 라이더 앱은 숫자 입력도 스캔과 같은 절차(승인 → 커밋 → 판정 조회)를 탄다.
  3. 잘못 친 숫자는 지금 QR 오류와 같은 거절(다른 배송·다른 센터 포장이면 거절, 시도 기록).
- 라이더 앱은 목에서 이미 숫자 입력 화면이 있다(`qr_pickup_screen.dart` — 실서버는 "계약에 아직 없음"으로 막힘). 11-16 PC 5 회신에 "다른 영역 몫"으로만 적고 요청서를 내지 않았던 것을 이번에 낸다.

## F-9. 센터 출고 목록 — 포장 인계 현황(관리자 화면)용 (2026-11-17, 보낸 영역 backoffice)
- **왜**: 사용자 2026-11-17 "포장 인계 현황 진행하자" — 업무 원문 `Delivery-business-logic.md` §10.1(관리자 페이지 DB 항목 "실시간 배송 현황·**포장 인계 현황**"), §3.1(출고대기·인계 구역에서 **출고요청·피킹중·패킹중·출고대기**를 주문번호로 보는 화면, **MFC 관리자가 모니터에 띄워 둠**), §3.4(포장마다 QR 인계, 인계 시각·처리 주체), §3.7(일부 인계면 가져간 포장과 남은 포장 구분).
- **지금 없는 것**: 센터 단위로 "진행 중인 출고"를 고르는 목록이 없다. `/tasks` 는 진행 중 작업만(패킹 끝나 인계 기다리는 출고·주문번호 없음), `/packages` 는 확인 필요 포장만, F-3 `/work-orders?orderId=` 는 주문 하나씩(주문 검색은 센터 관리자 403 — 주 사용자가 못 씀).
- **요청**(경로·이름은 PC 3 이 정한다 — 예): `GET /api/v1/shipments?mfcId=&state=&size=&cursor=`
  - 권한: F-3 과 같게 — 센터 직원(센터 관리자 포함)은 자기 센터, OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 는 모든 센터(mfcId 필수 또는 선택은 PC 3 판단). **사용자 2026-11-17: 센터 관리자(MFC_SUPERVISOR)를 이 화면 사용자로 넣음** — bff 는 관리자만(작업자 MFC_WORKER 는 PDA) 자기 센터로 고정해 부른다.
  - state 반복 가능, 비우면 `PREPARING`·`READY`·`PARTIALLY_HANDED_OVER`(끝나지 않은 출고). 오래된 순(만든 시각), 커서, `asOf`.
  - 항목(개인정보·메모·QR 없이 — 지금 계약에 있는 상태 값 그대로):
    - `shipmentId`·`orderId`·`orderNumber`(F-5 화면용 8자리, 있으면)·`fulfillmentId`·`mfcId`·`state`(ShipmentState)·`createdAt`·`readyAt`·`dispatchRequestedAt`
    - 작업: PICK·PACK 각각 지금 상태(TaskState)·막힌 사유(blockReason)·시작 시각
    - 포장 수: 전체·밀봉(SEALED)·인계 완료(HANDED_OVER)
    - 포장별: `packageId`·state(PackageState)·fenceState·`handoverAuthorizedAt`·`handedOverAt`·인계 받은 라이더 ID(있으면)
- **받는 쪽(backoffice)**: bff `GET /views/packing-handover`(계약에 자리 있음 — 지금 501)를 이 목록으로 채운다. 단계 표시(출고요청·피킹중·패킹중·출고대기·일부 인계·인계 완료)는 **bff 가 원천 값에서 정한 규칙으로**(사용자 2026-11-17 "BFF로 단계표시"), 원천 값도 같이 내보낸다. 배송·라이더 도착은 delivery-execution 에서 붙인다. 센터 관리자는 자기 센터로 고정(실시간 배송과 같은 방식).
- 개수: 센터 하나의 진행 중 출고는 수십 건 — 화면이 모니터에서 주기적으로 부른다(주기는 원문 미결 §4.1, 화면이 정함). 한 번에 MFC 1회 부르게 목록 하나로 주면 좋다.
- 급함: 보통(F-6 다음이어도 됨). 모양을 바꿔야 하면 이 절에 적어 주면 bff 를 맞춘다.
