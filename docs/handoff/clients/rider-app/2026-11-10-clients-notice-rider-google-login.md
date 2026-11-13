# 라이더 앱 Google 로그인 연동(rider-auth-service) + 고객 앱 정리 — ADR-0054·0055

- 작성: pmart-backend, 2026-11-10
- 대상: pmart-clients 라이더 앱(`apps/rider_app`, 패키지 `com.pmart.rider_app`)·고객 앱(`apps/customer_app`)
- 계약 정본: `pmart-backend/contracts/openapi/rider-auth-service.yaml`(새), `auth-service.yaml`(고객, 그대로)
- 결정: ADR-0055 — Google 은 신원 인증·ID 토큰 발급만, PMart 인증 서비스가 ID 토큰을 검증하고 자체 JWT 를 발급한다. 웹 클라이언트 ID 는 토큰 대상(`aud`) 지정용. **client secret·인가 코드(serverAuthCode)는 쓰지 않는다.**
- LAN 배치: rider-auth-service 는 아직 LAN 에 없다(전체 재배포 때 함께 — 사용자 결정). 배치되면 이 파일에 날짜를 적는다.

## 1. 라이더 앱 — 새로 붙일 것

### Google 설정
| 항목 | 값 |
|---|---|
| Google Cloud 프로젝트 | `pmart-123`(고객 앱과 같은 프로젝트, 클라이언트는 따로) |
| `google_sign_in` `serverClientId` | **라이더용 웹 클라이언트 ID** `293585219605-la3cj13q19oo51p7e4d0rf4ickh3flgu.apps.googleusercontent.com` |
| Android 클라이언트 | 패키지 `com.pmart.rider_app` + 서명 SHA-1 로 Google Cloud 에 등록(없으면 `DEVELOPER_ERROR`/10). ID 문자열은 앱 코드에 넣지 않는다 |

- **고객용 웹 클라이언트 ID(`…-p6akml03…`)를 라이더 앱에 쓰면 안 된다** — 그 토큰은 rider-auth-service 가 `PROVIDER_REJECTED`(401)로 거절한다. 반대로 라이더 앱 토큰은 고객 로그인에 쓰이지 않는다.
- client secret 은 앱에 넣지 않는다(쓰지 않는다).

### 흐름
```
① 앱: Google 로그인(serverClientId = 라이더 웹 ID) → idToken
② 앱 → 게이트웨이 POST /api/v1/rider-auth/social-logins  {provider: "GOOGLE", providerToken: <idToken>}
③ rider-auth-service: Google 공개키로 서명·iss·aud·만료 검증 → Google 계정을 라이더 주체 RD-… 에 연결(처음이면 생성) → 라이더 토큰(typ=RIDER)
④ 앱: GET /api/v1/riders/me (라이더 토큰)
     404 → 가입 화면: 전화번호·배달 지역·배달 수단·약관 → POST /api/v1/riders/signups → 즉시 운행 승인
     200 → 메인(운행) 화면
⑤ 이후 API 는 accessToken, 만료 전 POST /api/v1/rider-auth/tokens 로 갱신
```
- ④ 의 가입·`/riders/me` 는 rider-service 가 아직 구현 전이다(백엔드 요청 RS-2). 그 전까지 앱은 ③ 까지 붙이고, ④ 는 응답 `newAccount` 로 임시 분기해도 된다(true = 처음 로그인).

### API (모두 게이트웨이 `/api/v1` 아래)
| 할 일 | 요청 | 응답 |
|---|---|---|
| 로그인 | `POST /rider-auth/social-logins` 헤더 `Idempotency-Key`(시도마다 새 UUID), `X-Source-System: rider-app`, `X-Device-Id`(설치마다 한 번 만든 UUID) / 본문 `{provider: "GOOGLE", providerToken: idToken}` | `200 {authRequestId, state: "AUTH_MEMBER", riderId, newAccount, tokens{accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, sessionId}, occurredAt}` |
| 응답을 못 받았을 때 | 같은 `Idempotency-Key`·같은 기기로 다시 → 같은 riderId, 새 토큰. 또는 `GET /rider-auth/social-logins/{authRequestId}` + `X-Device-Id`(토큰 없음) | |
| 갱신 | `POST /rider-auth/tokens` 헤더 `Idempotency-Key`·`X-Source-System`·`X-Device-Id`(로그인한 기기) / `{refreshToken}` | 새 `TokenPair`. **refresh token 은 한 번만** — 받은 즉시 저장하고 이전 것은 버린다. 동시에 두 번 갱신하지 않는다(두 번째가 `TOKEN_REUSED` 로 세션을 끊음) |
| 로그아웃 | `POST /rider-auth/logouts` (Bearer) `{allDevices?: bool}` | 204. 운행 상태는 바꾸지 않는다(운행 종료는 rider-service) |
| 현재 세션 | `GET /rider-auth/sessions/current` (Bearer) | `{sessionId, riderId, issuedAt, expiresAt}` |

- 수명: access 30분, refresh 30일. 30일 동안 갱신이 없으면 다시 로그인.
- 토큰 `sub` = `riderId`(`RD-…`) = rider-service·배차의 라이더 ID.

### 오류
| 상태·코드 | 뜻 | 앱 동작 |
|---|---|---|
| 401 `PROVIDER_REJECTED` | Google 토큰 검증 실패(잘못된 serverClientId 포함) | "다시 로그인" — 반복되면 설정 확인 |
| 423 `AUTH_RESTRICTED` | 이 기기 15분 내 5회 실패 → 10분 제한. `retryAfterSeconds`·`Retry-After` | 남은 시간 안내 |
| 403 `ACCOUNT_NOT_AVAILABLE` | 이 라이더 주체로 로그인 불가 | 고객센터 안내 |
| 503 `PROVIDER_UNAVAILABLE` | Google 확인 불가(실패로 세지 않음) | 잠시 뒤 재시도 |
| 401 `TOKEN_INVALID`·`TOKEN_REUSED` (갱신) | 세션 끝남 | 로그인 화면 |
| 422 `CONFIG_REQUIRED` | 서버 설정 누락 | 백엔드에 알림 |

## 2. 고객 앱 — 바꿀 것 없음 + 정리 하나
- 지금 방식 그대로: `serverClientId` = 고객용 웹 클라이언트 ID `…-p6akml03…`, ID 토큰을 `POST /auth/social-logins` 로 보낸다(2026-10-30 안내 `../archive/2026-10-30-clients-notice-customer-google-login.md`).
- **백엔드 허용 aud 를 고객 웹 클라이언트 ID 하나로 줄였다**(2026-11-10, LAN 재배포 때 반영). `installed` 유형 ID(`…-29edshi9…`)를 aud 로 가진 토큰은 serverClientId 구조상 나오지 않으므로 앱 동작은 같다. 2026-10-30 안내의 "함께 받은 installed 유형 클라이언트도 백엔드가 허용한다" 문장은 더 이상 맞지 않다.
- **ADR-0025(서버 인가 코드) 기각**: `google_token_source.dart` 의 `authorizeServer`/`serverAuthCode` 시험 코드(기본 꺼짐)는 쓰지 않는다 — 지워도 된다. `credentialType` 같은 새 필드는 생기지 않는다.
- iOS 는 만들지 않는다(2026-10-31 결정) — 2026-10-30 안내의 iOS 절은 무시.
