# [pmart-backend → pmart-clients] 요청: 백오피스 "통합 조회" 화면 (2026-11-16)

- 보낸 영역: backoffice
- 받는 앱: backoffice
- 결정: 사용자 2026-11-16 "통합 조회로 진행해" — 업무 원문 BO-11(주문·결제·현장·배송 통합 조회)·§5.1(원천별 출처·시각, 실패를 0·정상으로 바꾸지 않음).
- 계약: `backoffice-bff.yaml` **0.3.0** `GET /api/v1/backoffice/views/unified-lookup`. 백엔드 PR(backoffice/bff-unified-lookup), LAN 배포 전 — 배포되면 알린다.

## 1. API
- `GET /api/v1/backoffice/views/unified-lookup?q=<값>&kind=<종류>`
  - `q`: 주문 ID(`ORD-…`)·배송 ID(`DLV-…`)·사건 ID(`CASE-…`)·고객 ID(`CU-…`) — 완전 일치.
  - `kind`: 비우면 접두어로 가림. `ORDER`·`DELIVERY`·`CASE`·`CUSTOMER` 로 정할 수도 있다. `TRANSFER`·`TRIP`·모르는 값은 **422 `UNSUPPORTED_LOOKUP`**(전화번호 검색은 없다 — 주문 조회 화면의 연락처 찾기를 쓴다).
- 권한: OPERATOR_ADMIN·CS_AGENT·RIDER_OPS(그 밖 403). 각 원천은 그 직원 권한으로 다시 판정한다.
- 응답
  - `resolved`: `{kind, orderId}` — 배송·사건 번호로 찾으면 그 주문으로 이어 `orderId` 가 채워진다. 고객 ID 면 `orderId` null.
  - `matches[]`: `{kind, id, summary, source}` — `kind` = `ORDER`·`DELIVERY`·`DISPATCH`·`CLAIM`·`CASE`·`INQUIRY`. **원천마다 따로** 온다(배송 수행 `DELIVERY` 와 배차 `DISPATCH` 는 같은 배송 ID 라도 다른 항목). `summary` 필드 이름은 각 원천 응답 그대로:
    - ORDER: orderId·customerId·status·mfcId·deliveryMode·arrivalWindow·confirmedAt·amounts·payment·lineCount·fulfillmentIssueCount (고객 ID 로 찾으면 목록 요약 — totalAmount·approvedAmount·itemCount·openFulfillmentIssues)
    - DELIVERY: deliveryId·type·state·riderId·mfcId·assignedAt·pickedUpAt·completedAt·createdAt
    - DISPATCH: deliveryId·state·assignment·attempts·delayAlertedAt·updatedAt
    - CLAIM: claimId·type·state·openedAt
    - CASE(열린 것 + 닫힌 것 최근 20건, 그 직원이 볼 수 있는 종류만): caseId·kind·state·priority·summary·slaDueAt·overdue·assigneeId·refs
    - INQUIRY(그 주문의 고객 문의 — CS_AGENT·OPERATOR_ADMIN 만, 그 밖은 `unavailableSources` 에 `case-service/inquiries`): inquiryId·category·status·origin·createdAt·answeredAt(제목·내용 없음 — 문의 답변 화면 `/inquiries/{id}` 로)
  - `source`: 원천 이름·조회 시각. `unavailableSources[]`: 실패했거나 볼 권한이 없는 원천 — **"조회 실패/권한 없음"으로 보여 주고 0건처럼 보이지 않게**.
  - 개인정보(이름·연락처·주소·메모)는 가린 값도 오지 않는다 — 원문이 필요하면 주문 상세 화면의 열람 요청(ADR-0062).
- 찾는 대상이 없으면 200 + `matches` 비어 있음(404 아님).

## 2. 화면 제안
- 메뉴 "통합 조회"(고객센터·라이더 운영 묶음, 관리자 포함) — 검색 칸 하나 + (선택) 종류.
- 결과는 주문 한 건을 위에, 아래로 원천별 칸(배송·배차·클레임·사건)을 나누어. 각 칸에 출처·시각, 실패한 원천은 칸 안에 경고.
- 각 항목에서 기존 상세 화면으로(주문 `/orders/{id}`, 배송 `/deliveries/{id}`, 사건 `/cases/{id}`).
- 고객 ID 로 찾으면 주문 목록만 → 주문을 누르면 그 주문으로 다시 통합 조회.

## 3. 아직 없는 것
- MFC 현장(예약·작업·인계 — PC 3 API). 필요하면 알려 주세요.

## 4. 질문이 있으면
- `pmart-clients/docs/handoff/backoffice/` 에 남겨 주세요.
