# product-service 요청 (받는 영역 platform — PC 1)

## PD-1. 상품 조회 목록의 판매 상태별 수 — 목록 맨 위 숫자 띠 (2026-11-19, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: 백오피스 웹(pmart-clients `docs/handoff/backoffice/2026-11-19-backend-list-status-counts.md` — 사용자 "전체 UI·UX 를 관제 화면 형식으로" → 목록마다 상태별 숫자 띠). 첫 쪽만 세면 틀린 숫자라 서버 수가 필요하다.
- **필요한 것**: `GET /products/counts?<목록과 같은 거르기>` — `GET /products` 와 같은 거르기(`categoryId` 등, `saleState` 거르기는 제외)로 `saleState`(SaleState)별 수.
- 응답 모양(웹 요청 그대로, case 0.26.0 `StatusCounts` 와 같게): `{"counts":[{"key":"<상태>","count":12,"capped":false}],"asOf":"…"}` — **모든 상태 값을 0 까지 빠짐없이**, 셀 상한을 넘으면 `capped=true`(화면 "N+"), 원천을 못 읽으면 503(화면 "?").
- **받는 쪽**: 웹이 상품 조회 목록에 직접(bff 를 거치지 않음 — 화면 하나가 원천 하나).
