# 백오피스 로그인을 employee-auth-service 로 — ADR-0056(Keycloak 은 신원만, 직원 토큰은 PMart 가 발급)

- 작성: pmart-backend, 2026-11-12
- 대상: pmart-clients 임시 백오피스(`web/backoffice`)
- 대체: `2026-11-11-clients-request-backoffice-keycloak-login.md`(백오피스가 Keycloak 공개 클라이언트로 직접 로그인). **그 방식은 곧 끊긴다** — 아래 §6.
- 근거: ADR-0056(2026-11-12 승인), 설계 `docs/architecture/auth/employee-auth-structure.md`, 계약 `contracts/openapi/employee-auth-service.yaml`.
- 백엔드 상태: employee-auth-service 가 LAN PC 5 에서 돌고, 게이트웨이·서비스 37개 모두 새 직원 토큰을 받는다(2026-11-12 확인). 운영자 13명(기존 Keycloak 계정·역할·센터)이 그대로 옮겨져 있다. **업무 API·계약은 바뀌지 않는다** — 토큰 모양이 예전 운영자 토큰과 같다(`typ=OPERATOR`, `sub=OP-…`, `roles`, `centers`).
- mfc_app(PDA) 로그인은 같은 방식으로 따로 요청한다(딥링크 `pmart-mfc://login`) — 이 문서는 백오피스만.

## 1. 바뀌는 점 한눈에

| | 지금(11-11) | 바꾼 뒤 |
|---|---|---|
| 백오피스가 아는 곳 | Keycloak(`http://192.168.0.101:8180`, 클라이언트 `pmart-backoffice`) | **게이트웨이만**(`http://192.168.0.40:8080`) |
| 로그인 화면 | Keycloak(같음) | Keycloak(같음 — employee-auth 가 보내 줌) |
| API 에 쓰는 토큰 | Keycloak access token | **PMart 직원 토큰**(employee-auth 발급, 15분) |
| 갱신 | Keycloak refresh | `POST /api/v1/employee-auth/tokens/refresh` |
| OIDC 라이브러리 | 필요 | **필요 없음**(일반 fetch 와 리디렉션만) |

백오피스는 OAuth 클라이언트가 아니다. client_id·client_secret·Keycloak 주소를 갖지 않는다.

## 2. 로그인 흐름

```
1) 백오피스: code_verifier 만들기(43~128자, [A-Za-z0-9-._~]) → sessionStorage 에 보관
             code_challenge = BASE64URL(SHA-256(code_verifier))   (Web Crypto: crypto.subtle.digest)
             deviceId = 이 브라우저용 무작위 값(localStorage 에 한 번 만들어 계속 사용)
2) 브라우저 이동(같은 탭, window.location):
   GET http://192.168.0.40:8080/api/v1/employee-auth/logins
       ?app=backoffice&deviceId=<deviceId>&codeChallenge=<challenge>
       [&returnUri=<허용 목록 중 하나 — 생략하면 첫 번째>]
   → 302 Keycloak 로그인 화면 → 아이디·비밀번호 → 302 employee-auth 콜백 → 302 복귀 주소
3) 복귀 주소로 돌아옴:  <returnUri>?ticket=<티켓>      (성공)
                     <returnUri>?error=<코드>        (실패 — §5)
4) 백오피스: POST http://192.168.0.40:8080/api/v1/employee-auth/tokens
      헤더 Idempotency-Key: <uuid>, X-Source-System: backoffice, X-Device-Id: <deviceId>
      본문 {"ticket": "<티켓>", "codeVerifier": "<1)의 verifier>"}
   → 200 {operatorId, roles, centers, tokens:{accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, sessionId}}
5) 주소창에서 ticket 을 지운다(history.replaceState). verifier 는 sessionStorage 에서 지운다.
```

- 티켓은 **60초·1회용**이고, **로그인을 시작한 deviceId + code_verifier** 로만 토큰으로 바뀐다. 틀리게 한 번 쓰면 그 티켓은 버려진다(다시 로그인).
- 로그인할 때마다 Keycloak 이 비밀번호를 다시 묻는다(공용 기기 대비 `prompt=login`). Keycloak 세션은 로그인 직후 끝난다 — "Keycloak 에 이미 로그인됨" 상태가 없다.
- roles 가 비어 있으면 권한 승인 대기다(업무 API 403). 화면에 안내.

## 3. 값 (LAN)

| 항목 | 값 |
|---|---|
| 로그인 시작 | `GET http://192.168.0.40:8080/api/v1/employee-auth/logins?app=backoffice&…` |
| 복귀 주소(허용 목록) | `http://192.168.0.40/auth/callback`, `http://d603-16/auth/callback`, `http://localhost:5173/auth/callback` — 이 밖은 400. **다른 경로가 필요하면 알려 달라**(백엔드 설정 `pmart.employee-auth.apps.backoffice.return-uris`) |
| 티켓 교환 | `POST /api/v1/employee-auth/tokens` |
| 갱신 | `POST /api/v1/employee-auth/tokens/refresh` 본문 `{"refreshToken"}`, 헤더 `Idempotency-Key`·`X-Source-System`·`X-Device-Id`(로그인한 deviceId) → `TokenPair` |
| 로그아웃 | `POST /api/v1/employee-auth/logouts`(Bearer 직원 토큰, 헤더 `Idempotency-Key`·`X-Source-System`, 본문 `{"allDevices": false}`) → 204. 그 뒤 토큰 삭제 |
| 현재 세션 | `GET /api/v1/employee-auth/sessions/current`(Bearer) → `{sessionId, operatorId, app, issuedAt, expiresAt}` |
| access token 수명 | 15분 — 만료 전에 갱신 |
| 세션 길이 | 마지막 갱신에서 12시간(백오피스), 시작에서 최대 12시간 — 지나면 다시 로그인 |
| refresh token | **한 번만** 쓴다. 갱신 응답의 새 값으로 바꿔 둔다. 이미 쓴 값을 다시 보내면 세션이 끝난다(`TOKEN_REUSED`) — 여러 탭이 동시에 갱신하지 않게 한 곳에서만 |

CORS: 게이트웨이가 백오피스 출처를 허용한다(지금과 같음). 로그인 시작·콜백은 fetch 가 아니라 **브라우저 이동**이어야 한다(302 를 따라 Keycloak 화면으로 가야 함).

## 4. 계정 화면

- 운영자 계정 화면은 11-11 요청대로 계속 내린 상태로 둔다. 직원 등록은 ADR-0056 5단계(hr-service, 인사팀 등록 화면)에서 새로 요청한다.
- 역할·센터는 이제 Keycloak 이 아니라 employee-auth 가 정본이다. Keycloak 관리 화면에서 역할을 바꿔도 직원 토큰에는 반영되지 않는다.

## 5. 오류

| 어디서 | 코드 | 뜻 / 화면 |
|---|---|---|
| 복귀 주소 `?error=` | `IDP_ERROR` | Keycloak 에서 취소·오류 — 다시 로그인 |
| | `IDP_REJECTED` | Keycloak 결과 확인 실패 — 다시 로그인, 반복되면 백엔드에 알림 |
| | `IDP_UNAVAILABLE` | Keycloak 응답 없음 — 잠시 뒤 다시 |
| | `EMPLOYEE_NOT_REGISTERED` | Keycloak 계정은 있으나 PMart 직원이 아님 — 관리자 문의 |
| | `ACCOUNT_NOT_AVAILABLE` | 정지·퇴사 — 관리자 문의 |
| 로그인 시작 400 | `APP_NOT_ALLOWED`·`RETURN_URI_NOT_ALLOWED`·`CHALLENGE_INVALID` | 설정·코드 문제 |
| 콜백 400 | `LOGIN_STATE_INVALID` | 로그인 시작 후 10분 넘김·같은 콜백 두 번 — 다시 로그인 |
| 티켓 교환 401 | `TICKET_INVALID` | 60초 넘김·이미 씀·다른 deviceId·verifier 다름 — 다시 로그인 |
| 갱신 401 | `TOKEN_INVALID`·`TOKEN_REUSED` | 다시 로그인 |

비밀번호를 틀리면 Keycloak 화면이 그대로 다시 묻는다(5회 틀리면 10분 잠금 — Keycloak).

## 6. 일정 — 왜 먼저 해 달라는가

- 다음 백엔드 단계(ADR-0056 4단계)에서 Keycloak 공개 주소가 게이트웨이 HTTPS `/iam`(`https://192.168.0.40:8443/iam`)로 바뀐다. 그러면 **지금 배포된 백오피스(Keycloak `http://192.168.0.101:8180` 직접 로그인)는 로그인이 안 된다**(발급자 주소가 달라져 OIDC 라이브러리가 거절).
- 이 문서대로 바꾼 백오피스는 Keycloak 주소를 모르므로 4단계 영향이 없다. **백엔드는 이 전환의 배포 회신을 받은 뒤 4단계를 한다.**
- HTTPS(`https://192.168.0.40:8443`)는 4단계에서 게이트웨이에 **추가**된다(8080 HTTP 는 클라이언트 전환 확인까지 함께). 주소를 설정값으로 두면 그때 바꾸기만 하면 된다. 브라우저는 LAN 사설 루트 CA(`pmart-backend/deploy/lan/tls/pmart-lan-root-ca.crt`)를 신뢰해야 한다 — PC 2~5 Windows 에는 설치돼 있다.

## 7. 확인해 줄 것

1. LAN 백오피스(`http://192.168.0.40`)를 이 방식으로 배포하면 이 폴더(또는 collab)에 회신해 달라.
2. 복귀 경로가 `/auth/callback` 이 아니면 쓰려는 경로를 알려 달라 — 백엔드 허용 목록에 넣는다.
3. 백엔드 시험 계정: `claude-*`(역할별), 비밀번호는 Keycloak 계정 그대로.
