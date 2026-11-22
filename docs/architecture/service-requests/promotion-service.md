# promotion-service 에 대한 요청

## PRM-1. 직원 고객 쿠폰 조회 — 보유·사용·소멸 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **왜**: 고객 상세 "쿠폰". 지금은 고객 본인 `/me/coupons` 만 있다(보상 쿠폰 지급 `/promotions/{id}/coupon-grants` 는 있음).
- **필요한 것**: `GET /customer-coupons?customerId=` 직원 조회 — 쿠폰마다 행사·금액·상태(보유·사용·소멸)·받은 경로(보상 지급 포함)·시각.
- **적립금은 없음**: 업무 문서 Commerce §353 "자체 포인트 1차 제외" — 요청하지 않는다.
