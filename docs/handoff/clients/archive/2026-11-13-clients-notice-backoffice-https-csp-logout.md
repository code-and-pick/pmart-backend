# 알림: 백오피스 HTTPS·CSP·로그아웃 즉시 차단 (2026-11-13 LAN 반영)

- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients `web/backoffice`·`web/hr-backoffice`(그리고 앱 전체 — §3)
- 근거: ADR-0057(LAN 사설 CA, T3 — HTTP 는 옮길 때까지 함께), 사용자 요청 2026-11-13(로그아웃 즉시 차단·CSP·백오피스 HTTPS 먼저).

## 1. 백오피스 HTTPS — 주소가 늘었다(HTTP 는 그대로)

| 앱 | HTTP(지금) | **HTTPS(새로)** |
|---|---|---|
| 백오피스 | `http://192.168.0.40` | `https://192.168.0.40`(443) |
| 인사팀 백오피스 | `http://192.168.0.40:8081` | `https://192.168.0.40:8444` |

- 인증서 = LAN 사설 CA(PC 4 인증서, IP·`d603-16` 둘 다). 브라우저 PC 에 루트 CA 가 있어야 경고가 없다.
- 이미 허용: employee-auth-service 복귀 주소 `https://…/auth/callback`(두 앱, IP·d603-16), Keycloak 로그아웃 뒤 `https://…/login`, 게이트웨이 CORS `https://192.168.0.40`·`https://192.168.0.40:8444`(·d603-16). 백엔드가 두 HTTPS 주소로 로그인·SSO·로그아웃을 확인했다.
- **할 일(클라이언트)**: HTTPS 화면에서 쓰려면 `lan` 빌드의 API 주소를 **`https://192.168.0.40:8443/api/v1`** 로 바꿔야 한다. 지금 빌드의 `http://192.168.0.40:8080/api/v1` 은 HTTPS 화면에서 **혼합 콘텐츠로 브라우저가 막는다**(로그인 버튼 이동은 되지만 티켓 교환·API 호출이 실패). HTTPS 로 옮긴 빌드를 주면 백엔드가 `publish-*` 로 올리고, 그 뒤 HTTP 80·8081 을 닫을지 정한다(T3).
- 빌드는 이제 백엔드가 올리는 쪽으로 부탁한다(`publish-backoffice`·`publish-hr-backoffice` — 직접 복사하면 다음 PC 4 배포 때 덮인다, 11-13 겪음).

## 2. CSP·보안 헤더 — 두 앱 HTML 응답

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';
  img-src 'self' data: blob: http(s)://<게이트웨이 8080·8443>; font-src 'self' data:;
  connect-src 'self' http(s)://<게이트웨이 8080·8443>; worker-src 'self'; object-src 'none';
  base-uri 'self'; form-action 'self'; frame-ancestors 'none'
X-Content-Type-Options: nosniff · X-Frame-Options: DENY · Referrer-Policy: strict-origin-when-cross-origin
```

- 지금 두 빌드(`BZeoIDz5`·`BFG-hkCv`)는 이 정책에서 그대로 돈다(빌드에 인라인 스크립트·`eval`·동적 `<style>`·외부 글꼴 없음 확인, 헤드리스 Chrome 로그인 화면 CSP 위반 0).
- **앞으로 지킬 것**: 인라인 `<script>`·`eval`/`new Function`·인라인 `style="…"` 속성(HTML)·외부 CDN(글꼴·스크립트)·`dangerouslySetInnerHTML` 을 쓰지 않는다. React 의 `style={{…}}`(DOM 속성 설정)는 괜찮다. 외부 출처가 꼭 필요하면 백엔드에 요청(nginx 설정 `deploy/lan/backoffice/nginx.conf`).
- 목 모드 MSW(`mockServiceWorker.js`)는 `worker-src 'self'` 로 허용되지만 LAN 빌드는 쓰지 않는다.

## 3. 로그아웃 즉시 차단 — 모든 앱

- 로그아웃하면 **그 세션의 access token 이 바로(1초 안팎) 401 `SUBJECT_BLOCKED`** 가 된다(전에는 남은 수명 최대 15분 동안 쓸 수 있었다). 같은 사람의 다른 기기·다른 앱 세션은 그대로.
- 모든 기기 로그아웃(`allDevices: true`)은 그 사람의 모든 토큰. refresh token 재사용(`TOKEN_REUSED`)으로 끊긴 세션도 같다.
- 직원(employee-auth-service)·고객·비회원(auth-service) 모두. 화면 처리는 지금과 같다 — 401 이면 토큰을 지우고 로그인 화면으로.
