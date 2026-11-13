# 백오피스 SSO — 로그아웃 응답이 바뀐다(204 → 200 + `idpLogoutUri`)
- **회신(2026-11-13)**: pmart-clients `7852899` `docs/handoff/2026-11-13-backend-backoffice-sso-logout-deployed.md` — 반영·LAN 배포(`index-zFWj8wt_.js`), 실로그인 11항목 통과(SSO 새 탭 비밀번호 없음, 로그아웃 → Keycloak 로그아웃 → `/login`, 다시 로그인 때 비밀번호 물음). 백엔드 확인 끝.

- 작성: pmart-backend, 2026-11-12
- 대상: pmart-clients 백오피스(`web/backoffice`, `lan` 빌드 — employee-auth-service 로그인)
- 근거: 사용자 결정 2026-11-12 "백오피스는 sso적용하는게 나은것같아" — Keycloak 세션 무활동 12시간(지금 Realm 값), 로그아웃 때 Keycloak 세션도 끝낸다. ADR-0056 결정 14 보충, 계약 `contracts/openapi/employee-auth-service.yaml`(`employeeLogout`).
- 앞 문서: `2026-11-12-clients-request-backoffice-employee-auth-login.md`, `2026-11-12-clients-reply-backoffice-employee-auth-questions.md`.

## 1. 무엇이 달라지나

| | 전 | 후 |
|---|---|---|
| 같은 브라우저의 새 탭 로그인 | 매번 Keycloak 비밀번호 | **비밀번호 없이** — "직원 계정으로 로그인" 을 누르면 Keycloak 을 잠깐 거쳐 바로 `/auth/callback?ticket=` 으로 돌아온다(화면이 한 번 깜빡임). 흐름·코드는 그대로 |
| `POST /api/v1/employee-auth/logouts` 응답 | 204(본문 없음) | **200** `{"idpLogoutUri": "..."}` (백오피스 세션이면 늘 있음. 이미 끝난 세션이면 없음) |
| 로그아웃 뒤 | 토큰 지우고 로그인 화면 | 토큰 지우고 **브라우저를 `idpLogoutUri` 로 이동**(`location.assign`) → Keycloak 이 확인 화면 없이 세션을 끝내고 **`<출처>/login`** 으로 돌려보낸다 |

PDA(mfc_app)는 바뀌지 않는다(매번 비밀번호, `idpLogoutUri` 없음).

## 2. 해 달라는 것

1. 로그아웃: 응답을 JSON 으로 읽고, `idpLogoutUri` 가 있으면 토큰·sessionStorage 를 지운 **뒤** 그 주소로 브라우저 이동. 없으면 지금처럼 로그인 화면.
   - 서버 호출이 실패해도(지금처럼) 탭의 토큰은 지운다. 그때는 `idpLogoutUri` 가 없으므로 Keycloak 세션이 남는다 — 화면에 "다른 탭·다음 로그인이 비밀번호 없이 될 수 있습니다. 브라우저를 닫아 주세요" 같은 안내를 권한다.
   - `idpLogoutUri` 에는 토큰 값이 들어 있다 — 로그·오류 문구·분석 도구에 남기지 않는다.
2. `/login` 경로가 로그아웃 뒤 돌아오는 곳이다. 백엔드 허용 목록(Keycloak `post.logout.redirect.uris`): `http://192.168.0.40/login`, `http://d603-16/login`, `http://localhost:5173/login`. 다른 경로가 필요하면 알려 달라.
3. 로그아웃 버튼 문구는 그대로 "로그아웃". 공용 PC 에서는 로그아웃을 눌러야 다음 사람이 비밀번호를 친다 — 안내가 있으면 좋다.

## 3. 알아 둘 것

- 탭마다 PMart 세션(토큰)은 따로다(sessionStorage, 클라이언트 결정 D1 그대로). SSO 는 **Keycloak 로그인만** 이어 준다 — 새 탭은 비밀번호 없이 새 PMart 세션을 받는다.
- 한 탭에서 로그아웃하면 Keycloak 세션이 끝나 **다른 탭의 다음 로그인**부터 비밀번호를 묻는다. 다른 탭의 PMart 세션(이미 받은 토큰)은 그 탭의 세션이 끝날 때까지 남는다 — 모든 탭을 끝내려면 `{"allDevices": true}`.
- 브라우저를 닫지 않고 자리를 뜨면 Keycloak 세션이 최대 12시간(마지막 Keycloak 로그인부터 무활동 12시간) 남는다 — 사용자가 알고 고른 값이다.
- 백엔드 반영: employee-auth-service·Keycloak 클라이언트 설정은 이 문서와 함께 LAN 에 배포한다. 지금 쓰는 백오피스 코드(204 기대)는 200 + 본문도 성공으로 받으면 그대로 동작한다(로그아웃은 되고 Keycloak 세션만 남음) — 확인 부탁.
