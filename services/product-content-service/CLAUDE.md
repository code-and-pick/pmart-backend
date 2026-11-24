# product-content-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `product-content-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/product-content-service.yaml` → `openApiGenerate` 가 `com.pmart.product.content.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.product.content.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:product-content-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:product-content-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8102.

## 이미지 저장소
- 본체는 S3 호환 저장소(로컬·LAN SeaweedFS, ADR-0016), DB 에는 버킷·키·메타만. 키 `products/{productId 또는 draft-{draftId}}/{role}/{sha256}.{ext}`, 같은 sha256 은 다시 올리지 않는다.
- 설정 `pmart.storage.s3.*` ← pmart-config `product-content-service-<프로필>.yml`(자격 증명은 {cipher}). 버킷·키가 없으면 기동 실패.
- 버킷은 비공개. 클라이언트는 `GET /content-images/{imageId}` 로 받는다. 게시된 이미지는 토큰 없이(비회원·`<img src>`), 초안 이미지는 운영자·서비스만. presigned URL 을 내주지 않는다.
- 상품 상세·카드·게시 이미지는 공개(계약 `security: []`, 비회원 둘러보기 2026-10-30).
- 자동 시험(테스트 코드·Git 에 들어가는 자료)의 묶음·이미지는 합성 바이트만.
- seed-v1 묶음(B마트 화면 캡처)의 `images/`(main·description_NN·info)는 **LAN·로컬 저장소(SeaweedFS)에 내부 확인용으로 올려도 된다**(사용자 결정 2026-11-02). 묶음 zip·`captures/` 는 Git·컨테이너 이미지·공개 저장소·외부 공개 화면에 넣지 않는다(묶음 README 의 재배포 금지). 운영(prod)에는 올리지 않는다.

## 상태
2026-10-30 임시 백오피스 등록 구현: 콘텐츠 초안(개별·seed-v1 배치 upsert)·이미지 업로드·제출 준비(DISPLAY 승인 본문), ProductVersionPublished 투영·원천 키 연결, ApprovalDecided(DISPLAY) → 게시(`product_content` 불변 버전) + ProductContentPublished(commerce.product.v1), 고객 조회(상세·카드·스냅샷·projection-status).
미구현(501): `/product-contents/{id}/publications`(게시는 승인 이벤트로만). `rating-summary` 는 2026-11-19 구현(아래 평점 집계). 옵션 가격은 아래 절.

## 신규 / 기존 게시 갱신 (BR-44, 2026-11-04)
- 심사 전 초안 응답의 `registration`(NEW/UPDATE)·`publishedContentVersion`·`publishedDraftId`·`publishedAt`, 승인 행 attributes 의 `registration`·`publishedContentVersion` — `ContentDraftService.existingPublished`(연결 상품 최신 게시본, 없으면 같은 원천 키의 최근 게시 초안).
- 묶음 업로드는 메모리에 통째로 올리지 않는다(`UploadedBundle`, BR-48).

## 옵션 가용 (2026-10-31)
- 상세·카드에 `mfcId` 를 주면 SKU 옵션마다 mfc-inventory `/internal/availability`(내부 호출, 토큰 없음) 사본을 붙인다(`ContentRules.availability`): 취급 OFF carried=false·UNKNOWN, A≤0 SOLD_OUT, 그 밖 AVAILABLE. LOW("N개 남음")는 노출 임계값이 운영 설정이라 안 쓴다.
- 재고 조회 실패는 화면을 실패시키지 않고 UNKNOWN(0·품절 아님), 상세 sources 에 mfc-inventory-service UNAVAILABLE. mfcId 가 없으면 채우지 않는다.

- **승인 반영 회신(RC-1, 2026-11-19 — ADR-0039)**: `application/ApprovalReceipts` — ApprovalDecided(APPROVED·PARTIALLY_APPROVED)를 반영한 같은 inbox 트랜잭션에서 `ApplicationReceiptRecorded`(토픽 platform.approval-receipt.v1, receiverService = 이 서비스, 순번 1, 행마다 APPLIED·REJECTED + reasonCode). 반려 결정에는 회신 없음. 규칙 `docs/architecture/service-requests/approval-receipt-publishers.md` RC-1.
- **옵션 가격(2026-11-19, 계약 0.1.2 — 클라이언트 BR-36 "상세에 가격이 안 보임")**: 상세·카드를 읽을 때 `infrastructure/PricingClient` → pricing `GET /api/v1/prices?skus=`(공개, 100개씩, `pmart.content.pricing-base-url` 기본 http://pricing-service:8107)로 옵션 `price{basePrice, priceVersion}` 를 채운다(`ContentQueries.withPrices`). 미설정 SKU 는 빈 칸, 조회 실패는 가격 없이 + 상세 sources pricing-service UNAVAILABLE(0원 아님). 시험 `ContentRegistrationFlowIT`(스텁 전 UNAVAILABLE·스텁 뒤 3980원).
- **단위 가격(2026-11-19, 계약 0.2.0 — 클라이언트 BR-19)**: 투영이 ProductVersionPublished.skus[].netContent 를 `product_projection.skus` 에 담고(새 사건부터), 옵션에 `netContent`, 가격이 붙으면 `unitPrice{basis 100, unit, price}` = `ContentRules.unitPrice`(기본가 × 100 ÷ 내용량, 0.5 올림). 내용량·가격 중 하나라도 없으면 unitPrice 없음. 할인가 기준은 앱 몫. 시험 `ContentRulesTest.unitPricePer100RoundsHalfUp`·`ContentRegistrationFlowIT`(3980원·180g → 2211).
- **평점 집계(2026-11-19, 계약 0.2.1·V3 — 사용자 "리뷰에서 api 는 구현하고")**: 구독 `reviewEvents-in-0` ← commerce.review.v1 `ReviewPublished`(작성·수정)·`ReviewDeleted` → `review_rating`(리뷰마다 한 줄, 리뷰 버전 = aggregateVersion 큰 것만 — `ReviewRatingStore.upsert`, `ReviewRatingProjector`). `GET /product-contents/{id}/rating-summary` **비회원도**(계약 security [] + 설정 permit-paths + 게이트웨이 public-paths): reviewCount·averageRating(소수 첫째, 리뷰 없으면 null — 0점 아님)·ratingCounts 1~5·asOf. 게시 콘텐츠가 없어도 사본으로 센다. 시험 `ContentRegistrationFlowIT.ratingSummaryCountsPublishedReviews`.
- **휴대폰용 축소 이미지(2026-11-19, 계약 0.3.0 — 상품 상세 느림, 사용자 "1 -> 해줘")**: `GET /content-images/{id}?w=240|480|720`. `application/ImageVariants` — 원본 폭이 w 이하·JPEG/PNG 가 아니면(WEBP 는 JDK 가 못 풂) 원본, 아니면 저장소 `variants/w<폭>/<sha256>.jpg` 를 읽고 없으면 `infrastructure/JpegResizer`(JDK ImageIO·Java2D, 반씩 줄인 뒤 bicubic, 흰 바탕, JPEG 0.82 — 새 의존성 없음)로 만들어 저장(동시에 둘까지). ETag `<sha256>-w<폭>`, 캐시는 원본과 같게. 그 밖의 w 는 400. 권한 검사는 원본과 같다(`ContentQueries.visibleImage`). 미리 만들어 두지 않는다 — 처음 보는 사람이 한 번 기다린다(LAN 측정 2026-11-19: 처음 0.2~0.5초, 다음 30~50ms. 미리 훑어 만드는 것은 아직 안 함). EXIF 회전은 반영하지 않는다(지금 이미지에는 없음). 시험 `JpegResizerTest`·`ImageVariantsTest`·`ContentRegistrationFlowIT`(풀 수 없는 본문은 원본·w=1000 은 400).
- **상세·카드 rating(2026-11-19, 계약 0.3.0 — 클라이언트 BR-63 "햇양파 리뷰 480개가 안 보임")**: `ContentQueries.withRatings` — 상세·카드의 `rating` 을 `review_rating` 사본으로 채운다(`ReviewRatingStore.summaries` 한 번에, rating-summary 와 같은 모양). 리뷰 없으면 reviewCount 0·averageRating null. 시험 `ContentRegistrationFlowIT`(리뷰 2개 → 상세·카드 3.5·2).
- **축소본 미리 만들기(2026-11-20, 상품 상세 느림 — 사용자 "응")**: `VariantPreparer` 가 뒤에서 스레드 하나(낮은 우선순위)로 이미지 내용(sha256)마다 240·480·720 축소본을 만들어 둔다 — 기동 30초 뒤·2분마다·이미지 올린 5초 뒤. 기록 `content_image_variant`(V4). 처음 보는 상품의 설명 사진 16~20장이 요청 때 만들어져 0.7~1.5초 늦던 것. 요청 때 만들기(`ImageVariants.variant`)는 그대로 두되 동시 2 → 4.
- **검색 색인용 카드 칸(2026-11-22, 계약 0.5.0 — 검색 products-v8)**: `GET /product-contents?include=search` 면 카드마다 `search{originType, originText, description}`(없으면 search null). 원산지 = 게시 콘텐츠 notices 의 첫 "원산지:" 줄(`domain/OriginRules` — 원재료마다 값을 나눠 모두 국내면 DOMESTIC, 하나라도 외국이면 IMPORTED, 원양산만·판독 못 함은 null, 지어내지 않는다). 영양은 싣지 않는다. 쓰는 곳 search-service Logstash. 시험 `OriginRulesTest`(LAN 실제 줄 모양)·`ContentRegistrationFlowIT`.
- **표시사항 값(2026-11-23, 계약 0.6.0·V5 — 사용자 "영양 넣어줘"·"알레르기 표시·검색만")**: 초안 입력 `nutrition`(100g/100mL 기준 basisUnit G·ML, energyKcal·sodiumMg·sugarsG·proteinG·fatG — 모르면 null)·`allergens`(contains·mayContain(같은 시설)·text, 법정 이름만 `domain/LabelFacts.ALLERGEN_NAMES` — 계란 → 알류로) → `content_draft.label_facts`·게시 때 `product_content.label_facts`(jsonb `{nutrition, allergens}`) → 상세·카드 그대로(없으면 null = 모름, 없음 아님). seed-v1 content 블록 같은 이름. 내용 해시는 값이 있을 때만 더한다(옛 초안 해시 그대로). 잘못된 값 400 VALIDATION_FAILED. 시험 `LabelFactsTest`·`ContentRegistrationFlowIT`. LAN 데이터 2026-11-23: 358개(영양 239·알레르기 300 — 검토표 + 이미지 판독, `상품데이터 보정/결과/nutrition_allergens.json`) 일괄 등록·DISPLAY 승인 3건.
- **상품 사진 규격 경고(2026-11-24, 계약 0.8.0 — 사용자 "저 대안대로 작업")**: `domain/ContentImageSpec` — 대표 1:1(±10%)·1000×1000 이상, 상세 설명·상세정보 표 가로 1000px(세로 자유). **막지 않음** — 초안 응답 `warnings[]`(`ContentDraftService.warnings` — MAIN_IMAGE_NOT_SQUARE·MAIN_IMAGE_SMALL·IMAGE_NARROW·IMAGE_SIZE_UNKNOWN), `problems`·`submittable`·일괄 등록은 그대로. 규격 조회 `GET /content-drafts/image-specs`(직원, RegistrationController). 시험 `ContentImageSpecTest`·`ContentRegistrationFlowIT`(합성 JPEG → IMAGE_SIZE_UNKNOWN, 제출 가능).
