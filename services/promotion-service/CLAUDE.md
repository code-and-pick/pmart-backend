# promotion-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `promotion-service` 절, 결정은 ADR-0082(쿠폰·기간 할인, 2026-11-20). 여기에는 이 서비스 안에서만 적용되는 규칙을 둔다.

## 계약
- REST: `contracts/openapi/promotion-service.yaml` → `com.pmart.promotion.api.generated`. `api/*Controller` 가 구현(PromotionsApi 직원·CouponsApi 고객·InternalApi 서비스).
- 구독: `commerce.pricing.v1` PriceVersionEffective(기본가 사본 — 표시 할인, 아래), `commerce.product.v1` ProductVersionPublished(SKU → 카테고리 사본, 상위 포함 — `PromotionRules.withAncestors` 가 id 접두 규칙 `p09 → p09-c01 → p09-c01-l01` 을 쓴다), `platform.approval.v1` ApprovalDecided(PROMOTION·promotion-service 만).
- 발행: `ApplicationReceiptRecorded`(platform.approval-receipt.v1 — 승인 반영 회신, ADR-0039), `SkuDisplayDiscountChanged`(commerce.sku-display-discount.v1 — 표시 할인).

## 규칙 (ADR-0082)
- 행사 두 종류: PRICE_DISCOUNT(기간 할인 — SKU·카테고리 대상, 정률·정액(개당)), COUPON(장바구니 쿠폰 — 정액·정률+최대, 최소 주문금액, 총 수량·1인 한도, 사용 기간 = 받은 날 포함 N일 또는 고정 기한, 발급 방식 DOWNLOAD·ADMIN·SIGNUP).
- 상태 DRAFT → SUBMITTED(해시 잠금) → APPROVED(시작~끝 사이만 적용, `phase`) / REJECTED / ENDED(조기 종료 승인). 승인 행 attributes.action = ACTIVATE·END, draftHash 로 대조.
- 역할: 만들기·고치기·제출 MARKETING·OPERATOR_ADMIN, 보기 + CS_AGENT, 지정 발급 MARKETING·CS_AGENT·OPERATOR_ADMIN. 승인자는 approval-service `approver-roles.PROMOTION`(MARKETING,OPERATOR_ADMIN — 자기 승인 금지).
- 기간 할인 계산: 같은 SKU 에 걸린 것 중 할인액이 가장 큰 1개(같으면 먼저 끝나는 것). 정률 원 미만 버림, 할인 ≤ 단가.
- 쿠폰: 발급은 행사 행 잠금(마지막 1장 한 명), 점유는 쿠폰 행 잠금 + 유일 인덱스(살아 있는 점유 하나). 점유 때 할인액을 정해 그대로. 확정 → USED, 해제 → AVAILABLE, 전체 취소 복원 → AVAILABLE(원래 기한이 지났으면 만료로 보임, `couponUsable=false`).
- 상태 어긋남은 409 INVALID_STATE(공통 ErrorCode). 조회성 POST(`/internal/price-discounts`·`/internal/coupon-quotes`)는 Idempotency-Key 없음(`pmart.web.excluded-paths`).

## 할인가 표시 (2026-11-21 ADR-0082 4단계)
- 구독 `pricingEvents-in-0` ← commerce.pricing.v1 PriceVersionEffective → `sku_price`(버전 큰 것만, `PriceProjection`). 기본가가 있어야 정률·정액 중 가장 큰 1개를 고른다.
- `DisplayDiscountSync`(`pmart.promotion.display-sync-interval`, 기본 30초): 진행 중 기간 할인이 걸린 SKU + 지금 할인으로 보이는 SKU 를 견적과 같은 `PriceDiscounts` 로 다시 계산 → `sku_display_discount` 와 다르면 seq+1 저장 + `SkuDisplayDiscountChanged`(토픽 `commerce.sku-display-discount.v1`, 키 SKU, compact — LAN topics-init). 할인 없어짐 = unitDiscount 0. 500개씩 트랜잭션, advisory lock 으로 인스턴스 하나만. 가격 없는 SKU 는 알리지 않음. 시험 `PromotionsIT.displayDiscountFollowsPriceStartEndAndCategoryProducts`.

## 가입 축하·보상 쿠폰 (2026-11-21 ADR-0082 5단계)
- 구독 `customerEvents-in-0` ← commerce.customer.v1 `CustomerIdentityVerified`(stage SIGNUP 만) → `SignupCoupons`: `signup_grant`(signupKey = 번호 HMAC, PK)를 먼저 잡고, 진행 중 SIGNUP 쿠폰 행사마다 `CouponService.issue`(채널 SIGNUP, 1인 한도·총 수량 그대로). 한 장도 못 주면 키를 놓는다. **키는 탈퇴 뒤에도 남기고 지급 후 5년에 지운다**(처리방침 2026-11-21.2 — `pmart.promotion.signup-grant-retention` P1826D, 매일 04:50 `SignupCoupons.purgeExpiredKeys`, 설정 없으면 지우지 않음). 키 없는 사건(customer 비밀값 미설정)은 주지 않음.
- 구독 `orderEvents-in-0` ← order.order.v1 `OrderCancelled`(cancelReason DISPATCH_UNAVAILABLE, accountId) → `CompensationCoupons`: `compensation_grant`(주문·사유 PK) PENDING → 설정 `pmart.promotion.compensation.dispatch-unavailable-promotion-id` 행사(COUPON·ADMIN·RUNNING)에서 **1인 한도 없이** 한 장(issued_by system, 사유에 주문 ID) → ISSUED. 못 주면 PENDING, `retry-interval`(1분)마다 다시(SKIP LOCKED). 바인딩 start-offset latest — 처음 켤 때 지난 취소는 소급 안 함.
- 시험 `PromotionsIT.signupAndDispatchUnavailableCompensationCoupons`. (2026-11-21: pricing 견적·order 점유/확정/해제/복원·claim 환불은 연결됨 — 각 서비스 CLAUDE.md)

## 실행
```bash
./gradlew :services:promotion-service:check
```
로컬 포트 8109. LAN PC 2.
- **쿠폰 도착 알림(2026-11-21)**: `CouponService.issue` 가 DOWNLOAD 가 아닌 발급(SIGNUP·ADMIN — 보상·직원 지정)마다 같은 트랜잭션에서 `CouponIssued`(토픽 `commerce.coupon.v1`, 보상이면 reason DISPATCH_UNAVAILABLE·orderId) → notification.
- **쿠폰 사용 시각(2026-11-23, 계약 0.2.1 — 고객앱 BR-85)**: `GET /me/coupons` `usedAt` = `coupon.used_at`(점유 확정 = 주문 확정 때 기록, 해제·복원 때 null) — USED 일 때만. 시험 `PromotionsIT`(확정 뒤 있음·복원 뒤 없음).
- **행사 대상 테마·받을 고객(2026-11-24, 계약 0.3.0·V5 — 백오피스 요청, 사용자 "행사 대상에 테마도 추가"·"직원 지정 발급 … 고객 id 를 넣는 칸", 세부 "추천안대로 진행", ADR-0082 덧붙임)**:
  - **THEME 대상**: `ThemeSync`(`pmart.promotion.theme-sync-interval` 기본 PT1M)가 storefront `GET /internal/storefront/themes`(`infrastructure/StorefrontThemesClient`, 주소 `pmart.promotion.storefront-base-url` 기본 `http://storefront-service:8106` — pmart-config 변경 없음)를 받아 진열 버전이 바뀌면 `theme_copy`·`theme_copy_product`(`ThemeCopyStore`)를 통째로 바꾼다. 안 닿으면 사본 그대로. 할인 판정(`PromotionStore.priceCandidates`·`themesOf`, `DisplayDiscountStore.targetedSkus`)은 사본 상품 → `sku_product` 의 모든 SKU. 테마가 빠지면 할인이 멈추고 표시 할인은 다음 30초 주기에 0. 제출 검사 `PromotionRules.check(in, now, themeKinds)` — 사본에 없으면 THEME_NOT_PUBLISHED, RANKING_AUTO 면 THEME_AUTO_RANKING(`Problem.code`). 고를 목록 `GET /promotions/theme-targets`(READERS — 게이트웨이 라우트 `/promotions/**` 안이라 설정 변경 없음), 행사 `targets[].title`.
  - **받을 고객**: `coupon.recipients`{accountIds ≤100, reason} — ADMIN 만(아니면 RECIPIENTS_NOT_ADMIN), 초안 저장 때 `promotion_recipient`·`promotion.recipient_reason`(`RecipientStore`)에 통째로, 입력 JSON 이라 해시 잠금에 들어감. `RecipientGrants`(`pmart.promotion.recipient-grant-interval` 기본 PT1M)가 진행 중 ADMIN 행사의 아직 안 준 고객에게 `CouponService.issue`(채널 ADMIN, issued_by = 만든 직원, 사유 그대로, 쿠폰 도착 알림) — 행사 행 잠금·행사마다 트랜잭션. 결과 `recipientResults[]`(PENDING·ISSUED·SKIPPED_LIMIT·SOLD_OUT). 시작 전에 끝난 행사는 PENDING 그대로. 승인 요청 요약 "· 받을 고객 N명(시작 때 발급)"·attributes `recipientCount`.
  - 시험 `PromotionRulesTest.themeTargetsAndRecipients`, `PromotionsIT.themeTargetFollowsPublishedThemeProductsAndStopsWhenRemoved`(storefront 대역 = JDK HttpServer)·`adminCouponRecipientsAreIssuedWhenRunning`.
- **쿠폰 발급 기록(2026-11-24, 계약 0.3.1 — PRM-1 + 행사 발급 내역, 사용자 "쿠폰 발급 기록 조회"·"만들어")**: `GET /promotions/coupons?customerId=|promotionId=&status&source&size&cursor`(`CouponRecordsController`·`application/CouponRecords`·`infrastructure/CouponRecordStore`·`domain/CouponRecordRules`) — 보기 역할은 행사 보기와 같음(READERS), 거르기 하나 이상(없으면 400). 상태 = AVAILABLE·HELD·USED·EXPIRED(HELD 를 USED 로 접지 않음 — 고객 쿠폰함과 다름), 받은 경로 = 채널 DOWNLOAD·SIGNUP 그대로, ADMIN 은 issued_by `system` 이면 COMPENSATION(배차 불가 보상) 아니면 STAFF(발급한 직원 ID). 받은 시각 최근 순, 커서 = base64(issuedAt|couponId). 마이그레이션 없음(행사 기준은 `ix_coupon_promotion_account` 로 찾고 정렬은 행사 안에서). 시험 `CouponRecordRulesTest`·`PromotionsIT.couponRecordsByCustomerAndPromotion`.
