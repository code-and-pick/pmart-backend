# 처리 끝난 클라이언트 handoff (archive)

`docs/handoff/clients/` 에서 **처리가 끝난 파일**을 옮겨 둔 곳(2026-11-13 사용자 결정 "처리 끝난 오래된 파일은 그렇게 하자"). 파일 이름·내용은 그대로 — 기록으로 남긴다.

## 옮기는 기준
- 끝남: 요청·회신 항목이 양쪽 모두 반영됨(클라이언트 `docs/backend-requests.md` BR 상태 RESOLVED·구현 커밋), 공지한 변경이 이미 쓰이고 남은 할 일이 없음.
- 대체됨: 뒤 파일이 같은 내용을 바꿈(아래 표에 대체 파일).
- 남은 할 일·결정이 하나라도 있거나 지금도 유일한 설명 문서면 옮기지 않는다(애매하면 남긴다).
- 옮길 때 저장소 안 링크는 같은 커밋에서 고친다. 일지(`docs/worklog/`)의 옛 경로는 기록이라 고치지 않는다 — 파일 이름으로 이 폴더에서 찾는다.

## 2026-11-13 1차 (11-09 이전 53개 중 33개)
판정 근거: 클라이언트 BR 상태표·클라이언트 git log·소스 확인·11-10 이후 백엔드 handoff.

- 끝남 31개: 10-24 br05-start-503 / 10-30 customer-addresses·customer-google-login·customer-payment·home-and-search·public-browse-and-test-data·reply-br27-google-login·reply-customer-app-priority / 10-31 checkout·google-profile·order-cancel·product-availability·reply-address-geocoding·request-google-server-auth-code-test / 11-02 reply-br34·br35·br37·request-seed-v1-baseline·request-session-expiry / 11-03 price-import-batch·reply-br39·reply-br40-br43 / 11-04 reply-br44-br48 / 11-05 search-changes / 11-06 request-backoffice-lan-api-base·request-customer-home-stale-price / 11-07 fulfillment-auto-cancel / 11-09 assistant-lan-generative·center-manager-term·handoff-folder-moved·pick-visit-order
- 대체됨 2개: 10-31 backoffice-hosting → `11-06-…-backoffice-lan-api-base`·`2026-11-13-all-reply-https-builds-published-http-closed.md`(HTTPS), 11-02 toss-customer-key → `11-02-…-reply-br34-payment-widget-config`

남긴 20개(아직 할 일)는 바로 위 폴더 — MFC·백오피스 화면 요청(11-07~11-09), 보류 항목(BR-06 폴링·BR-03 쿠키·연락처), 백엔드 회신이 없는 클라이언트 질문(BR-50·51·52·53 등) 관련.
