# 요청: 직원 refresh token 을 HttpOnly 쿠키로 — Signed Double-Submit CSRF (web/core)

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-13
- 대상: web-common(`web/core` 직원 로그인·갱신 코드) — backoffice·hr-backoffice 둘 다
- 근거: ADR-0059(`docs/adr/0059-web-staff-refresh-cookie-signed-double-submit.md`), 로그인 마무리 기준 3(`docs/operations/pc-split.md` §1-1)
- 계약: `contracts/openapi/employee-auth-service.yaml` **0.3.0** — `gen:api` 다시 해 주세요

## 1. 바뀌는 것 (요약)

지금은 refresh token 을 탭 sessionStorage 에 두고 본문으로 갱신합니다. 앞으로 웹 백오피스는:

- **refresh token 은 JS 가 읽을 수 없는 쿠키**로만 오갑니다(HttpOnly). 화면 코드는 refresh token 을 볼 일이 없습니다.
- access token 은 지금처럼 **메모리 + Bearer**. 일반 API 호출은 바뀌지 않습니다.
- **탭별 세션·silent SSO·로그아웃 흐름은 그대로**입니다.
- PDA(mfc_app)는 바뀌지 않습니다(본문 refresh token 그대로).

백엔드는 구현·시험을 마쳤고, 설정 `refresh-cookie` 는 **꺼 둔 상태**입니다. 이 요청이 반영된 빌드가 오면 LAN 에서 켭니다(켜기 전에는 지금 방식 그대로 동작).

## 2. 서버가 하는 일

- 티켓 교환 `POST /employee-auth/tokens` 응답(쿠키 앱일 때):
  - 본문 `tokens.refreshToken` = **null**, `tokens.refreshTransport` = **`COOKIE`**(PDA 등은 `BODY`). `sessionId` 는 그대로 있습니다.
  - `Set-Cookie` 두 개(세션 쿠키 — 브라우저를 닫으면 사라짐):
    - `__Secure-pmart_ea_rt` — refresh token. HttpOnly·Secure·SameSite=Strict·`Path=/api/v1/employee-auth/tokens/refresh/{sessionId}`
    - `__Secure-pmart_ea_csrf_{sessionId}` — CSRF 값. **JS 가 읽습니다**(HttpOnly 아님)·Secure·SameSite=Strict·`Path=/`. 세션 동안 바뀌지 않습니다.
- 갱신 `POST /employee-auth/tokens/refresh/{sessionId}`(새 경로):
  - 헤더 `X-CSRF-Token` = 위 CSRF 쿠키 값 그대로, `X-Device-Id`, `Idempotency-Key`, `X-Source-System`. 본문 없음.
  - 성공 200: 새 access token(본문 `refreshToken` = null), 새 refresh 쿠키(같은 Path). CSRF 쿠키는 그대로.
  - 403 `CSRF_INVALID`: CSRF 쿠키 없음·헤더 불일치·서명 틀림. **세션은 끊기지 않지만** 이 탭은 그 세션을 못 씁니다 → silent 로그인.
  - 401 `TOKEN_INVALID`·`TOKEN_REUSED`: 세션 끝남 → silent 로그인.
  - 예전 본문 경로 `POST /employee-auth/tokens/refresh` 는 쿠키 앱 세션이면 401 입니다.
- 로그아웃 `POST /employee-auth/logouts`: 지금과 같고, 응답 `Set-Cookie` 로 그 세션의 쿠키 둘을 지웁니다(`Max-Age=0`).

## 3. 클라이언트에 부탁 (`web/core`)

1. **credentials**: 화면(443·8444)과 게이트웨이(8443)는 다른 출처입니다. 다음 세 호출은 `credentials: 'include'` 로 불러 주세요 — 그래야 쿠키가 저장·전송·삭제됩니다. 게이트웨이는 이 세 경로에서만 백오피스 두 출처에 자격 증명 CORS 를 엽니다.
   - `POST /employee-auth/tokens`(티켓 교환), `POST /employee-auth/tokens/refresh/{sessionId}`(갱신), `POST /employee-auth/logouts`(로그아웃)
   - 다른 API 는 지금처럼 credentials 없이(Bearer).
2. **저장**: sessionStorage 에는 `sessionId`·`deviceId` 만. refresh token 은 더 이상 저장하지 않습니다(`refreshTransport` 가 `BODY` 면 지금처럼 — 시험·다른 앱 대비).
3. **갱신**: `document.cookie` 에서 `__Secure-pmart_ea_csrf_{sessionId}` 값을 읽어 `X-CSRF-Token` 헤더로. 지금의 single-flight(같은 탭에서 한 번만)를 그대로 지켜 주세요.
4. **결과를 모르는 갱신(응답 유실·네트워크 오류)은 다시 보내지 마세요.** 연결이 끊기면 새 쿠키도 안 와서, 다시 보내면 옛 쿠키 재사용(TOKEN_REUSED)으로 세션이 끝납니다. 지금처럼 "세션 끝남"으로 보고 silent 로그인.
5. **F5(새로고침)**: access token 이 사라집니다 → sessionStorage 의 `sessionId` 로 **쿠키 갱신을 먼저**, 실패하면 silent 로그인(그래도 안 되면 로그인 버튼).
6. **CSRF 쿠키가 없거나 403 `CSRF_INVALID`**: 이 탭의 세션을 버리고 silent 로그인. CSRF 값을 다시 받는 API 는 없습니다(일부러 만들지 않음).
7. **로그아웃**: 서버 호출이 실패해도 이 탭의 `sessionId` 를 지워 주세요(CSRF 값을 읽을 길이 없어져 남은 쿠키는 쓸 수 없고, 서버 무활동 한도로 끝납니다). `idpLogoutUri` 이동은 지금과 같습니다.
8. **로컬 개발**: 로컬 게이트웨이도 `http://localhost:5173`·`5174` 에 쿠키 CORS 를 엽니다. Chrome 은 localhost 를 안전한 출처로 보므로 `Secure` 쿠키가 http://localhost 에서도 저장됩니다.

## 4. 확인 부탁 (반영 뒤)

- 같은 앱 탭 두 개가 각자 15분 넘게 쓰며 갱신(서로 끊기지 않음), 백오피스(443)·인사팀(8444) 동시 사용, 갱신 중 F5, 탭 닫고 새 탭(silent), 한 탭 로그아웃 뒤 다른 탭은 그대로.
- 개발자 도구에서 refresh 쿠키가 HttpOnly 이고 `document.cookie` 에 보이지 않는지, CSRF 쿠키 이름이 탭(세션)마다 다른지.
- CSP·CORS 오류 0.

결과는 클라이언트 handoff 로 회신해 주세요(받는 백엔드 영역: platform). 빌드가 오면 백엔드가 LAN 에서 `refresh-cookie` 를 켜고 같이 확인합니다.
