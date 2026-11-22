# rider-auth-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `rider-auth-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.
결정: ADR-0054(결정 3·E·F — 라이더는 Keycloak 이 아니라 이 서비스), ADR-0055(Google 로그인은 ID 토큰 검증만).

## 계약
- REST: `contracts/openapi/rider-auth-service.yaml` → `openApiGenerate` 가 `com.pmart.riderauth.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행 `SubjectBlocked`(platform.subject-block.v1, outbox — `application/SessionBlocks`, 2026-11-13). 로그아웃 = 그 세션(`sessionId`, LOGOUT), 모든 기기 = 주체 전체(LOGOUT_ALL), refresh 재사용 = 그 세션(TOKEN_REUSED), 정지 라이더 = 주체 전체(RIDER_RESTRICTED, 2026-11-15). 게이트웨이가 받아 이미 나간 access token 을 바로 401 `SUBJECT_BLOCKED`. 구독 `riderEvents-in-0` ← delivery.rider.v1 `RiderRestricted`(2026-11-15). `pmart.messaging.domain: platform`(설정 저장소).

## 패키지
`com.pmart.riderauth.{api, application, infrastructure}` — rider-service(`com.pmart.rider`)와 겹치지 않게. Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지(V1 은 2026-11-10 LAN 배치 전).
- 제공자 토큰·이메일 원문·이름은 저장하지 않는다 — 제공자 `sub` 와 내부 라이더 주체 `RD-…` 연결 + 가린 이메일(2026-11-19)만. refresh token 은 SHA-256 만.

## 실행
```bash
./gradlew :services:rider-auth-service:check                      # 단위 + 통합(Testcontainers PostgreSQL) + 계약 검증
./gradlew :services:rider-auth-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8510. LAN PC 5.

## Google 로그인 (ADR-0055)
- 라이더 앱(`google_sign_in`, `serverClientId` = 라이더용 **웹** 클라이언트 ID)이 받은 Google ID 토큰을 `GoogleIdTokenVerifier`(Google JWKS, iss·aud·만료)로 검증한다. client secret·인가 코드 교환·Spring OAuth2 Client 를 쓰지 않는다.
- 허용 aud = `pmart.rider-auth.google.client-ids`(라이더 웹 ID 하나, pmart-config `rider-auth-service.yml`). **고객용 웹 ID·Android 클라이언트 ID 는 넣지 않는다** — 고객 앱 토큰으로 라이더 로그인이 되면 안 된다.
- 같은 Google 계정 → 같은 `RD-…`. 이메일로 합치지 않는다. `newAccount=true` 면 앱이 가입 화면으로 간다(가입은 rider-service `POST /riders/signups` — 가입 여부는 `GET /riders/me`).
- 기기별 15분 5회 `PROVIDER_REJECTED` → 10분 `AUTH_RESTRICTED`(423, `RestrictedAdvice`). Google 장애(503)는 세지 않는다.
- 같은 Idempotency-Key 재전송: 성공이었으면 같은 주체로 새 세션 토큰, 실패였으면 같은 실패, 다른 기기면 409.
- 시험은 `GoogleIdTokenVerifier` 빈을 시험 키 검증기로 바꾼다(`RiderSocialLoginIT`).

## 토큰 (자체 JWT)
- access: RS256, `iss` = `pmart.rider-auth.issuer`(발급자 표 `rider` 줄 issuer-uri 와 같아야 함), `sub` = riderId, `typ=RIDER`, `aud` = `pmart.rider-auth.audience`(기본 `pmart-api`, 발급자 표 rider 줄 `audiences` 와 같게 — ADR-0055 결정 6. Google 토큰의 aud 와 다른 값), `roles`·`centers` 빈 목록, `sid`. 수명 30분.
- refresh: 30일, 한 번만 쓴다(재사용이면 세션 폐기 `TOKEN_REUSED`), 로그인한 기기에서만. 세션은 마지막 갱신 기준 30일 연장. 수치는 `pmart.rider-auth.*`(record 설정 — 바꾸면 재시작).
- 서명키 `pmart.rider-auth.signing.keys`(auth-service 와 **다른 키**). LAN 은 `{cipher}`(원본 `deploy/lan/.secrets/rider-auth-signing-key.pem`, `init-secrets.sh` 가 만듦). local/test 만 `ephemeral=true`.
- `GET /api/v1/internal/jwks`(permit-path, 게이트웨이 비노출) — 게이트웨이·전 서비스가 발급자 표 `rider` 줄로 이 주소를 쓴다. 자기 토큰(로그아웃·현재 세션)도 같은 표로 검증하고, 서비스 안에서 `typ=RIDER`·`iss` 를 한 번 더 본다.

## 상태
2026-11-10 구현(ADR-0054 ④): 로그인·결과 조회·갱신·로그아웃·현재 세션·JWKS. LAN 배치 2026-11-11(PC 5, ADR-0054 결정 J 전체 재배포). 2026-11-13 로그아웃·재사용 즉시 차단(SubjectBlocked — 사용자 "라이더 로그아웃즉시 차단도 해줘").
- **정지 라이더 로그인 끊기(2026-11-15, ADR-0054 ⑤ 라이더 정지 — 사용자 "추천대로": 진행 중 배송이 끝난 뒤 끊기, 신규배차 제한은 끊지 않음)**: `application/RiderSessionCuts`, V2 `rider_session_cut`. `RiderRestricted` SUSPENDED·TERMINATED → PENDING(aggregateVersion = 라이더 행 버전, 낮거나 같으면 버림), 그 밖 → LIFTED. `sweep`(`pmart.rider-auth.session-cut-interval` 1분)이 PENDING 마다 dispatch `GET /internal/riders/{id}/active-deliveries`(`infrastructure/DispatchClient`, `pmart.rider-auth.dispatch-base-url` 기본 `http://delivery-dispatch-service:8404`, 트랜잭션 밖)를 보고 비었으면 잠금 아래 다시 확인 → 모든 세션 REVOKED(`RIDER_RESTRICTED`) + `SubjectBlocked`(주체 전체) → DONE. 기한(`to`)이 지났으면 끊지 않고 LIFTED. dispatch 장애면 다음 점검에. **DONE 이고 제한이 유효하면 새 로그인 403 ACCOUNT_NOT_AVAILABLE**(로그인·같은 키 재전송 둘 다). PENDING 동안은 로그인 된다(픽업한 배송 마무리). 처음 켜기 전 소비 그룹 시작 위치 = latest(지난 제한은 rider-service 상태가 이미 막음).
- 주체 비활성(`rider_account.state=DISABLED`)을 바꾸는 API 는 아직 없다.
- **탈퇴(2026-11-17)**: `RiderWithdrawn`(rider, delivery.rider.v1 — 같은 `riderEvents` 바인딩) → `RiderWithdrawals`: 바로 모든 세션 끊기(revoke_reason RIDER_WITHDRAWN) + `SubjectBlocked`(ACCOUNT_DISABLED — 이미 나간 access token 도 401) + 계정 DISABLED + **Google 연결(`rider_identity`) 지움** — 같은 Google 계정의 다음 로그인은 새 라이더 주체(사용자 "재가입 허용"). 진행 중 배송이 있으면 rider-service 가 탈퇴를 막으므로 기다리지 않는다.
- **카카오·네이버(2026-11-19, 0.2.0·V3 — 라이더 앱 요청 R1, 고객 ADR-0071 과 같은 흐름)**: `IdTokenVerifiers`(GOOGLE·KAKAO·NAVER) — 카카오 iss `https://kauth.kakao.com`·JWKS 고정, aud = `pmart.rider-auth.kakao.client-ids`(앱 키), 네이버 iss `https://nid.naver.com`, aud = `pmart.rider-auth.naver.client-ids`. 앱 키가 없으면 그 제공자만 422 CONFIG_REQUIRED. 계정 하나 = 제공자 하나(사용자 "하나만"). 연결 때 **가린 이메일만**(`rider_identity.email_masked`, 예 a***@k***) — 같은 번호 안내용, 원문 없음. 내부 `GET /internal/riders/{id}/identity`(provider·emailMasked·linkedAt).
- **네이버 인가 코드 서버 교환(2026-11-20, 0.3.0 — ADR-0078, BR-69)**: `POST /rider-auth/naver-token-exchanges`(permit-path). 네이버는 secret 없는 교환을 거절하므로 `application/NaverCodeExchangeService` 가 `infrastructure/NaverTokenClient`(전용 RestClient·JDK 클라이언트, 연결 2초·응답 5초 — 공용 빌더·사용자 토큰 안 씀)로 `{token-base-url}/oauth2/token` 에 client_secret_post 교환하고 **id_token 만** 돌려준다(access/refresh 버림, 계정·세션 안 만듦). 설정 `pmart.rider-auth.naver.{client-ids(첫 번째 = client_id), client-secret({cipher}), callback-url, token-base-url, connect-timeout, read-timeout}`(`NaverExchangeProperties`, toString 이 secret 가림). redirectUri ≠ callback-url 400, secret·callback 없음 422, 네이버 4xx·본문 error 401 PROVIDER_REJECTED(오류 코드만 로그), 시간 초과·5xx 503 PROVIDER_UNAVAILABLE. 같은 키 재전송은 `RequestIdempotency` 가 저장한 id_token 재생(교환은 기록 트랜잭션 안 — 동시 같은 키는 대기), 실패는 기록 안 함. 시험 `NaverCodeExchangeIT`(WireMock).
- **동의는 처음 가입할 때만(2026-11-22, 0.4.0·V4 — RA-1, 사용자 "라이더쪽을 수정해야해, 소셜로그인부분때문에", 고객 auth-service 와 같게)**: ① 탈퇴 반영 커밋 뒤 카카오 앱 연결 끊기(`KakaoUnlinkClient`, `pmart.rider-auth.kakao.admin-key` 없으면 안 부름). ② 네이버 교환 `purpose=WITHDRAWAL` 이면 프로필을 읽은 뒤 `grant_type=delete`. ③ LOGIN 은 그 sub 로 가입을 마친 라이더가 없을 때만 끊음(연결 없음, 또는 rider-service `GET /internal/riders/{id}` 404 — `infrastructure/RiderClient`, `pmart.rider-auth.rider-base-url`), 조회 실패면 안 끊음. ④ 네이버 프로필 email 을 가려 sub 의 SHA-256 에 10분(`naver_email_hold`) → 네이버 로그인 때 `email_masked` 로 옮김(`pmart.rider-auth.naver.profile-base-url`). 끊기·프로필 실패는 교환·탈퇴를 막지 않는다. access token 은 한 번 쓰고 버림(저장·응답·로그 금지).
