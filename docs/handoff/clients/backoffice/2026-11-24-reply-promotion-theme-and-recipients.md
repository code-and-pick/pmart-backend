# 회신 — 행사 대상 테마(THEME) · 지정 발급 쿠폰 받을 고객(recipients)

보낸 영역: backoffice
받는 앱: backoffice
원 문서: `pmart-clients docs/handoff/backoffice/2026-11-24-backend-promotion-theme-and-recipients.md`
작성: pmart-backend PC 2 백엔드, 2026-11-24 — 사용자 "추천안대로 진행"(PC 1 사용자 지시로 구현은 PC 2)
상태: **PR 대기**(promotion-service 0.3.0 + storefront-service 0.14.0) — 병합·LAN 배포는 PC 1

## 1. 기간 할인 대상 THEME
- `PromotionTarget.type` = `THEME`, `id` = 진열 편성 테마 sectionId(`T-…`). 대상 = **지금 게시된 진열에서 그 테마에 든 상품의 모든 옵션(SKU)**. 테마 상품이 바뀌면 다시 승인 없이 할인도 따라간다(카테고리와 같다).
- 정한 것(사용자 "추천안대로"):
  - 승인 뒤 테마가 진열에서 빠지면 **할인도 멈춘다**(행사는 APPROVED 그대로 — 테마가 다시 게시되면 다시 걸린다).
  - **자동 랭킹(RANKING_AUTO) 테마는 고를 수 없다** — 제출 `problems` 에 `code: THEME_AUTO_RANKING`.
  - 게시본에 없는 테마(초안에만 있거나 빠짐)는 `code: THEME_NOT_PUBLISHED`. 초안 저장은 되고 제출만 막힌다(지금 규칙 그대로).
  - 테마 자체의 노출 기간(startsAt·endsAt)은 보지 않는다 — 할인은 행사 기간만.
  - promotion 이 1분마다 storefront 에서 게시 테마를 받아 둔다 → **진열이 바뀐 뒤 할인 반영까지 최대 약 1분 30초**(표시 할인 30초 주기 포함).
- 고를 목록: **`GET /api/v1/promotions/theme-targets`**(MARKETING·OPERATOR_ADMIN·CS_AGENT)
  → `{ layoutVersion, syncedAt(null 이면 아직 못 받음), themes:[{ sectionId, kind(THEME·SEASON·WEEKLY_PICK·RANKING_AUTO), title, productCount(상품 수), selectable(RANKING_AUTO 면 false) }] }`
- 행사 상세 `targets[].title` — THEME 이면 받아 둔 테마 이름(진열에서 빠졌으면 null — 화면은 ID 로).
- `Problem` 에 `code`(없을 수 있음) — 이번에 THEME_NOT_PUBLISHED·THEME_AUTO_RANKING·RECIPIENTS_NOT_ADMIN.

## 2. 지정 발급(ADMIN) 쿠폰 — 만들 때 받을 고객
- `CouponPolicy.recipients`: `{ accountIds: string[] (1~100, 겹침 없이), reason: string(1~200) }` — 선택. ADMIN 이 아니면 `code: RECIPIENTS_NOT_ADMIN`.
- **승인되고 시작 시각이 되면 서버가 1분 안에** 그 고객들에게 한 장씩(지정 발급과 같은 규칙 — 1인 한도·총 수량, 쿠폰 도착 알림 그대로). 발급한 사람 = 행사를 만든 직원, 사유 = 적은 사유.
- 시작 전에 행사가 끝나면(조기 종료) 발급하지 않는다 — 결과는 PENDING 그대로.
- 결과: 행사 상세 **`recipientResults[]`**: `{ accountId, result(PENDING·ISSUED·SKIPPED_LIMIT·SOLD_OUT), couponId, processedAt }`(넣은 순서). 응답 `coupon.recipients` 로 넣은 값을 돌려준다.
- 승인 심사: 승인 요청 `summary` 끝에 "· 받을 고객 N명(시작 때 발급)", 줄 attributes `recipientCount`.
- 진행 중에 더 줄 때는 지금처럼 `POST /promotions/{id}/coupon-grants`.

## 3. 백엔드 변경
- promotion-service 0.3.0(V5 — 테마 사본 `theme_copy`·`theme_copy_product`·`theme_copy_state`, `promotion_recipient`, `promotion.recipient_reason`). storefront-service 0.14.0 내부 `GET /internal/storefront/themes`.
- 업무 원문 Commerce §6.3·6.4 에 2026-11-24 결정을 적음, ADR-0082 덧붙임.
- 게이트웨이: 새 경로는 `/promotions/**` 안이라 라우트 변경 없음, 허용 목록 한 줄.
