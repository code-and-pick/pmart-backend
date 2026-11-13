# ADR-0055 Google 소셜 로그인은 ID 토큰 검증만 — 고객·라이더 각자 웹 클라이언트 ID(aud), 백엔드 OAuth2 Client 없음

- 상태: 승인 (2026-11-10, 사용자 "PMart Google 소셜로그인 최종 구현 방향")
- 대체: ADR-0025 결정 1~9(전체 — 서버 인가 코드 하이브리드, 구현하지 않음, 기각).
- 확인: 고객 Google 로그인 ADR(0019)의 결정 1·8 은 대체가 아니라 이 ADR 로 다시 확정한다(허용 aud 는 웹 클라이언트 ID 하나로 좁힘). RAG 색인기가 대체로 읽지 않도록 대체 줄에 적지 않는다.
- 관련: ADR-0019(고객 Google 로그인), ADR-0054(인증 셋으로 — 결정 3·E·F), ADR-0011(PMart JWKS), `contracts/openapi/auth-service.yaml`·`rider-auth-service.yaml`

## 배경
- ADR-0025 는 auth-service 를 OAuth 클라이언트(웹 클라이언트 secret 으로 인가 코드 교환)로 만들려 했다. 실기기에서 `serverAuthCode` 가 나오는 것까지만 확인했고 서버 쪽은 만들지 않았다.
- 사용자가 최종 방향을 정했다: Google 은 사용자 신원 인증과 ID 토큰 발급만 맡고, PMart 는 그 ID 토큰을 검증한 뒤 자체 JWT 를 발급해 접근을 관리한다. 웹 클라이언트 ID 는 토큰 대상(`aud`) 식별자로만 쓴다.
- 고객 aud 허용 목록에 Google 이 `installed` 유형으로 내려 준 두 번째 클라이언트 ID(`…-29edshi9…`)가 들어 있었다. 고객 앱의 `serverClientId` 는 웹 클라이언트 ID 라 ID 토큰 `aud` 는 늘 웹 ID 이고, 이 값을 aud 로 가진 토큰은 나오지 않는다.

## 결정
1. **Google 클라이언트 구성**

   | 구분 | 고객 앱 | 라이더 앱 |
   |---|---|---|
   | Android 클라이언트(패키지 + 서명 SHA-1) | 고객 전용 | 라이더 전용(`com.pmart.rider_app`) |
   | 웹 애플리케이션 클라이언트 | 고객 전용 `293585219605-p6akml03…` | 라이더 전용 `293585219605-la3cj13q…` |
   | Flutter `google_sign_in` `serverClientId` | 고객 웹 ID | 라이더 웹 ID |
   | 검증·발급 서비스 | auth-service | rider-auth-service |

   - **Android 클라이언트 ID**: Google 이 패키지 이름과 서명 SHA-1 로 앱을 알아보는 데만 쓴다. 앱 코드·백엔드 설정에 ID 문자열을 넣지 않는다.
   - **웹 클라이언트 ID**: `serverClientId` 로 넣으면 Google 이 ID 토큰 `aud` 를 이 값으로 정한다. 백엔드는 이 값 **하나만** aud 로 허용한다(고객·라이더 각자). 웹 애플리케이션을 만들거나 Spring OAuth2 Client 를 쓰는 것이 아니다.
2. **흐름**: 앱 Google 로그인 → Google OIDC ID 토큰(JWT) → 앱이 PMart 인증 서비스로 전달 → 인증 서비스가 Google JWKS 로 서명·`iss`·`aud`·`exp` 검증 → Google `sub` 로 PMart 주체 식별(고객 `CU-…`, 라이더 `RD-…`) → PMart 자체 JWT 발급(고객 `typ=CUSTOMER` auth-service, 라이더 `typ=RIDER` rider-auth-service) → 게이트웨이·각 서비스가 PMart JWKS(발급자 표, ADR-0054)로 검증.
3. **쓰지 않는 것**: Spring OAuth2 Client 의존성, Google access token 으로 사용자 정보 조회, Google 인가 코드 교환, Google 웹 클라이언트 `client_secret`(백엔드·저장소·설정·비밀 폴더 어디에도 두지 않는다), Spring Authorization Server.
4. **OAuth2 Resource Server**: 지금 쓰는 `spring-boot-starter-oauth2-resource-server`(Nimbus JWT 검증·`JwtIssuerAuthenticationManagerResolver`)는 **유지**한다. 꼭 필요하지는 않지만 빼면 같은 검증(서명·iss·aud·만료·발급자 표)을 직접 다시 만들어야 하고, 이미 게이트웨이·common-security·시험이 이것 위에 있다. JWT 검증 자체는 그대로다.
5. **업무 권한**은 Google 클라이언트 ID 가 아니라 PMart 계정·발급자·사용자 종류(`typ`)·역할로 나눈다. 고객 토큰으로 라이더 API 를 부르면 서비스가 막고(`TYPE_RIDER` 아님), auth-service 가 `typ=RIDER` 를 내도 게이트웨이 발급자 짝 검증이 막는다(ADR-0054).
6. **PMart 자체 JWT 의 `aud`** (2026-11-10 추가, 사용자: "성능 저하가 아니라면 넣는 게 낫다"): auth-service·rider-auth-service 가 발급하는 access token 에 `aud: ["pmart-api"]` 를 넣고(`pmart.auth.audience`·`pmart.rider-auth.audience`, 기본 `pmart-api`), 발급자 표의 `auth-service`·`rider` 줄에 `audiences: [pmart-api]` 를 둔다(Keycloak `staff` 줄과 같은 값). Google ID 토큰의 `aud`(웹 클라이언트 ID)는 옮기지 않는다 — 그 값은 인증 서비스가 Google 토큰을 받을 때만 본다.
   - 지금은 `typ`·발급자 짝으로 충분하다(API 마다 사용자 종류가 다름). `aud` 는 같은 종류의 사람에게 용도가 다른 토큰(예: 외부 파트너 API)을 줄 때 필요한데, 나중에 넣으면 "발급 먼저 → 검사 나중" 두 번 배포가 필요하므로 전체 재배포 전에 함께 넣는다. 비용은 토큰마다 목록 비교 한 번.
   - 재배포 직후 `aud` 없이 발급된 기존 access token 은 거절된다(최대 30분·비회원 1시간) — 앱은 갱신(`/auth/tokens`)으로 새 토큰을 받는다. LAN 시험 토큰(고객·라이더 `RD-claude-*`)을 직접 만들 때도 `aud: pmart-api` 를 넣는다.

## 결과
- auth-service: 코드 변경 없음(이미 ID 토큰 검증). LAN 허용 aud 를 고객 웹 ID 하나로 줄였다(pmart-config `auth-service-lan.yml`). 계약 `providerToken` 설명에서 "인가 코드" 를 뺐다. ADR-0025 의 `credentialType`·`accepted-credentials`·client secret 환경변수는 만들지 않는다.
- rider-auth-service(새, ADR-0054 E): 같은 방식으로 구현 — `pmart.rider-auth.google.client-ids` = 라이더 웹 ID, 자체 서명키(auth-service 와 다른 키, LAN `.secrets/rider-auth-signing-key.pem` → `{cipher}`), 발급자 표 `rider` 줄(`user-types: [RIDER]`).
- 고객 앱: 지금 방식 그대로(`serverClientId` = 고객 웹 ID, ID 토큰 전달). ADR-0025 를 위해 넣었던 `authorizeServer` 시험 코드는 쓰지 않는다. 라이더 앱: `serverClientId` = 라이더 웹 ID 로 같은 방식(클라이언트 안내 `docs/handoff/clients/rider-app/2026-11-10-clients-notice-rider-google-login.md`).
- `nonce` 를 쓰지 않으므로 탈취된 ID 토큰을 만료(약 1시간) 전에 다시 보내는 공격은 기기별 실패 제한·짧은 수명에 기댄다(ADR-0025 가 막으려던 위험). 필요해지면 `nonce` 를 새 ADR 로 더한다.
- 사용자가 대화에 올린 라이더 웹 클라이언트 JSON 파일에는 `client_secret` 이 들어 있다. 이 저장소·설정·비밀 폴더에 옮기지 않았다. 쓰지 않는 값이므로 Google Cloud 콘솔에서 secret 을 지우거나 재발급해 두면 안전하다(사용자 판단).
