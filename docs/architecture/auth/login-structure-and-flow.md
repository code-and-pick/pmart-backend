# PMart 로그인 구조와 흐름

- 작성: 2026-11-10
- 근거: ADR-0054(인증 셋으로 — 직원 Keycloak·고객 auth-service·라이더 rider-auth-service), ADR-0055(Google 로그인은 ID 토큰 검증만, 결정 6 PMart JWT `aud`), ADR-0019(고객 Google 로그인), ADR-0018(운영자 로그인), ADR-0022(비회원 세션)
- 계약: `contracts/openapi/auth-service.yaml`, `contracts/openapi/rider-auth-service.yaml`
- 2026-11-11 LAN 전체 재배포로 rider-auth-service·`aud` 검사가 동작 중이고, 발급자 표 auth-service 줄의 RIDER 는 뺐다. 상태는 [7. 현재 상태와 남은 일](#7-현재-상태와-남은-일).

## 1. 전체 구조 — 발급자 셋, 게이트웨이 하나

| 사용자 | 로그인 방식 | 토큰 발급 서버 | `typ` | 주체 ID |
|---|---|---|---|---|
| 고객 | Google 소셜 로그인 | auth-service (8501, PC 4) | `CUSTOMER` | `CU-…` |
| 비회원 | 기기 기준 임시 세션 | auth-service | `GUEST` | `GST-…` |
| 라이더 | Google 소셜 로그인 | rider-auth-service (8510, PC 5) | `RIDER` | `RD-…` |
| 직원(운영자) | 아이디·비밀번호(Keycloak 로그인 화면, PKCE) | **Keycloak**(PC 5 :8180, Realm `pmart-staff`) — 2026-11-11 백오피스 전환·계정 옮김. auth-service 운영자 로그인은 확인 전까지 함께 둠 | `OPERATOR`(Keycloak `typ` 는 "Bearer" — 발급자로 정함) | 운영자 ID(`pmart_operator_id`, `OP-…`) |

Keycloak 구조(Realm·클라이언트·역할·계정 관리·차단 중계)는 `keycloak-structure.md`.

> 2026-11-12 결정(ADR-0056, 구현 전): 직원 로그인을 강의 설계대로 바꾼다 — Keycloak 은 신원만, PMart 직원 JWT 는 employee-auth-service(Keycloak 기밀 클라이언트), 계정 발급은 hr-service + 사내 메일(`@pmart.com`). [`employee-auth-structure.md`](employee-auth-structure.md). 구현·전환 전이라 아래 표는 아직 지금(ADR-0054) 구조다.

게이트웨이와 모든 서비스는 **발급자 표**(pmart-config `application-<프로필>.yml` 의 `pmart.security.issuers`) 하나로 토큰을 검증한다.

| 표의 줄 | 확인하는 `iss` | 서명 확인용 공개키 | 확인하는 `aud` | 인정하는 종류 |
|---|---|---|---|---|
| `auth-service` | auth-service | auth-service JWKS | `pmart-api` | CUSTOMER, GUEST, SERVICE (+ 옮기는 동안 OPERATOR) |
| `staff` | Keycloak pmart-staff | Keycloak certs | `pmart-api` | OPERATOR |
| `rider` | rider-auth-service | rider-auth-service JWKS (`/api/v1/internal/jwks`) | `pmart-api` | RIDER |

- 표에 없는 `iss` 는 401.
- 한 발급자는 자기 줄의 종류만 낼 수 있다(발급자↔종류 짝). 예: rider-auth-service 토큰은 RIDER 로만 인정한다.

## 2. Google 클라이언트 구성 (ADR-0055)

| | 고객 앱 | 라이더 앱 |
|---|---|---|
| Android 클라이언트 | 고객 패키지 + 서명 SHA-1 | `com.pmart.rider_app` + 서명 SHA-1 |
| 웹 클라이언트 ID | `293585219605-p6akml03…` | `293585219605-la3cj13q…` |
| 검증·발급 서비스 | auth-service | rider-auth-service |

### Android 클라이언트 ID가 쓰이는 곳
| 위치 | 내용 |
|---|---|
| Google Cloud 콘솔 | 패키지 이름 + 서명 SHA-1 등록만 한다 |
| Google 내부 (로그인 ① 단계) | 로그인을 요청한 앱이 등록된 패키지·서명과 같은지 확인한다. 다르면 `DEVELOPER_ERROR`(10) |
| 앱 코드·백엔드 설정 | **쓰지 않는다.** ID 문자열을 어디에도 넣지 않는다 |

### 웹 클라이언트 ID가 쓰이는 곳
| 위치 | 고객 | 라이더 |
|---|---|---|
| 앱 `google_sign_in` 의 `serverClientId` | `customer_app/lib/features/auth/data/google_token_source.dart` | rider_app (연동 예정) |
| Google 이 발급하는 ID 토큰의 `aud` | 이 값이 된다 | 이 값이 된다 |
| 백엔드가 허용하는 Google 토큰 `aud` | `auth-service-<프로필>.yml`: `pmart.auth.customer.google.client-ids` | `rider-auth-service.yml`: `pmart.rider-auth.google.client-ids` |

- 쓰임새는 "이 Google 토큰이 어느 서버에 보내라고 발급된 것인지" 표시하는 것뿐이다. 웹 앱을 만들거나 웹 로그인을 하는 것이 아니다.
- 앱마다 따로 두므로 고객 앱 토큰으로 라이더 로그인이 안 되고, 반대도 안 된다.
- 한 줄 요약: **Android 클라이언트 ID 는 Google 이 "어느 앱인지" 확인하는 데, 웹 클라이언트 ID 는 "어느 서버용 토큰인지"(`aud`) 정하는 데 쓴다.** 업무 권한은 둘 다와 관계없이 PMart 계정과 토큰 종류(`typ`)·역할로 나눈다.

### 백엔드에서 쓰지 않는 것
Spring OAuth2 Client, Google 인가 코드 교환, Google access token 으로 사용자 정보 조회, 웹 클라이언트 `client_secret`, Spring Authorization Server. OAuth2 Resource Server(PMart JWT 검증)는 유지한다.

## 3. client_secret 과 공개·기밀 클라이언트

`client_secret` 은 "웹에서 로그인할 때" 필요한 값이 아니다. **서버가 Google 토큰 발급 주소(`https://oauth2.googleapis.com/token`)에 직접 인가 코드를 토큰으로 바꿔 달라고 할 때** 자기가 그 클라이언트임을 증명하는 비밀번호다. 웹 서비스가 주로 그렇게 하기 때문에 웹 클라이언트에 붙어 나올 뿐이다.

| | 기밀 클라이언트 | 공개 클라이언트 |
|---|---|---|
| 예 | 서버가 있는 웹 서비스 | 모바일 앱, 브라우저만으로 도는 웹(SPA) |
| 비밀을 숨길 수 있나 | 예. 서버 안에만 둔다 | 아니오. APK 는 디컴파일되고 브라우저 코드는 누구나 본다 |
| Google 이 클라이언트를 확인하는 방법 | `client_id` + `client_secret` | Android: 패키지 + SHA-1. 그 외: PKCE, 등록된 출처(origin) |
| Google 클라이언트 유형 | 웹 애플리케이션 (secret 있음) | Android (secret 없음) |

### secret 이 필요한 경우와 아닌 경우
| 방식 | secret |
|---|---|
| 서버가 인가 코드를 받아 Google 토큰으로 교환 (Spring OAuth2 Client 기본 방식, Google API 를 계속 쓰려고 Google refresh token 을 받을 때) | **필요** |
| 서버가 보관한 Google refresh token 으로 Google access token 갱신 | **필요** |
| 웹: Google 로그인 버튼(Google Identity Services)으로 브라우저가 ID 토큰을 받고, 서버는 검증만 | 필요 없음 |
| 모바일 앱: `google_sign_in` 으로 ID 토큰을 받고, 서버는 검증만 (**PMart 방식**) | 필요 없음 |

- ID 토큰 검증은 Google 공개키(JWKS)로 한다. Google 은 자기 개인키로 서명하므로 비밀값이 필요 없다.
- PMart 는 웹 클라이언트를 **이름표(ID)로만** 쓴다. 모바일 앱은 공개 클라이언트라 Android 클라이언트로 로그인하고, ID 토큰의 `aud` 를 "PMart 서버"로 지정하려고 웹 클라이언트 ID 를 `serverClientId` 로 준다. 같이 발급된 secret 은 쓸 곳이 없다.
- 나중에 웹 고객 로그인을 만들어도 Google 로그인 버튼 방식이면 지금 앱과 같다(브라우저 → ID 토큰 → auth-service 검증 → PMart 토큰). secret 은 여전히 필요 없다.
- secret 이 새면 다른 사람이 그 웹 클라이언트 행세를 하며 그 클라이언트 앞으로 발급된 인가 코드를 Google 토큰으로 바꿀 수 있다. PMart 로그인이 뚫리지는 않지만, 쓰지 않는 값이므로 Google Cloud 콘솔에서 삭제·재발급해 두는 것이 깔끔하다. 저장소·설정·비밀 폴더에는 두지 않는다.

## 4. 로그인 흐름 (고객·라이더 공통)

```
① 앱 ── google_sign_in(serverClientId = 자기 앱의 웹 ID) ──▶ Google
        Google: Android 클라이언트(패키지·SHA-1)로 앱 확인
② Google ──▶ 앱: Google ID 토큰
        (iss=accounts.google.com, aud=웹 ID, sub=Google 계정, exp ≈ 1시간)
③ 앱 ──▶ 게이트웨이 ──▶ 인증 서비스
        고객:   POST /api/v1/auth/social-logins
        라이더: POST /api/v1/rider-auth/social-logins
        헤더: Idempotency-Key, X-Source-System, X-Device-Id
        본문: {provider: "GOOGLE", providerToken: <ID 토큰>}
④ 인증 서비스: Google 공개키로 서명 확인, iss·aud·exp 확인
        aud 가 자기 앱 웹 ID 가 아니면 401 PROVIDER_REJECTED
        기기별 15분 안에 5회 실패하면 10분 423 AUTH_RESTRICTED
        Google 장애는 503 PROVIDER_UNAVAILABLE (실패로 세지 않음)
⑤ Google sub → PMart 주체 (처음이면 새로 만듦, 이메일로 합치지 않음)
        고객 CU-… (회원이면 CustomerAuthenticated 이벤트로 프로필을 customer-service 에 전달)
        라이더 RD-… (newAccount=true 면 앱이 가입 화면으로)
⑥ PMart 토큰 발급
        access token: RS256, 30분
          iss=발급 서버, sub=주체, aud=pmart-api, typ, roles, centers, sid, jti
        refresh token: 무작위 값, 30일, 한 번만 사용, DB 에는 SHA-256 만 저장
```

- 고객과 라이더는 서명키가 서로 다르다.
- Google 토큰·이메일·이름은 인증 서비스에 저장하지 않는다. 저장하는 것은 Google `sub` 와 주체 ID 의 연결뿐이다.
- Google ID 토큰은 ③ 에서 한 번 쓰고 끝난다. 이후 API 는 PMart access token 만 쓴다.
- 같은 `Idempotency-Key` 재전송: 성공이었으면 같은 주체로 새 세션 토큰, 실패였으면 같은 실패, 다른 기기면 409.

## 5. 로그인 뒤 API 호출

```
앱 ── Bearer access token ──▶ 게이트웨이
      ① iss 로 발급자 표의 줄 선택 (표에 없으면 401)
      ② 그 발급자 공개키로 서명 확인, 만료 확인
      ③ aud 에 pmart-api 가 있는지
      ④ 발급자와 typ 짝 (예: rider 발급자는 RIDER 만)
      ⑤ 경로별 사용자 종류 허용 목록 (예: 라이더 경로는 RIDER 만)
   ──▶ 각 서비스: 같은 발급자 표로 한 번 더 검증, 그다음 업무 권한(역할·센터) 확인
```

### `aud` 가 두 군데 있다
| 토큰 | `aud` 값 | 누가 확인하나 |
|---|---|---|
| Google ID 토큰 | 앱의 웹 클라이언트 ID | 인증 서비스 (로그인 ④ 단계) |
| PMart access token | `pmart-api` | 게이트웨이·각 서비스 (발급자 표) |

- Google 토큰의 `aud` 를 PMart 토큰으로 옮기지 않는다. 둘은 받는 쪽이 다르다.
- `typ` 은 토큰의 **주인이 누구인지**(고객·라이더·운영자), `aud` 는 **어느 API 용인지**를 가린다. 지금은 API 마다 사용자 종류가 달라 `typ` 으로 충분하지만, 같은 종류의 사람에게 용도가 다른 토큰(예: 외부 파트너 API)을 줄 때는 `aud` 가 필요하다. 나중에 넣으면 "발급 먼저 → 검사 나중" 두 번 배포가 필요하므로 지금 넣었다(ADR-0055 결정 6). 비용은 토큰마다 목록 비교 한 번이다.
- Keycloak 은 한 Realm 의 여러 클라이언트 토큰이 같은 `iss` 로 나오므로 `aud` 검사가 처음부터 있었다.

## 6. 세션 관리

| 할 일 | 고객 (auth-service) | 라이더 (rider-auth-service) |
|---|---|---|
| 갱신 | `POST /auth/tokens` | `POST /rider-auth/tokens` |
| 로그아웃 | `POST /auth/logouts` | `POST /rider-auth/logouts` (`allDevices` 선택) |
| 현재 세션 | `GET /auth/sessions/current` | `GET /rider-auth/sessions/current` |

- 갱신은 로그인한 기기에서만 된다. 쓴 refresh token 이 다시 오면 탈취로 보고 세션을 끊는다(`TOKEN_REUSED`). 앱은 동시에 두 번 갱신하지 않는다.
- 고객·라이더 세션은 마지막 갱신부터 30일 동안 유지된다. 30일 동안 쓰지 않으면 다시 로그인한다.
- 운영자 세션은 12시간 절대 수명. 비회원은 access 1시간, refresh·세션 30일.
- 라이더 로그아웃은 운행 상태를 바꾸지 않는다(운행 종료는 rider-service).

### 라이더 가입 (rider-service, 서비스 요청 RS-2 — 아직 구현 전)
로그인으로 `RD-…` 를 받은 뒤 `GET /riders/me`:
- **404** → 가입 화면: 전화번호·배달 지역·배달 수단·약관 → `POST /riders/signups` → 즉시 운행 승인(ADR-0054 결정 G). 중복 확인은 **전화번호만**, 면허·주민번호는 받지 않는다(사용자 결정 2026-11-10).
- **200** → 운행 화면.

## 7. 현재 상태와 남은 일

2026-11-11: 커밋·pmart-config push·LAN 전체 재배포 완료(이미지 5265276). 확인 — 비회원·운영자 토큰 `aud=pmart-api` 로 API 통과, `aud` 없는 토큰 401, 라이더 로그인 경로가 rider-auth-service 까지 도달(가짜 토큰 `PROVIDER_REJECTED`), rider-auth-service 서명 시험 토큰 통과, auth-service 서명 RIDER 토큰 401.

| 할 일 | 언제·누가 |
|---|---|
| 운영자 로그인 Keycloak 이전 — 계정 옮김·백오피스 배포 완료(2026-11-11). 백오피스 로그인 확인 뒤 auth-service 줄에서 `OPERATOR` 빼기 | 클라이언트 회신 뒤 (`keycloak-structure.md` §13) |
| 라이더 앱 로그인 연동, 실제 Google 토큰으로 시험 | 클라이언트 (`docs/handoff/clients/rider-app/2026-11-10-clients-notice-rider-google-login.md`) |
| rider-service 가입 구현 (RS-2) | rider-service 작업 때 |
| 차단 목록 ⑤ — 운영자(auth-service)·Keycloak 직원은 동작(2026-11-11 LAN). 라이더 정지·고객 정지는 그 기능을 만들 때 | 나중 |
| 웹 클라이언트 `client_secret` 삭제·재발급 | 사용자 (Google Cloud 콘솔) |

### 알아 둘 것
- LAN 시험용으로 직접 만드는 토큰에도 `aud: pmart-api` 를 넣는다. 시험 라이더 토큰은 rider-auth-service 서명키(`.secrets/rider-auth-signing-key.pem`, `iss=http://rider-auth-service:8510`)로 만든다 — auth-service 서명 RIDER 토큰은 이제 401.
