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
