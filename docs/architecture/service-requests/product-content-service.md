# product-content-service 요청 (받는 영역 platform — PC 1)

## PCT-1. 콘텐츠 초안 목록 `GET /content-drafts` — 백오피스 콘텐츠 조회 (2026-11-20 등록, 웹 요청 2026-11-19, 보낸 영역 backoffice — PC 2, 받는 쪽 platform PC 1)

- **요청한 곳**: 백오피스 웹 `pmart-clients docs/handoff/backoffice/2026-11-19-backend-content-draft-list.md`(CD-1·CD-2 — 사용자 "콘텐츠 조회 기능이 추가됐으면"). 지금 `/content-drafts` 는 만들기(POST)·한 건 조회(`/content-drafts/{draftId}`)뿐이라 작성 중·승인 대기·반려된 초안은 **초안 ID 를 알아야만** 열 수 있다.
- **필요한 것(CD-1)**: 직원 `GET /content-drafts?state=&productId=&cursor=&size=`(모두 선택, state 여럿이면 반복 또는 쉼표 — 계약 관례대로) → 줄 `draftId`·`productId`·`state`·`name`(고객용 상품명)·대표 `imageId`(있으면)·`approvalId`(있으면)·`updatedAt`·`version`, 최근 바뀐 것 먼저. 권한은 지금 POST 와 같게.
- **선택(CD-2)**: 같은 거르기(상태 빼고)로 상태별 수 — PD-1 과 같은 모양(`{"counts":[{"key","count","capped"}],"asOf"}`, 모든 상태 0 까지).
- 상품 초안 목록(PD-2)과 줄 모양이 비슷하면 웹 "등록 진행 중" 탭이 한 표로 합친다(상품·콘텐츠 화면 합치기 — 사용자 결정 2026-11-19).
- **받는 쪽**: 웹 콘텐츠 조회에 직접. 받기 전까지 웹은 게시된 콘텐츠만 보이고 안내 상자에 적어 둔다.
- **상태**: 요청(PC 1 대기, 급하지 않음).
