# 알림 — 리뷰 신고 상세 `GET /review-reports/{reviewId}` (review-service 0.8.0)

보낸 영역: backoffice (PC 2 백엔드)
받는 앱: backoffice (통합 백오피스 웹 — 리뷰 신고 확인 화면 `features/reviews/ReviewReportPage.tsx`)

- 근거: 사용자(2026-11-24) "보이는데 눌러서 상세확인 같은걸 할 수 없다고" — 목록 줄을 눌러 여는 상세. 신고자 표시는 사용자 "안 보여 줌".

## 경로
`GET /api/v1/review-reports/{reviewId}` — CS_AGENT·OPERATOR_ADMIN(그 밖 403). 신고가 없는 리뷰·없는 리뷰 404 `REVIEW_NOT_FOUND`.

## 응답 `ReviewReportDetail`
- 리뷰: `reviewId`·`productId`·`sku`·`rating`·`text`(글 전체)·`authorMasked`·`reviewCreatedAt`·`reviewUpdatedAt`(작성자 수정·운영자 숨김 때 바뀜, 없으면 null)·`reviewHidden`·`reviewDeleted`·`photos[{photoId, url}]`.
- `openReportCount` — 처리 전 신고 수. 0 이면 처리(숨김/유지) 버튼을 두지 않는다.
- `reports[]`(최근 신고 순, 처리 전·끝 모두): `reportId`·`reason`(ABUSE·AD·IRRELEVANT·PRIVACY·OTHER)·`detail`(기타 글)·`reportedAt`·`state`(OPEN·RESOLVED)·`resolution`(HIDE·KEEP)·`resolvedAt`·`resolvedBy`(직원 ID — 이름은 지금처럼 `StaffName`)·`resolutionNote`(처리 메모).
- **신고자(고객)는 없다.**

## 사진
- 공개 리뷰 사진은 지금처럼 `<img src>` 로 열린다.
- **숨긴 리뷰 사진은 직원 토큰(Authorization 헤더)이 있어야 열린다** — `<img>` 는 헤더를 못 보내니 fetch → blob URL 로 보여 주어야 한다(없으면 404). 작성자가 지운 리뷰 사진은 직원에게도 404.

## 상태
- 서버 PR(pmart-backend) — 병합·LAN 배포 뒤 다시 알린다. LAN 은 PC 1 이 게이트웨이 경로(pmart-config)를 더해야 열린다.
