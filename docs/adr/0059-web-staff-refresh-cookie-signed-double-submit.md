# ADR-0059 웹 백오피스 직원 refresh token 은 세션별 HttpOnly 쿠키로, CSRF 는 Signed Double-Submit — ADR-0018 결정 6(CSRF 두지 않음)의 웹 직원 부분 대체

- 상태: 승인 (2026-11-13, 사용자 "A로 하자" → "①번으로 진행" → "지도하시는분이 더블서밋 쓰래" → "응 해줘"). 로그인 마무리 기준 3(`docs/operations/pc-split.md` §1-1).
- 관련: ADR-0018 결정 6(Bearer 만, CSRF 없음), ADR-0056(employee-auth-service — 탭별 세션, SSO, silent), ADR-0057(LAN HTTPS — 이 결정의 전제).

## 배경
- 백오피스·인사팀 백오피스(웹)는 직원 access token(15분)과 **refresh token 을 탭의 sessionStorage** 에 두고 Bearer 로 보낸다. 쿠키 인증이 아니라 CSRF 는 없지만, XSS 가 생기면 **수명이 긴 refresh token 을 훔칠 수 있다**(CSP 로 줄였지만 0 은 아니다).
- 백오피스는 **탭마다 세션이 따로**다(새 탭 = silent SSO 로 새 세션). 쿠키는 브라우저 전체가 공유하고 **포트를 구분하지 않는다**(백오피스 443·인사팀 8444·게이트웨이 8443 이 같은 호스트) — 이름 하나짜리 쿠키로 옮기면 탭·앱끼리 덮어쓰고, 같은 refresh token 으로 동시에 갱신해 TOKEN_REUSED 로 둘 다 끊긴다.
- 정식 BFF(브라우저에 토큰 없음 — 게이트웨이 세션)는 게이트웨이 인증 구조를 바꾸는 일이라 택하지 않았다(선택지 B).

## 결정
1. **대상**: 앱 설정 `refresh-cookie: true` 인 웹 앱(backoffice·hr-backoffice). PDA(mfc)·시험 교환은 지금처럼 본문 refresh token(`POST /tokens/refresh`).
2. **refresh 쿠키** `__Secure-pmart_ea_rt` — HttpOnly·Secure·SameSite=Strict·**Path=`/api/v1/employee-auth/tokens/refresh/{sessionId}`**·Max-Age 없음(세션 쿠키). 세션마다 Path 가 달라 탭·앱끼리 섞이지 않는다. 티켓 교환·갱신 응답에서 본문 `refreshToken` 은 비우고 `refreshTransport: COOKIE`.
3. **갱신** `POST /api/v1/employee-auth/tokens/refresh/{sessionId}`(공개 작업, 토큰 없음). 서버는 **소유권**을 본다: 쿠키의 refresh token 이 주소의 세션 것이고, 세션의 `deviceId`(X-Device-Id)·앱(쿠키 앱)이 맞고, 세션이 살아 있어야 한다. 쿠키 Path 는 나누는 장치일 뿐 보안 장치가 아니다. 회전·재사용 감지(TOKEN_REUSED)는 그대로.
4. **CSRF = Signed Double-Submit**(지도 의견). 쿠키 `__Secure-pmart_ea_csrf_{sessionId}` — JS 가 읽는다(HttpOnly 아님)·Secure·SameSite=Strict·**Path=/**(화면이 읽어야 하므로)·Max-Age 없음. 이름에 sessionId 를 넣어 탭·앱끼리 덮어쓰지 않는다. 값 = `무작위.HMAC-SHA256(서버 키, sessionId + "!" + 무작위)` — 세션에 묶인 서명이라 다른 세션 값·심어 넣은 쿠키로는 통과하지 못한다. 갱신 요청은 헤더 `X-CSRF-Token` = 그 쿠키 값이어야 하고 서명·sessionId 가 맞아야 한다. 틀리면 **403 `CSRF_INVALID`, 세션은 끊지 않는다**(남이 일부러 틀린 요청으로 세션을 끊지 못하게). 값은 **세션 동안 고정**(갱신 때 바꾸지 않음 — 갱신 응답 유실·새로고침에도 탭이 계속 쓸 수 있게). 서버 키는 설정 `{cipher}`(local·시험은 임시 키).
5. **탭이 저장하는 것**: sessionStorage 에 sessionId 와 deviceId 만. CSRF 값은 쿠키에서 읽는다. access token 은 메모리.
6. **복구 정책**:
   - F5: access token 이 사라진다 → sessionId 로 쿠키 갱신을 먼저, 실패하면 silent SSO.
   - 갱신 결과를 모름(응답 유실): **다시 보내지 않는다** — 연결이 끊기면 `Set-Cookie` 도 안 와서 옛 쿠키로 다시 보내면 TOKEN_REUSED 다. 세션 끝남으로 보고 silent SSO.
   - CSRF 쿠키 유실: 그 세션은 이 탭에서 못 쓴다 → silent SSO(가능하면 남은 access token 으로 옛 세션 로그아웃). **CSRF 값 다시 받기 API 는 만들지 않는다**.
   - 같은 탭 동시 갱신: 클라이언트 single-flight(`web/core` pmartFetch, 이미 있음) + 서버 refresh 행 잠금.
7. **로그아웃**(Bearer): 서버 세션 폐기와 함께 그 세션의 쿠키 둘을 `Max-Age=0` 으로 지운다. 모든 기기 로그아웃은 서버 세션 전부 폐기, 쿠키는 이 탭 것만 지워진다(다른 탭 쿠키는 서버 세션이 없어 못 쓴다).
8. **게이트웨이**(employee-auth-service 는 게이트웨이 뒤 그대로): 갱신 경로만 이름이 `__Secure-pmart_ea_` 로 시작하는 쿠키를 넘기고 나머지 쿠키·나머지 경로는 지금처럼 지운다. **자격 증명 CORS**(`Allow-Credentials: true`)는 쿠키가 오가는 세 경로(`/tokens`, `/tokens/refresh/*`, `/logouts`)에만, 별도 출처 목록(`pmart.gateway.cors.credentialed-origins` — 백오피스 두 앱)에만. 사전 요청 허용 헤더에 `X-CSRF-Token`.

## 결과
- JS 는 refresh token 을 읽을 수 없다. XSS 가 생겨도 그 탭이 열려 있는 동안의 access token(15분)과 그 탭에서의 갱신 호출까지만 위험하다.
- 탭별 세션·silent SSO·로그아웃 흐름은 그대로. CSRF 쿠키가 세션마다 하나씩 쌓여(Path=/) 이 호스트로 가는 요청에 실린다 — 세션 쿠키라 브라우저를 닫으면 사라지고, 게이트웨이가 갱신 경로 밖에서는 지운다.
- 화면(443·8444)이 게이트웨이(8443)가 심은 쿠키를 읽는다 — 쿠키가 포트를 구분하지 않는 동작에 기댄다. 브라우저 검증 항목(탭별·포트별·preflight)에 넣는다.
- 클라이언트 `web/core` 변경 필요(handoff). 바뀌기 전에도 깨지지 않는다 — 옛 클라이언트는 본문 refresh token 이 비어 있으면 갱신을 못 하고 silent SSO/로그인으로 간다(배포 순서는 클라이언트 준비 뒤 설정 `refresh-cookie` 를 켜는 것으로 맞춘다).
- 대안 기록: 세션 바인딩 CSRF(서버 저장 해시, CSRF 쿠키 없음)를 먼저 제안했으나 지도 의견에 따라 Signed Double-Submit.
