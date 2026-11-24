# review-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `review-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다. 결정은 ADR-0063.

## 계약
- REST: `contracts/openapi/review-service.yaml`(0.8.0) → `openApiGenerate` 가 `com.pmart.review.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 구독 `DeliveryCompleted`(delivery.delivery.v1, 바인딩 `deliveryEvents-in-0`), 발행 `ReviewPublished`·`ReviewDeleted`(commerce.review.v1). 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `EventConsumers`(inbox).

## 패키지
`com.pmart.review.{api, application, domain, infrastructure}`. 규칙 `domain/ReviewRules`, 실행 `application/ReviewService`, 표 `infrastructure/ReviewStore`(V2 — `review_eligibility`·`review`·`review_photo`), 내부 조회 `infrastructure/Upstreams`(order·customer·center-master·product-content 카드).

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.
- 리뷰 본문·별명은 로그 금지.

## 실행
```bash
./gradlew :services:review-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:review-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8108. LAN PC 2.

## 리뷰 1차 (2026-11-16, ADR-0063)
- 자격: `DeliveryCompleted` 품목마다 `review_eligibility`(첫 수령 시각·받은 수량 누계, 계정·SKU·상품·상품명은 order 내부 주문 사본). order 에 없는 주문은 건너뜀, order 장애는 예외(재시도·DLT).
- 작성 `POST /reviews`: 본인 품목·상품 있음(아니면 422 NOT_ELIGIBLE) → order 로 반품 확인(받은 수량 전부 반품이면 NOT_ELIGIBLE, order 장애 503) → 작성자 표시(customer, 실패면 "PMart 고객") → 멱등 처리 안에서 자격 행 잠금·중복(ALREADY_REVIEWED / 삭제됨 REWRITE_AFTER_DELETE_FORBIDDEN)·기한(ELIGIBILITY_EXPIRED)·사진 붙이기(PHOTO_NOT_FOUND) → `ReviewPublished`.
- 수정 `PATCH`: 본인(남의 것 404)·삭제됨/3일 지남 409 INVALID_STATE·X-Expected-Version. photoIds 를 주면 그 목록으로 바꾸고 빠진 사진은 떼어 정리 대상. 다시 `ReviewPublished`.
- 삭제 `DELETE`: 멱등(이미 삭제면 204), 행·사진은 남김(공개만 닫힘), `ReviewDeleted`.
- 사진: `POST /review-photos`(10MB·JPEG/PNG/WEBP, 키 `review-photos/<accountId>/<photoId>`), `GET /review-photos/{id}`(공개 리뷰에 붙은 것만). `OrphanPhotoSweeper` 1시간마다 24시간 지난 미부착·분리 사진 삭제. 버킷 미설정이면 사진만 503.
- 공개 경로(`permit-paths`)는 경로 단위라 `/reviews/*` 의 PATCH·DELETE 도 토큰 없이 들어온다 — 서비스가 고객 토큰을 다시 확인한다(403).
- 설정 `pmart.review.order-base-url`·`customer-base-url`, `pmart.storage.s3.*`.
- **주문 리뷰 작성 여부 내부 조회(2026-11-18, 계약 0.3.0 — 사용자 결정 "A해줘", 리뷰 요청 알림)**: `GET /internal/orders/{orderId}/review-status?accountId=`(토큰 없음) → 그 계정의 자격 품목 수·리뷰 쓴 품목 수(삭제 포함)·아직 쓸 수 있는 품목 수(리뷰 없음·수령 + 14일 안)·reviewed. 자격이 없거나 남의 주문이면 모두 0(404 아님), 반품은 보지 않는다(작성 때 order 로 확인). 부르는 쪽 notification-service(배송 완료 1시간 뒤 리뷰 요청). `ReviewStore.orderStatus`, 시험 `ReviewsIT.orderReviewStatusForNotification`.

## 상태
리뷰 자격·작성·수정·삭제·공개 조회·사진·내부 조회 구현(2026-11-16). 운영자 숨김·신고 없음(사용자 결정). 평점 집계 투영은 product-content(아직 구독 안 함).
- **사진 축소본(2026-11-20, 계약 0.4.0 — 리뷰 사진 느림)**: `GET /review-photos/{id}?w=240|480|720` → `ReviewPhotoVariants`(버킷 `review-photo-variants/w<폭>/<photoId>.jpg`, 원본보다 크면 원본 크기 JPEG, WEBP 는 원본, 요청 때 만들기 동시 2). `ReviewPhotoPreparer` 가 올린 5초 뒤·기동 30초 뒤·2분마다 미리 만든다(`review_photo.variants_at`, V3). 고아 사진 정리 때 축소본도 지운다. `JpegResizer` 는 product-content 와 같은 방식(복사 — 라이브러리로 뺄지는 셋째가 생기면).
- **리뷰관리 표시 값(2026-11-20, 계약 0.5.0 — 고객 앱 BR-67, 사용자 "추천대로")**: `GET /me/review-eligibilities` 에 `total`(조건에 맞는 전체)·`counts{writable,written}`(탭 머리, state 무시 — `/me/reviews` 는 없다), 품목에 `orderedAt`·`mfcName`·`imageUrl`·`optionLabel`(`EligibilityDisplay`). 주문 확정 시각·센터 ID 는 자격 만들 때 order 사본(`review_eligibility.ordered_at·mfc_id`, V4 — 그 전 행은 목록 조회 때 주문마다 order 를 한 번 읽어 채움), 센터 이름은 center-master `/internal/centers/{id}` 10분 사본, 사진·옵션 라벨은 product-content `GET /product-contents?productIds=`(한 쪽에 한 번, 100개씩) 의 **지금** 대표 사진. 못 읽으면 그 값만 null. 설정 `pmart.review.center-master-base-url`·`content-base-url`(없으면 그 값 null). 시험 `EligibilityDisplayTest`·`ReviewsIT.eligibility*`.
- **추천순·옵션 필터·도움돼요(2026-11-21 BR-76, 계약 0.6.0·V5 — 사용자 "BR-76부터 해줘")**: 목록 `sort=RECOMMENDED`(helpful_count DESC → created_at DESC)·`sku` 거르기, `summary.reviewCount` = 거른 수(평균은 상품 전체). `review.helpful_count` + 표 `review_helpful`(리뷰·회원 PK). `PUT`·`DELETE /reviews/{id}/helpful`(회원만, `ReviewService.helpful` — 멱등 처리 안에서 리뷰 행 잠금, 내 리뷰 422 HELPFUL_OWN_REVIEW, 공개 아님 409 REVIEW_NOT_PUBLISHED, 취소는 상태 무관). 목록은 회원 토큰이 있으면 `helpedByMe`(없으면 null). 게이트웨이 라우트에 `/api/v1/reviews/*/helpful`. 시험 `ReviewsIT.recommendedSortSkuFilterAndHelpful`.
- **주문별 쓸 수 있는 품목 수(2026-11-23, 계약 0.6.1 — 고객앱 BR-86)**: `GET /me/review-eligibilities` 품목 `orderWritableCount` — 쪽에 나온 주문들을 `ReviewStore.writableByOrder`(자격 부분 질의 computed_state REV_ELIGIBLE 을 order_id 별로) 한 번에. state·쪽과 상관없음. 시험 `ReviewsIT`(쪽 크기 1 → 1).
- **리뷰 신고(2026-11-23, 계약 0.7.0·V6 — 고객앱 BR-90, 사용자 정책 + "추천대로")**: `application/ReviewReports` — 고객 `POST /reviews/{id}/reports`(회원·한 리뷰 한 번 `uq_review_report_reporter`·내 리뷰 422·OTHER 만 글, APPLIED), 운영자(CS_AGENT·OPERATOR_ADMIN) `GET /review-reports`(리뷰별 묶음 `ReviewReportStore.groups`)·`POST /review-reports/{id}/resolutions`(HIDE = review.state `HIDDEN` + `ReviewDeleted` reason OPERATOR_HIDDEN, KEEP = 신고만 닫음). 숨긴 리뷰: 공개 목록·집계는 PUBLISHED 만이라 빠짐, 단건은 작성자만(LOCKED — 계약 상태 값을 늘리지 않음), 자격 상태식도 LOCKED, 수정 409. 목록 카드 `reportedByMe`·`mine`. 시험 `ReviewsIT.reportAndOperatorHide`.
- **이용제한 고객 작성 막기(2026-11-23 CU-7)**: 작성 때 부르는 customer `review-author`(0.16.0 `restriction`·`restrictedUntil`)가 RESTRICTED·SUSPENDED 면 403 `ACCOUNT_RESTRICTED`(`Upstreams.author`). customer 를 못 읽으면 막지 않는다(기본 작성자 표시). 시험 `ReviewsIT.restrictedCustomerCannotWriteReview`.
- **신고 상세(2026-11-24, 계약 0.8.0 — 통합 백오피스 사용자 "보이는데 눌러서 상세확인 같은걸 할 수 없다고", 신고자 "안 보여 줌")**: `GET /review-reports/{reviewId}`(CS_AGENT·OPERATOR_ADMIN, `ReviewReports.detail`·`ReviewReportStore.items`) — 리뷰 글 전체·사진·숨김/삭제·`reviewUpdatedAt`, 신고 한 건 한 건(사유·기타 글·신고 시각·처리 결과·처리 직원·처리 메모, 최근 순, 처리 전·끝 모두)·`openReportCount`. **신고자 열은 읽지도 않는다.** 신고 없는 리뷰·없는 리뷰 404 REVIEW_NOT_FOUND. 사진 본문은 상담원·운영 관리자 토큰이면 숨긴(HIDDEN) 리뷰 것도(`ReviewService.photo` — 작성자가 지운 리뷰는 그대로 404). 새 경로 — 게이트웨이 라우트(pmart-config, PC 1)·허용 목록. 시험 `ReviewsIT.reportAndOperatorHide`.
