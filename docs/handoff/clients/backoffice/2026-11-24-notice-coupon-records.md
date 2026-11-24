# 알림 — 쿠폰 발급 기록 조회 `GET /promotions/coupons` (promotion-service 0.3.1)

보낸 영역: backoffice
받는 앱: backoffice
작성: pmart-backend PC 2 백엔드, 2026-11-24 — 사용자 "쿠폰 발급 기록 조회 같은 건 어디서 봄?" → "만들어"
상태: **PR 대기** — 병합·LAN 배포는 PC 1. 고객 상세 "쿠폰"(BF-5 2차 PRM-1)과 행사 화면 "발급 내역"이 같이 쓴다.

## 경로
`GET /api/v1/promotions/coupons` — MARKETING·OPERATOR_ADMIN·CS_AGENT(그 밖 403)
- 거르기: `customerId`(고객 기준) · `promotionId`(행사 기준) — **하나 이상**(둘 다 없으면 400). 둘 다 주면 그 고객의 그 행사 쿠폰.
- `status`: AVAILABLE(기한 안 보유) · HELD(결제 중 점유) · USED(사용 확정) · EXPIRED(기한 지남, 안 씀). 고객 앱 쿠폰함은 HELD 를 USED 로 보이지만 여기서는 따로.
- `source`: DOWNLOAD(앱 내려받기) · STAFF(직원 지정 발급) · SIGNUP(가입 축하) · COMPENSATION(회사 사유 보상 — 배차 불가 등, 서버가 줌).
- `size` 1~100(기본 20), `cursor` = 앞 쪽 `page.nextCursor`. 받은 시각 최근 순.

## 응답
`{ items: [CouponRecord], page: { nextCursor, size } }`

CouponRecord:
- `couponId`, `promotionId`, `promotionName`, `customerId`, `discount`({type, value, maxAmount})
- `status`, `source`
- `issuedBy` — STAFF 면 발급한 직원 운영자 ID(이름은 hr `employee-labels` 로), 그 밖 null
- `issueReason` — 발급 사유(STAFF·COMPENSATION·SIGNUP, 보상이면 "배차 불가 보상 · 주문 …")
- `issuedAt`, `validUntil`, `orderId`(HELD·USED 일 때), `usedAt`(USED 일 때)
- 고객 이름·연락처는 없다 — 행사 기준 목록에서 고객을 보이려면 customer 일괄 조회(`GET /customers?customerIds=`)로 가린 이름을 붙인다.
