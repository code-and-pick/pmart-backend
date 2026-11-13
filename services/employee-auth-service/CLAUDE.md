# employee-auth-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `employee-auth-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.
결정: ADR-0056(Keycloak 은 직원 신원만, 직원 토큰은 이 서비스). 설계·구현에서 정한 것: `docs/architecture/auth/employee-auth-structure.md`(진행 절).

## 계약
- REST: `contracts/openapi/employee-auth-service.yaml` → `openApiGenerate` 가 `com.pmart.employeeauth.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트(2026-11-13 5-3): **구독** `platform.employee.v1`(hr-service `EmployeeRegistered·Suspended·Reinstated·Left` — `EmployeeEventHandler`, 바인딩 `employeeEvents-in-0`), **발행** `SubjectBlocked`(platform.subject-block.v1 — 직원 정지·퇴사·관리 경로 정지, issuer = 이 서비스). **구독** `platform.approval.v1`(2026-11-13 5-4 — `ApprovalDecided` type GRANT·정본 이 서비스만, `GrantApprovalHandler`, 바인딩 `approvalEvents-in-0`), **발행** `GrantApplied`(platform.auth.v1, 승인 행마다)·`ApplicationReceiptRecorded`(platform.approval-receipt.v1, 승인마다 — approval 실행 축).

## 패키지
`com.pmart.employeeauth.{api, application, infrastructure}`. Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- Keycloak 토큰·비밀번호·이메일·이름은 저장하지 않는다 — Keycloak `sub` ↔ 운영자 ID 연결만. state·티켓·refresh token 은 SHA-256 만. Keycloak PKCE verifier 는 콜백에서 지운다.
  - **예외(2026-11-12, 백오피스 SSO)**: SSO 앱 세션에만 Keycloak **ID 토큰**을 둔다(`login_ticket`→`employee_session.keycloak_id_token`) — 로그아웃 때 `id_token_hint` 로 확인 화면 없이 Keycloak 세션을 끝내려고. 클라이언트 기본 범위를 `basic` 만 두어 이름·이메일이 없고(시험이 확인), `aud` = 이 서비스라 PMart API 에 쓸 수 없다. 세션이 끝나면(로그아웃·폐기·만료 뒤 하루 정리) 지운다. 만료된 ID 토큰으로도 Keycloak 26.8.1 로그아웃이 된다(2026-11-12 확인).
- `employee_account` 가 직원 상태·역할·권한 센터의 정본(D1). 상태는 hr-service 사건(`hr_version` 으로 늦은 사건 버림, 근무 시작일 `start_date` 전 로그인 거절)과 관리 경로(PUT, 변경 기록)가 바꾼다. 사건으로 새로 생긴 직원은 권한 없음.
- 승인된 권한(2026-11-13 5-4): `employee_grant`(승인 행 하나 = 한 줄, `<approvalId>:<lineId>`, 유효 기간 = 시작일 00:00 ~ 마지막 날 다음 00:00 Asia/Seoul, 모르는 역할 행은 거절 UNKNOWN_ROLE). **토큰 역할·센터 = `employee_account` 값(관리 경로·이전 계정) ∪ 지금 유효한 grant**(`EmployeeAccess` — 로그인·갱신 때마다 계산). 관리 경로 PUT·조회는 계정 값만 다룬다(grant 는 보이지 않음 — 화면은 5-6).

## 로그인 (Keycloak 기밀 클라이언트)
- Spring OAuth2 Client 를 쓰지 않는다 — 게이트웨이가 Cookie 헤더를 지우므로 세션 쿠키 없이 돌게 직접 구현(`EmployeeLoginService`, `infrastructure/KeycloakClient`).
- 브라우저 주소(authorization-uri)와 서버끼리 주소(token·jwks·logout — 같은 PC 5 Docker 네트워크)를 나눈다. ID 토큰 `iss` 는 `pmart.employee-auth.keycloak.issuer` 와 정확히 같아야 한다(Keycloak KC_HOSTNAME 기준).
- 앱별 SSO(`pmart.employee-auth.apps.<앱>.sso`): **끈 앱(기본, PDA)** 은 콜백에서 코드 교환 직후 Keycloak 세션을 끝내고(`endSession`) 시작 URL 에 `prompt=login`. **켠 앱(backoffice, 2026-11-12 사용자 결정)** 은 Keycloak 세션을 남겨 같은 브라우저의 다음 로그인이 비밀번호 없이 되고, 로그아웃 응답 `idpLogoutUri`(Keycloak end_session + `id_token_hint` + `<로그인한 출처><post-logout-path>`)로 브라우저가 Keycloak 세션을 끝낸다. 거절된 로그인(미등록·정지)은 SSO 앱이어도 Keycloak 세션을 끝낸다. Keycloak 세션 무활동 한도는 Realm 값(12시간, 사용자 결정 — 바꾸지 않음).
- 티켓은 60초·1회, 시작한 기기 + 앱의 code_verifier(S256) 로만. 틀린 시도도 티켓을 태운다.
- 복귀 주소는 설정 허용 목록에서만(열린 리디렉션 금지). 오류는 `?error=코드` 로 앱에 돌려준다(state 를 모르면 400).
- Keycloak 클라이언트 시크릿은 pmart-config `{cipher}`(원본 `deploy/lan/.secrets/keycloak-employee-auth-client-secret`), Keycloak 쪽 값은 Realm 파일 자리표시자(render.sh). 로그에 남기지 않는다.

## 토큰 (자체 JWT)
- access: RS256, 예전 auth-service 운영자 토큰과 같은 모양 — `iss` = `pmart.employee-auth.issuer`(발급자 표 `staff-ea` 줄과 같게), `sub` = 운영자 ID, `typ=OPERATOR`, `aud=pmart-api`, `roles`, `centers`, `sid`, 15분.
- refresh: 한 번만(재사용이면 세션 폐기 `TOKEN_REUSED`), 로그인한 기기에서만. 세션 = min(갱신 + 앱 무활동 한도, 시작 + 12시간).
- 서명키 `pmart.employee-auth.signing.keys`(다른 발급자와 다른 키, LAN `{cipher}`, 원본 `.secrets/employee-auth-signing-key.pem`). local/test 만 `ephemeral=true`.
- **웹 refresh 쿠키(ADR-0059)**: 앱 설정 `refresh-cookie` 를 켠 앱은 refresh token 을 본문 대신 세션별 HttpOnly 쿠키(`api/RefreshCookies` — Path = 갱신 주소/{sessionId})로, CSRF 는 Signed Double-Submit(`application/CsrfTokens` — HMAC 에 sessionId 를 묶음, 서버 저장 없음, 세션 동안 고정, 키 `pmart.employee-auth.refresh-cookie.csrf-key` `{cipher}` — local·시험은 `signing.ephemeral` 이면 임시 키). 갱신 `POST /tokens/refresh/{sessionId}` 는 CSRF(403, 세션 유지) → 소유권(세션·기기·쿠키 앱, 401 — 토큰 안 씀) → 회전 순. 쿠키 앱 세션은 본문 갱신 경로를 못 쓰고, 본문 앱은 쿠키 경로를 못 쓴다. 로그아웃은 그 세션의 쿠키 둘을 지운다. 시험 `RefreshCookieIT`(Keycloak 없이 티켓을 직접 넣음).
- 로그아웃·현재 세션은 이 서비스가 발급한 토큰만(Keycloak 토큰은 403). 관리 경로는 발급자 표가 인정하는 OPERATOR + `OPERATOR_ADMIN`.

## 시험
```bash
./gradlew :services:employee-auth-service:check   # 단위 + 통합(PostgreSQL·Keycloak 26.8.1 컨테이너) + 계약 검증
```
- `EmployeeLoginIT` 는 Keycloak 컨테이너(`src/integrationTest/resources/keycloak/pmart-staff-it-realm.json` → 컨테이너 안 이름 `pmart-staff-realm.json` — 26.8 은 파일 이름 = Realm 이름)를 띄우고 브라우저를 흉내 낸다. Keycloak 쿠키에 Secure 가 붙어 java.net.CookieManager 대신 직접 쿠키를 보관한다.
- 로컬 포트 8511. LAN PC 5. 로컬 프로필에는 Keycloak 이 없어 로그인 시작이 CONFIG_REQUIRED.
