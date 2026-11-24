# promotion-service 에 대한 요청

## PRM-1. 직원 고객 쿠폰 조회 — 보유·사용·소멸 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **왜**: 고객 상세 "쿠폰". 지금은 고객 본인 `/me/coupons` 만 있다(보상 쿠폰 지급 `/promotions/{id}/coupon-grants` 는 있음).
- **필요한 것**: `GET /customer-coupons?customerId=` 직원 조회 — 쿠폰마다 행사·금액·상태(보유·사용·소멸)·받은 경로(보상 지급 포함)·시각.
- **적립금은 없음**: 업무 문서 Commerce §353 "자체 포인트 1차 제외" — 요청하지 않는다.
- **구현**(2026-11-24 PC 2 — 사용자 "쿠폰 발급 기록 조회"·"만들어", PC 1 대신 PC 2 가 구현, promotion-service 0.3.1): 경로는 요청과 달리 **`GET /promotions/coupons?customerId=|promotionId=`**(하나 이상 — 게이트웨이 라우트 `/promotions/**` 안이라 설정 변경 없이, 행사 "발급 내역"도 같은 경로). 쿠폰마다 행사 이름·할인·고객 ID·상태(AVAILABLE·HELD·USED·EXPIRED — 고객 쿠폰함과 달리 결제 중 HELD 따로)·받은 경로(DOWNLOAD·STAFF·SIGNUP·COMPENSATION)·발급한 직원(STAFF 만)·사유·받은 시각·기한·사용 주문·사용 시각, 거르기 status·source, 받은 시각 최근 순·커서(size ≤100). MARKETING·OPERATOR_ADMIN·CS_AGENT. 고객 이름·연락처 없음.

## PRM-2. 행사 대상에 진열 테마 · 지정 발급 쿠폰은 만들 때 받을 고객 (2026-11-24, 보낸 영역 backoffice — PC 2 웹, pmart-clients `docs/handoff/backoffice/2026-11-24-backend-promotion-theme-and-recipients.md`)
- **사용자 결정(2026-11-24)**: "행사 대상에 테마도 추가"(원문 Commerce §6.3·6.4 바뀜), "직원 지정 발급을 눌러도 고객 id 를 넣는다던가 찾는 칸 자체가 없는데". 받는 쪽 구현은 PC 1 사용자 지시로 **PC 2 백엔드**가 맡음.
- **정한 것(사용자 "추천안대로 진행")**: ① 대상 = 그때 게시된 진열의 그 테마 상품의 모든 옵션, 테마 상품이 바뀌면 다시 승인 없이 따라감 ② 승인 뒤 테마가 진열에서 빠지면 할인도 멈춤 ③ 자동 랭킹 테마 제외 ④ 테마 노출 기간은 보지 않음 ⑤ promotion 이 1분마다 storefront 에서 받아 둠(최대 약 1분 30초) ⑥ 받을 고객 ≤100명·사유, 승인 뒤 시작 시각이 되면 1분 안에 발급 ⑦ 발급한 사람 = 행사를 만든 직원 ⑧ 시작 전에 끝나면 발급 안 함 ⑨ 승인 요약에 받을 고객 수.
- **구현**(2026-11-24 PC 2, promotion-service 0.3.0·V5 + storefront-service 0.14.0): `PromotionTarget.type` THEME·`title`, `Problem.code`(THEME_NOT_PUBLISHED·THEME_AUTO_RANKING·RECIPIENTS_NOT_ADMIN), `GET /promotions/theme-targets`, `coupon.recipients`·`recipientResults[]`, 승인 요약 받을 고객 수. storefront 내부 `GET /internal/storefront/themes`. 게이트웨이 허용 목록 1줄(라우트 변경 없음).
