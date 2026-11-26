# 알림 — 고객 목록·구매 요약·최근 활동일·분류·구매 지표 (backoffice-bff 0.29.0)

보낸 영역: backoffice
받는 앱: backoffice (`web/backoffice` — 고객 목록·고객 지표 화면)
작성: pmart-backend PC 2, 2026-11-26 — 원천 회신 `2026-11-26-reply-cu5-cu6-customer-list-last-active.md`·`…-reply-o12-customer-purchase-summaries.md`·`…-reply-o13-customer-purchase-metrics.md`
계약: **backoffice-bff 0.29.0**(`contracts/openapi/backoffice-bff.yaml` `getCustomerListView`·`getCustomerMetricsView`, `CustomerListRow`·`CustomerPurchase`·`CustomerSegment`·`CustomerMetricsView`·`CountRatio`)
상태: **PR 대기 — LAN 배포 전**(배포 전에는 두 경로 404, 고객 상세 새 칸은 없음)

## 1. 고객 목록 `GET /api/v1/backoffice/views/customers`
- 권한 CS_AGENT·OPERATOR_ADMIN(원천 판정).
- 거르기: `name`(이름 **2글자 이상 앞부분**, 별명은 안 찾음) · `joinedFrom`·`joinedTo`(가입 한국 날짜, 포함) · `status`(ACTIVE·WITHDRAW_PENDING·WITHDRAW_COMPLETED). 가입 최근 순.
- 또는 `lastOrderFrom`·`lastOrderTo`(마지막 구매 한국 날짜, 포함) — 마지막 구매 최근 순, **한 번도 안 산 고객은 나오지 않음**. 이름·가입일·상태와 같이 주면 400.
- 쪽: `size` 1~50(기본 50), 다음 쪽은 같은 거르기 + `page.nextCursor`.
- 이름으로 찾으면 서버가 열람 기록(검색)을 남긴 뒤에만 결과를 준다. 기록이 안 되면 **200 + 빈 items + `unavailableSources` 에 `customer-service`** → "잠시 뒤 다시 찾아 주세요"(0명이 아님).
- 줄 = 기존 고객 찾기 줄(가린 이름·전화·고객번호·상태·이용제한) + `joinedAt` + 아래 셋.

## 2. 줄과 고객 상세(`views/customer/{id}`)에 붙은 칸
- `purchase` — `orderCount`·`totalAmount`(누적)·`amount3m`(`amountSince` 이후 3개월)·`firstOrderAt`·`lastOrderAt`. 산 것 = 취소·만료 빼고, **환불은 안 뺌**, 배달팁·봉투값 뺌. 못 읽으면 null(`unavailableSources` 에 `order-service/metrics`).
- `lastActiveAt` — max(마지막 로그인, 마지막 토큰 갱신, 마지막 주문). 셋 다 없거나 원천 하나라도 못 읽으면 null → 화면 "-".
- `segment` — `DORMANT` 휴면 · `AT_RISK` 위험 · `TOP` 우수 · `NEW` 신규 · `REGULAR` 일반(이름은 화면 글자). 규칙(사용자 결정 2026-11-23·26, 한국 날짜):
  - 휴면 = 마지막 구매가 1년보다 전, 위험 = 6개월보다 전. **산 적이 없으면 가입일로**(가입 1년보다 전 휴면, 6개월보다 전 위험).
  - 우수 = 3개월 안 구매 100만 원 이상. 신규 = 가입 1달 이내.
  - 겹치면 휴면 → 위험 → 우수 → 신규 순(신규이면서 우수면 **우수**).
  - 구매 요약을 못 읽으면 null(분류 모름).
- **분류로 거르기는 아직 없다**(필요하면 알려 주세요 — 휴면은 `lastOrderTo` 로 일부 대신할 수 있지만 안 산 고객이 빠짐).

## 3. 고객 구매 지표 `GET /api/v1/backoffice/views/customer-metrics?from=&to=[&centerId=]`
- **첫 구매 기준**: 기간 안에 첫 구매를 한 고객만, 그 고객 주문은 첫 구매부터 `to` 까지. 기간 366일 이하. OPERATOR_ADMIN 모든 센터, MFC_SUPERVISOR 는 centerId 필수.
- 값: `customerCount`·`repeatCustomerCount`·`oneTimeCustomerCount`·`orderCount`·`purchaseAmount` + `repeatRate`(재구매율)·`oneTimeRate`(이탈률 — 한 번만 산 고객)·`averageOrderCount`(평균 구매 횟수) — 셋 다 `{numerator, denominator, ratio}`, 분모 0 이면 ratio null("-").
- 원천 장애 503(0 으로 보이지 않게), 400 은 조건 오류.