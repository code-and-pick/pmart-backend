# 회신: 클라이언트 할 일 3건 받음 · 새 웹 빌드 · handoff 폴더 제안

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-13
- 대상: all (backoffice·hr-backoffice·mfc-app·web-common)
- 답하는 것: pmart-clients `docs/handoff/2026-11-13-backend-client-todo-done.md`(ca2ecf3 — 클라이언트 작업 폴더에서는 `all/` 로 옮기는 중), `docs/handoff/all/2026-11-13-backend-handoff-folders.md`(클라이언트 작업 폴더에서 읽음 — 커밋 전)

## 1. 할 일 3건 — 받음

| 항목 | 백엔드 처리 |
|---|---|
| 옛 운영자 로그인 코드 삭제 + `gen:api`(9d5c372) | 받음. `other-pcs-todo.md` 클라이언트 절 체크 |
| 권한 기간 "기한 없음" | 받음. 같은 절 체크 |
| mfc_app `SUBJECT_BLOCKED` 안내 | 받음. PC 3 절 mfc_app 줄에 "안내 끝 — 실기기 확인만 남음" |
| HTTPS 빌드 소스 | 앞서 받음(13608a6) |

- 남은 것: 두 HTTPS 주소의 브라우저 확인은 사용자가 11-13 "HTTPS 전환했어"로 확인했다(그 빌드 기준). 이번 새 빌드는 올린 뒤 다시 봐 달라. mfc_app 실기기는 PC 3.

## 2. 새 웹 빌드 두 개

- 백오피스 `index-C_IVVcnv.js`, 인사팀 `index-B-0VeOMD.js` — **올리는 것은 사용자 확인 뒤**(PC 4 배포). 올리면 이 파일 아래에 결과(올린 진입 파일 이름)를 덧붙인다.
- 올리기 전에 지금 PC 4 에 떠 있는 index.html 과 비교한다(11-13 덮어쓰기 사고 뒤 정한 순서).
- **올림(2026-11-13, 사용자 "1번해줘")**: 올리기 전 PC 4 화면 = 직전에 올린 빌드(Dmg9_m1j·mOfMoV39) 확인 → `publish-backoffice`·`publish-hr-backoffice`. 지금 `https://192.168.0.40` → `index-C_IVVcnv.js`, `https://192.168.0.40:8444` → `index-B-0VeOMD.js`(둘 다 200, CSP 헤더 그대로). 새 빌드에 옛 운영자 로그인 경로·HTTP 8080 없음, API 는 8443 만. 브라우저로 로그인·화면을 한 번 봐 달라.

## 3. `SUBJECT_BLOCKED` 구별(§4) — 사실 확인만

- 맞다. 401 `SUBJECT_BLOCKED` 는 정지·퇴사뿐 아니라 끝난 세션(로그아웃·모든 기기 로그아웃·refresh 재사용)의 토큰에도 나온다. 게이트웨이는 사유를 응답에 싣지 않는다.
- 지금은 바꾸지 않는다(요청 없음). 구별이 필요해지면 그때 정한다 — 예: 갱신 응답에서 정지·퇴사는 403 `ACCOUNT_NOT_AVAILABLE`, 끝난 세션은 401 `TOKEN_INVALID`/`TOKEN_REUSED`.

## 4. handoff 폴더 제안(§3) — 받음, 옮김(사용자 2026-11-13 "2번부터 먼저하고")

- 백엔드 `docs/handoff/clients/` 도 앱 영역 폴더로 나눴다: `customer-app/`·`mfc-app/`·`rider-app/`·`backoffice/`·`hr-backoffice/`·`web-common/`·`all/`·`archive/`. 규칙 `docs/handoff/clients/README.md`.
- 새 파일 이름: `<앱 영역>/YYYY-MM-DD-<종류>-<주제>.md`(종류 request·reply·notice), 첫머리 `보낸 영역:` 그대로. 옛 파일은 이름 그대로 폴더만 옮겼다.
- 옮긴 것(바로 아래에 있던 44개):
  - `customer-app/` 6: cart-and-guest-token, delivery-and-order-draft, profile-nickname, customer-app-team-distribution, reference-app-questions, customer-chat-assistant
  - `mfc-app/` 4: handover-check, pda-response-plan, mfc-app-login-and-https-build, mfc-app-employee-login
  - `rider-app/` 2: br06-offer-polling, rider-google-login
  - `backoffice/` 6: temp-backoffice-api, ai-assistant, assignment-plan-094, plan-input, worker-speed-and-plan-estimate, backoffice-role-menus
  - `hr-backoffice/` 1: webmail-separate · `web-common/` 1: silent-login
  - `all/` 8: reply-high, mfc-inbound-screens·mfc-picking-packing-screens·shelf-life-screens(백오피스+PDA 둘 다), customer-inquiry-page(고객 앱+백오피스), area-plan-rag-backoffice, 이 회신, dev-on-other-pcs
  - `archive/` 16(받음·끝남): 11-11~11-12 Keycloak·employee-auth 로그인 요청과 회신, sso-logout, 11-13 area-split·area-pcs-decided·handoff-archive·https-builds-published·operator-login-removed·backoffice-https-csp-logout·hr-backoffice 4개·dispatch-manual-only
- 백엔드 저장소 안 링크는 같은 커밋에서 고쳤다. 클라이언트 문서의 백엔드 경로는 클라이언트가 고친다. 잘못 옮긴 것이 있으면 알려 달라.
- 클라이언트 쪽 옮기기(`pmart-clients/docs/handoff/<앱 영역>/`)가 커밋되면 백엔드 문서의 클라이언트 경로도 고친다.

## 5. 함께 알림 — 이미 보낸 요청

- `../web-common/2026-11-13-web-common-request-silent-login.md` — 백오피스를 열 때 조용히 로그인 확인(employee-auth-service 0.2.0 `silent`). web/core 에 부탁.
