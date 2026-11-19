# product-service 요청 (받는 영역 platform — PC 1)

## PD-1. 상품 조회 목록의 판매 상태별 수 — 목록 맨 위 숫자 띠 (2026-11-19, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: 백오피스 웹(pmart-clients `docs/handoff/backoffice/2026-11-19-backend-list-status-counts.md` — 사용자 "전체 UI·UX 를 관제 화면 형식으로" → 목록마다 상태별 숫자 띠). 첫 쪽만 세면 틀린 숫자라 서버 수가 필요하다.
- **필요한 것**: `GET /products/counts?<목록과 같은 거르기>` — `GET /products` 와 같은 거르기(`categoryId` 등, `saleState` 거르기는 제외)로 `saleState`(SaleState)별 수.
- 응답 모양(웹 요청 그대로, case 0.26.0 `StatusCounts` 와 같게): `{"counts":[{"key":"<상태>","count":12,"capped":false}],"asOf":"…"}` — **모든 상태 값을 0 까지 빠짐없이**, 셀 상한을 넘으면 `capped=true`(화면 "N+"), 원천을 못 읽으면 503(화면 "?").
- **받는 쪽**: 웹이 상품 조회 목록에 직접(bff 를 거치지 않음 — 화면 하나가 원천 하나).

## PD-2. 상품 초안 목록 — 백오피스 "등록 진행 중" 탭 (2026-11-19, 보낸 영역 backoffice — PC 2, 받는 쪽 platform PC 1)

- **요청한 곳**: 백오피스 웹 질문서 `pmart-clients docs/handoff/backoffice/2026-11-19-backend-product-flow-questions.md` Q2. 상품·콘텐츠 화면 합치기(사용자 결정 2026-11-19 — 조회 → 메뉴 정리 → 등록 한 흐름)의 "등록 진행 중" 탭에 작성 중·제출·반려된 상품 초안을 한곳에 보인다. 지금 `/product-drafts` 는 만들기·한 건 조회·고치기·제출뿐이라 초안 ID 를 알아야만 연다.
- **필요한 것**: 직원 `GET /product-drafts?state=&mine=&cursor=&size=`(state = DRAFT·SUBMITTED·REJECTED 등 반복, mine=true 면 만든 직원 본인 것만, 최근 순·커서) → 초안 요약(draftId·관리용 이름·카테고리·상태·검증 결과 요약·변경 요청/승인 ID·만든 직원·시각). 권한은 초안 만들기와 같게(MD·OPERATOR_ADMIN).
- 콘텐츠 초안 목록(product-content — 웹 요청 `backend-content-draft-list`)과 같은 모양이면 화면이 한 표로 합친다.

## PD-3. 개별 등록에서 상품 초안과 콘텐츠 초안을 원천 키로 미리 잇기 — 확인 요청 (2026-11-19, 보낸 영역 backoffice — PC 2)

- **질문**(웹 질문서 Q1): "상품 등록" 한 흐름(상품 정보 → 고객 화면 → 가격 → 제출)에서 화면이 만든 원천 키(예 `sourceSystem=backoffice-manual`, `sourceKey=<화면이 만든 UUID>`)를 상품 초안·콘텐츠 초안에 같이 넣어, 상품이 승인·공개되면 콘텐츠 초안이 그 상품에 저절로 이어지게 해도 되는가.
- **계약에서 읽은 것(PC 2)**:
  - product `POST /product-drafts` 설명: "고객용 명칭·설명·이미지는 product-content-service 초안으로 따로 넣는다(**같은 sourceKey 로 연결**)" — 잇는 길이 의도된 것으로 읽힌다.
  - 그런데 `ProductDraftInput.sourceSystem` 설명은 "외부 원천(예 bmart-capture). **개별 입력은 생략**", 수정 초안 승인은 "원천 키를 싣지 않는다".
  - product-content `POST /content-drafts`: productId 를 생략하면 같은 원천 키로 발행된 `ProductVersionPublished.sources` 를 받을 때 연결.
- **확인해 줄 것**: ① 개별 입력에 `sourceSystem=backoffice-manual`(또는 정해 주는 값)을 넣어도 되는가, ② 새 상품 공개의 `ProductVersionPublished.sources` 에 그 키가 실리는가, ③ 같은 키로 두 번 제출하면 upsert(같은 초안 갱신)인지 — **오늘 LAN 상품 중복 11쌍(PC 1 에 전달함)** 과 같은 일이 생기지 않게.
- 답에 따라 웹은 등록 흐름을 "한 번에 제출"(이어지면) 또는 "상품 승인 뒤 콘텐츠"(안 이어지면)로 만든다.
