# backoffice-bff 에 대한 요청 (주문 이행 쪽에서)

## BO-12. 운영자 수동 개입 화면 (2026-11-09 기록)

- **요청한 곳**: order-orchestration, mfc-fulfillment.
- **대상**
  1. ESCALATED 사가 처리.
  2. 재고가 거절한 인계 재시도(D-8) — API `POST /internal/package-handovers/{id}/retries` 있음.
  3. 중지 실패 재시도 — `POST /internal/stop-requests/{id}/retries` 있음.
- **지금**: 화면 없음 — API·로그로만. backoffice-bff CommandsController 501.
- **사용자 결정(2026-11-16 "너 추천대로")**:
  1. **누가**: `OPERATOR_ADMIN` 만 처리한다(상담원·센터 관리자는 목록을 보지 않음).
  2. **버튼 둘로 시작**: "재시도"(사가 조치 재시도·인계 재시도·중지 재시도)와 "처리 완료로 표시"(사람이 밖에서 해결 — **사유 필수**). 환불은 이 화면에서 하지 않고 기존 환불 절차(claim·payment)로.
  3. **근거**: 재시도·처리 완료 모두 메모 필수. 인계 재시도는 "실물 확인 → 재고 정정 → 재시도" 절차(D-8)를 화면에 안내.
  4. **알림**: 지금은 목록만(멈춘 건 수 표시). case 사건함(R-3)이 생기면 멈춘 건을 사건으로도 연다.
  - **구현(2026-11-16, PC 1 — 사용자 "PC 2 에 넘기기 전에", "MFC 쪽 요청 없이 내 쪽만")**:
    - order-orchestration 0.2.0: `GET /internal/stuck-sagas`(네 사가 표의 ESCALATED·RECONCILIATION_REQUIRED·UNKNOWN_TRACKING, 처리 완료 표시 전, allowedActions), `POST /internal/sagas/{id}/manual-actions` 의 REQUERY_RESULT(결제 확인 대기만 — 원결과 다시 확인)·**MARK_RESOLVED(새 — 밖에서 해결함 표시, 사가 상태·자원 그대로)** — 둘 다 승인 없이, operatorId·reason 필수. V7 `saga_manual_action`·`saga_manual_resolution`.
    - 재시도 판단: 업무 거절로 멈춘 사가(ESCALATED)는 같은 키로 다시 보내도 같은 거절이라 "다시 확인"을 주지 않고 "처리 완료"만.
    - backoffice-bff 0.2.0: `GET /views/pending-work`(멈춘 사가 + order 이행 확인 필요, OPERATOR_ADMIN, 원천 장애면 unavailableSources), `POST /commands/order-orchestration-service/saga-requery|saga-resolve`(targetRef = sagaId, reason 필수).
    - **없는 것**: MFC 인계 거절(D-8)·중지 실패 목록 — mfc-fulfillment 에 목록 API 가 없어 화면에 안 나온다(재시도 API 는 있음). 사용자 2026-11-16 처음엔 "지금은 요청하지 않음" → 같은 날 확인(중지 실패면 취소 사가가 WAITING_RESULT 로 조용히 멈춰 처리 대기에 안 나옴) 뒤 **"A안" — PC 3 에 목록·직원용 재시도 요청 [F-4](mfc-fulfillment-service.md)**. bff 쪽은 0.4.0 으로 준비(목록은 설정으로 꺼 둠, 재시도 명령은 지금 내부 경로로 연결).
- 화면은 pmart-clients 몫 — 정해지면 `docs/handoff/clients/` 로 알린다.

## BF-1. 화면 응답의 운영자 ID 에 "이름 · 사번" 싣기 (2026-11-19, 보낸 영역 platform — PC 1, 원 요청 backoffice operator-display-names R2)
- **왜**: 백오피스 승인함·처리 대기·접수 건함의 요청자·결정자·처리자 칸에 `OP-…` 가 그대로 보인다. PC 1 이 hr-service 0.8.0 에 표시 조회를 만들었다(R1) — 화면이 따로 불러도 되지만, bff 화면 응답에 같이 실으면 화면 호출이 줄어든다(요청서 R2, 선택).
- **원천**: hr-service `GET /api/v1/hr/employee-labels?operatorIds=…`(직원 토큰 — bff 는 받은 토큰 그대로 전파, 1~50개, 퇴사자 포함, 모르는 ID 는 빠짐 → 화면 "확인 불가"). 줄 `{operatorId, name, employeeNo}`. 이름은 로그 금지.
- **요청**: `views/approvals`(요청자·결정자)·`views/pending-work`(최근 재시도한 사람)·접수 건함 화면(맡은 사람)의 운영자 ID 옆에 `…Label: {name, employeeNo} | null`. hr 를 못 읽으면 그 칸만 null(화면 전체를 막지 않음 — unavailableSources 에 hr).
- 급함: 낮음(화면이 R1 을 직접 불러도 됨). 모양은 PC 2 가 정해 클라이언트에 알림.
- **상태**: **구현(2026-11-19, bff 0.17.0 — PR #212, 배포됨)**: 모양 `OperatorLabel{operatorId, name, employeeNo}` — 승인함 `items[].requestedByLabel`, 처리 대기 `items[].lastRetry.operator`, 접수 건함 `items[].assignee`(그 뒤 0.18.0 라이더 컴플레인 `recordedByLabel`). 화면 응답 하나에 hr 한 번(50개씩), 실패면 그 칸만 null + unavailableSources hr-service(403·404 는 장애 아님). 승인 결정자(상세의 결정 이력)는 bff 화면에 없어 웹이 hr 를 직접(R1).

## BF-2. 고객 화면 `views/customer` — 백오피스 "고객" 메뉴 (2026-11-19, 보낸 영역 backoffice — PC 2 자신, 설계)

- **사용자 결정(2026-11-19 "응 그대로 진행해")**: 고객센터 묶음 "고객"(조회만). 원천 요청 customer **CU-4**(찾기·상세·원문)·**CU-3**(고객번호), claim **CL-7**(고객 거르기). 이 절은 PC 2 의 설계(원천이 나오면 구현).
- **모양(안)**:
  - `GET /views/customer-search?customerNumber=|phone=|orderNumber=` — 완전 일치 하나만(둘 이상 400). 주문번호는 order `GET /orders/lookups?orderNumber=` → customerId 로 이은 뒤 customer 상세. 결과 0~N 명(전화는 여럿일 수 있음 — 가린 값만).
  - `GET /views/customer/{customerId}` — 머리: customer 상세(가린 이름·고객번호·가입 방식·휴대폰 확인·가입 시각·상태). 칸: 최근 주문(order `/orders/lookups?customerId=` 10건, 화면 번호 그대로), 클레임(claim CL-7 10건), 문의(case `GET /inquiries` 고객 거르기 10건 — **case 에 직원 `customerId` 거르기를 PC 2 가 더한다**), 상담원 접수(case `/cs-intakes?customerRef=`), 열린 접수 건(그 고객 주문·클레임에 걸린 것 — case refs customerId·orderId). 칸마다 `nextCursor`·원천 표시.
  - 원천 하나가 실패하면 그 칸만 null + unavailableSources(0건으로 바꾸지 않음), customer 가 없으면 404, 403 은 그대로.
- **역할**: CS_AGENT·OPERATOR_ADMIN(원천 판정 그대로). 원문 열람은 화면이 customer CU-4 ③ 을 직접.
- **순서**: CU-4·CU-3 → bff·case 고객 거르기 → 웹. 그전에는 화면이 통합 조회(고객 ID → 주문)로 대신.
- **상태**: **구현(PC 2, 2026-11-20 — bff 0.22.0, customer 0.11.0 CU-3·CU-4 LAN 배포 뒤)**: `GET /views/customer-search`(고객번호·전화·주문번호), `GET /views/customer/{customerId}`(가린 머리 + 주문·문의·상담원 접수 칸). 열린 접수 건 칸은 이번에 넣지 않음(웹은 문의·접수의 caseId 로 이음). **클레임 칸은 CL-7 뒤** 추가.

## BF-MFC-S. 결품률 지표 사유에 변질 SPOILED (2026-11-19, 보낸 영역 mfc — PC 3)
- mfc-fulfillment 0.22.0 이 결품 사유 **SPOILED(변질)** 를 더했다 — `GET /metrics/picking-lines` 의 `shortLinesByReason.SPOILED`(선택 칸, 없으면 0 으로).
- 요청: `MetricsView.SHORT_REASONS`(SHORT·DAMAGED·BLOCKED)에 SPOILED 를 더해 대시보드 사유별 결품에 "변질"로. 지금 그대로여도 깨지지는 않는다(합계 shortLines 에는 이미 들어감).
- **상태**: **구현**(PC 2, bff 0.21.0 PR #248 — 결품률 사유 목록에 SPOILED, 원천에 SPOILED 칸이 있을 때만 점을 냄(0.22.0 전 원천은 0 으로 만들지 않음), 이름표 "변질"은 웹).

## BF-3. 상품(SKU)별 결품률 — `views/metrics` SKU 묶음 (2026-11-20, 보낸 영역 backoffice 웹 — BR-73 SK-2, PC 2 자신)
- **왜**: 웹 BR-73(pmart-clients `docs/handoff/backoffice/2026-11-20-backend-shortage-by-sku.md`) — 대시보드 "결품 많은 상품 상위 N". 웹은 bff 로 묶든 원천을 바로 부르든 괜찮다고 했고, PC 2 는 지금 SHORTAGE_RATE 와 같은 길(`views/metrics`, 권한·실패 표시 같게)로 묶기로 웹에 답함(2026-11-20).
- **기다림**: mfc-fulfillment F-13(SKU별 원천, PC 3). 들어오면 bff 에서 원천 그대로 넘기고(SKU·분자·분모·사유별), 상품명이 없으면 product 조회로 채울지는 그때.
- **상태: 구현(2026-11-20, bff 0.23.0 — PC 2)**. F-13(mfc-fulfillment 0.32.0, #308) 배포 뒤. `views/metrics` 의 날짜별 점(points) 모양과 달라 **새 경로 `GET /views/metrics/shortage-by-sku`**(from·to·centerId·sort·limit·minPickedLines) — 원천 값 그대로(sku·productName·pickedLines·shortLines·shortLinesByReason, 원천이 적용한 sort·minPickedLines), 비율은 화면이. 상품명이 없으면 null(bff 가 product 로 채우지 않음 — 화면은 SKU). 원천 4xx 그대로, 장애 503.

## BF-4. 클레임·라이더 컴플레인·처리 대기 화면 센터 거르기 (2026-11-23, 보낸 영역 platform — 사용자 "주문에 붙은 것만", 통합 백오피스 센터 고르기 #390)
- **요청**: `GET /views/claims`(→ claim CL-8 `mfcId`), `GET /views/rider-complaints`(→ case `centerId`), `GET /views/pending-work`·`/views/work-counts`(묶는 원천마다 센터 인자를 넘기고, 센터를 못 거르는 원천은 응답에 표시)에 선택 `centerId`(bff 의 다른 view 와 같은 이름).
- 원천 쪽: claim CL-8(PC 1), case R-12(PC 2).
- **범위 밖(사용자 결정)**: `views/unified-lookup`(통합 조회) — 그대로.
- **구현 1차**(2026-11-23 PC 2, bff 0.24.0 — PR 대기): `views/claims` centerId → claim mfcId(CL-8), `views/pending-work` 이행 확인·MFC 중지 실패·인계 거절은 원천 mfcId 로(멈춘 사가는 센터가 없어 못 거름), `views/work-counts` PENDING_WORK 같은 규칙·DELIVERIES execution mfcId(CASES·OBLIGATIONS·SHIFT_HANDOVERS·INQUIRIES·APPROVALS 는 범위 밖 — 모든 센터). 못 거른 원천은 응답 `centerUnfilteredSources`. **`views/rider-complaints` 는 case R-12 뒤 2차.** **2차 구현**(2026-11-23 bff 0.25.0 — `views/rider-complaints?centerId` → case 0.33.0 centerId, 줄 `centerId`).

## BF-5. 고객 상세 넓히기 — 배송·환불·활동 기록·분류 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **1차(원천 있음, PC 2)**: 주문 칸 상품·금액·취소, 배송 이력(execution·dispatch), 환불(claim), 관리자 활동 기록(audit 열람·작업 기록), 문의 처리 상태·담당, 클레임 종류별 수.
- **2차(원천 요청 뒤)**: 목록·검색(CU-5), 최근 활동일(CU-6), 계정 상태(CU-7), 분류·분석(O-12·O-13), 결제(PY-1), 리뷰(RV-1), 쿠폰(PRM-1). — **목록·최근 활동일·분류·분석 구현**(2026-11-26 PC 2, bff 0.29.0 `GET /views/customers`·`GET /views/customer-metrics`, 고객 상세 `purchase`·`lastActiveAt`·`segment` — 사용자 2026-11-26 "우수 우선"·"산 적 없으면 가입일로 판정"). 분류로 거르기는 아직 없음.
- **고객 분류 규칙(사용자 결정 2026-11-23)**: 신규 가입 1달 이내, 우수 3개월 안 구매 100만원 이상, 휴면 1년 구매 없음, 위험 6개월 이상 구매 없음(휴면 우선), 그 밖 일반.
- **1차 구현**(2026-11-23 PC 2, bff 0.25.0 — PR 대기): 고객 상세에 `deliveries`(execution 주문마다 — 상태·담당 MFC·배정·픽업·완료)·`claimCounts`(종류별 수 — 반복 클레임)·`piiAccessLogs`(audit 열람 기록 — OPERATOR_ADMIN 만), 클레임 줄 사유 코드·환불 금액. **아직 없음**: 작업 기록(audit `/audit-entries` 501), 문의 담당 상담원·주문 상품 이름(원천 목록에 없음), 상담 메모 칸(case 0.34.0 배포 뒤 다음 PR). → **구현**(2026-11-23 bff 0.26.0 `customerNotes`).
