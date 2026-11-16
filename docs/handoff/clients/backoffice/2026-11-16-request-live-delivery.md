# [pmart-backend → pmart-clients] 요청: 백오피스 "실시간 배송 현황" 화면 (2026-11-16)

- 보낸 영역: backoffice
- 받는 앱: backoffice
- 결정: 사용자 2026-11-16 "백오피스용부터 추천대로" — 배송 업무 원문 §10.1(관리자 페이지 "실시간 배송 현황", 예시 표: 주문번호·배송 상태·기사·예정/완료시각·사건), §1(고객센터·운영 관리자의 배송 관제 화면). MFC 센터 관리자용은 따로 정한다.
- 계약: `backoffice-bff.yaml` **0.3.0** `GET /api/v1/backoffice/views/live-delivery`(통합 조회와 같은 PR #25). LAN 배포 전.
- 함께 답: `pmart-clients/docs/handoff/backoffice/2026-11-16-backend-unified-lookup-questions.md` 질문 3개 — §4.

## 1. API
- `GET /api/v1/backoffice/views/live-delivery?mfcId=&state=&size=&cursor=`
  - 권한: OPERATOR_ADMIN·CS_AGENT·RIDER_OPS(그 밖 403) — 셋 다 모든 센터.
  - `mfcId`: 비우면 모든 센터.
  - `state`(delivery-execution ExecutionState 하나): **비우면 끝나지 않은 배송 전부**(UNASSIGNED·ASSIGNED·ARRIVED_AT_STORE·PICKED_UP·ARRIVED_AT_CUSTOMER·FAILURE_REPORTED·SUSPENDED·RETURN_IN_PROGRESS·RETURN_PICKED_UP)를 생긴 순(오래된 것 먼저)으로 `size`(최대 50)건 — 이때 쪽 넘김 없음, 더 있으면 `truncated: true`(센터·상태로 좁히라고 안내). `state` 를 주면 그 상태만, `page.nextCursor` 로 다음 쪽. 상태 없이 `cursor` 를 주면 400, 모르는 상태 400.
- `items[]`
  - `deliveryId`·`orderId`·`mfcId`·`type`(FORWARD·RETURN·RESHIPMENT)·`riderId`·`createdAt`·`assignedAt`·`pickedUpAt`
  - `delivery`: 배송 수행 상태 `{state, version, source}`(delivery-execution)
  - `dispatch`: 배차 상태 `{state, version, source}`(delivery-dispatch) — 배차 원천 장애면 `state: null`, `source.confidence: UNKNOWN`
  - `arrivalWindow`(date·start·end, 한국 시각 그대로), `promisedAt`(= 그 끝 시각, UTC ISO — 없으면 null), `latestEta`(아직 늘 null)
  - `openCases`: 그 배송의 열린 사건 `[{caseId, kind, priority, overdue}]`(그 직원이 볼 수 있는 종류만) — 사건 원천 장애면 `null`(0건과 구별)
- `unavailableSources`: `delivery-execution-service`·`delivery-dispatch-service`·`case-service` 중 실패·권한 없음.
- 개인정보(고객 이름·연락처·주소)는 없다.

## 2. 화면 제안(원문 표 기준)
- 메뉴 "실시간 배송 현황"(라이더 운영·고객센터 묶음).
- 위: 센터 고르기(비우면 전체)·상태 고르기(비우면 진행 중 전부), 새로 고침(자동 새로 고침은 화면 판단 — 서버 부담을 생각해 30초 이상 권장).
- 표 열: 배송·주문 번호(→ 배송 상세·주문 상세), 수행 상태, 배차 상태, 라이더, 예정 시간대(arrivalWindow) / 약속 지남 표시(promisedAt < 지금), 열린 사건(개수·기한 넘김 빨강 → 사건 상세).
- `truncated` 면 "더 있어요 — 센터나 상태로 좁혀 주세요".

## 3. 아직 없는 것
- latestEta(tracking 연결 전), MFC 센터 관리자용(MFC 백오피스 — 따로 정함).

## 4. 통합 조회 질문 답(2026-11-16)
1. **원천 이름 목록**: 계약 `unified-lookup` 설명에 적었다 — order-service(ORDER)·delivery-execution-service(DELIVERY)·delivery-dispatch-service(DISPATCH)·claim-service(CLAIM)·case-service(CASE)·case-service/inquiries(INQUIRY). 그 조회에서 부른 원천만 `sources` 에 나온다.
2. **INQUIRY**: 계약 `matches[].kind` 설명에 들어가 있다(PR #25 01c8f974 부터).
3. **닫힌 사건 순서**: 확인해 보니 case 목록이 상태와 관계없이 우선순위·기한·오래된 순이라 "최근 20건"이 실제로는 오래된 쪽이었다 — **case 0.5.0(PR #21 525921a4)에서 `state=CLOSED` 는 닫힌 시각 최근 순으로 고쳤다**. 사건함의 "닫힌 것" 탭도 이 순서가 된다.
