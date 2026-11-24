# 알림 — 사진 규격(배너·상품 사진 모두 경고만 — 막지 않음)

보낸 영역: backoffice
받는 앱: backoffice
작성: pmart-backend PC 2 백엔드, 2026-11-24 — 사용자 "사진이 들어가는 곳 옆에 사진 규격 필요" → "가로 세로 크기나 비율이 필요한건데" → "저 대안대로 작업"
상태: **PR 대기**(storefront-service 0.15.0 + product-content-service 0.8.0) — 병합·LAN 배포는 PC 1

값은 고객 앱이 그리는 비율 그대로다(홈·카테고리 배너 = 화면 폭 ÷ 2.8, 팝업 4:5, 로딩 화면 = 화면 가득, 상품 대표 = 정사각형, 상세 = 화면 폭 맞춤). 앱은 칸을 채우고 넘치는 곳을 자른다.

## 1. 배너 — 경고만(같은 날 사용자 "서버에서 막지 않고 경고만 표시")
| 자리 | 비율 | 권장 | 최소 |
|---|---|---|---|
| HOME_TOP·HOME_MIDDLE·HOME_INLINE·CATEGORY | 2.8:1 | 1960×700 | 1080×386 |
| POPUP | 4:5 | 1080×1350 | 720×900 |
| SPLASH | 9:16 | 1080×1920 | 720×1280 |
- 비율 허용 ±10%. 형식 JPEG·PNG·WEBP, 5MB 이하(그대로).
- 서버는 막지 않는다. 진열 편성 초안 응답(`GET/POST/PUT /storefront/layout-drafts…`)에 새 칸 **`warnings[]`**: `{ field: "banners[i].imageId", code, detail }` — code `BANNER_IMAGE_RATIO`(비율 ±10% 밖) · `BANNER_IMAGE_TOO_SMALL`(최소보다 작음) · `BANNER_IMAGE_SIZE_UNKNOWN`(가로·세로를 못 읽음), detail = 지금 크기·권장 크기. 제출(`…/submissions`)은 그대로 된다.
- 올린 그림 응답의 `width`·`height` 로 초안에 넣기 전에도 미리 알려 줄 수 있다.
- 지금 LAN 배너(1984×793 = 2.5:1)도 경고만 붙고 그대로 제출된다.
- 규격 조회: **`GET /api/v1/storefront/banner-image-specs`**(직원) → `{ ratioTolerance: 0.1, maxBytes, contentTypes, items:[{ placement, ratio, ratioLabel("2.8:1"·"4:5"·"9:16"), recommendedWidth, recommendedHeight, minWidth, minHeight }] }` — 화면은 이 값을 그대로 띄운다(하드코딩하지 않기).

## 2. 상품 사진 — 막지 않고 경고만
| 역할 | 규격 |
|---|---|
| MAIN(대표) | 1:1(±10%), 1000×1000 이상 권장, 1장 |
| DESCRIPTION·DETAIL | 가로 1000px 권장, 세로 자유 |
- 형식 JPEG·PNG·WebP, 장당 20MB, 초안 100장까지(그대로 — 넘으면 막음).
- 초안 응답에 새 칸 **`warnings[]`**(`problems` 와 같은 모양 `{code, field, detail}` — 제출을 막지 않음, `submittable` 에 영향 없음): `MAIN_IMAGE_NOT_SQUARE` · `MAIN_IMAGE_SMALL` · `IMAGE_NARROW` · `IMAGE_SIZE_UNKNOWN`. `field` = `images[역할:순서]`(예 `images[MAIN:0]`), `detail` = 지금 크기와 권장.
- 일괄 등록(seed zip)도 그대로 받고 경고만 붙는다.
- 규격 조회: **`GET /api/v1/content-drafts/image-specs`**(직원) → `{ maxBytes, contentTypes, maxImagesPerDraft, items:[{ role, ratioLabel("1:1"|null), ratioTolerance, recommendedWidth, recommendedHeight(null = 세로 자유), maxCount }] }`.
