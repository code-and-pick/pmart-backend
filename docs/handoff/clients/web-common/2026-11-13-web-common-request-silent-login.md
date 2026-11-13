# 요청: 백오피스를 열 때 조용히 로그인 확인(silent) — 다른 백오피스에 이미 로그인했으면 버튼 없이

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-13
- 대상: web-common(`web/core` 직원 로그인 코드) — backoffice·hr-backoffice 둘 다 쓰는 곳
- 근거: 사용자 2026-11-13 — 인사팀 백오피스에 로그인한 뒤 새 탭에서 백오피스를 열면 로그인 버튼을 눌러야 함 → "앱을 열 때 조용히 확인" 적용("응 일단 이어서 해주고")
- 계약: `contracts/openapi/employee-auth-service.yaml` **0.2.0** — `GET /employee-auth/logins` 의 `silent`, 콜백 error `LOGIN_REQUIRED`

## 1. 백엔드가 바꾼 것 (employee-auth-service — LAN 배포는 따로 알림)

- `GET /api/v1/employee-auth/logins?app=…&deviceId=…&codeChallenge=…&returnUri=…&silent=true`
  - SSO 앱(backoffice·hr-backoffice)만. Keycloak 에 `prompt=none` 으로 보낸다 — **로그인 화면을 띄우지 않는다.**
  - 같은 브라우저에 Keycloak 세션이 있으면: 평소처럼 복귀 주소로 `?ticket=…` → 지금처럼 `POST /employee-auth/tokens` 로 교환.
  - 없으면: 복귀 주소로 **`?error=LOGIN_REQUIRED`** — 오류 화면이 아니라 **로그인 버튼 화면**을 보이면 된다.
  - PDA(mfc) 등 SSO 가 아닌 앱이 `silent=true` 를 보내면 400 `SILENT_NOT_ALLOWED`(공용 기기는 늘 비밀번호).
- 화면 전체 이동(리디렉션)이다 — 숨은 iframe 이 아니어서 쿠키 제한에 걸리지 않는다. 쿠키·CORS·CSP 변경 없음.
- 실제 Keycloak 으로 시험함(`EmployeeLoginIT`): 세션 있음 → 티켓, 로그아웃 뒤 → `LOGIN_REQUIRED`, mfc → 400.

## 2. 클라이언트에 부탁 (`web/core`)

1. 앱이 열렸는데 PMart 토큰(탭 저장소의 refresh token)이 없으면, 로그인 버튼 화면을 보이기 전에 **한 번** `silent=true` 로 로그인 시작(지금 버튼과 같은 PKCE·deviceId·returnUri).
2. 복귀 주소에서:
   - `ticket` → 지금과 같이 교환하고 원래 가려던 화면으로.
   - `error=LOGIN_REQUIRED` → 로그인 버튼 화면(오류 문구 없이).
   - 그 밖의 error → 지금처럼 오류 안내.
3. **무한 반복 방지**: 이 탭에서 이미 silent 를 한 번 시도했으면(예: sessionStorage 표시) 다시 하지 않는다. 로그아웃 직후·`LOGIN_REQUIRED` 를 받은 뒤에는 버튼만.
4. 화면이 Keycloak 을 한 번 다녀오느라 잠깐 깜빡인다 — 필요하면 "로그인 확인 중" 정도의 빈 화면.
5. 원래 가려던 경로(딥링크)는 지금 로그인 버튼 흐름처럼 보존.

## 3. 참고

- 이것은 "열 때 자동 로그인"만이다. **한쪽 로그아웃을 다른 앱에 전파**하는 일(Keycloak 백채널 로그아웃 + 탭 복귀 때 확인)은 아직 결정 전 — 하게 되면 따로 알린다.
- CSRF(refresh token → HttpOnly 쿠키)와는 별개다(이번 변경은 토큰 보관 방식을 바꾸지 않는다).
- 결과는 클라이언트 handoff 로 회신해 달라(받는 백엔드 영역: platform).
