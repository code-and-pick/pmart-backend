# 백오피스 로그인을 Keycloak 으로 — ADR-0054 결정 D·L

- 작성: pmart-backend, 2026-11-11
- 대상: pmart-clients 임시 백오피스(`web/backoffice`)
- 근거: ADR-0054(직원 = Keycloak Realm `pmart-staff`, 고객 = auth-service, 라이더 = rider-auth-service). 결정 D(옮기는 동안 auth-service 운영자 로그인을 함께 둔다), 결정 L(직원 계정은 당분간 Keycloak 관리 화면에서 관리 — 2026-11-11 사용자)
- 백엔드 상태: Keycloak 은 LAN PC 5 에서 돌고 있다. 게이트웨이·모든 서비스가 Keycloak 직원 토큰을 운영자로 받는다(2026-11-11 같은 계정의 두 토큰으로 API 14곳 비교 — 13곳 같은 결과, 다른 1곳은 아래 §4). **백엔드 API·계약은 바뀌지 않는다.** 바뀌는 것은 로그인·토큰을 어디서 받느냐다.

## 1. Keycloak 설정 값

| 항목 | 값 |
|---|---|
| 발급자(issuer) | `http://192.168.0.101:8180/realms/pmart-staff` |
| OIDC 설정 | `http://192.168.0.101:8180/realms/pmart-staff/.well-known/openid-configuration` (여기서 인가·토큰·로그아웃 주소를 읽는다) |
| 클라이언트 ID | `pmart-backoffice` — 공개 클라이언트(**client secret 없음**) |
| 흐름 | Authorization Code + **PKCE(S256 필수)** |
| redirect 허용 | `http://192.168.0.40/*`, `http://d603-16/*`, `http://localhost:5173/*` — 그 밖은 Keycloak 이 400. 로그아웃 뒤 돌아갈 주소도 같은 목록 |
| scope | `openid` |
| 수명 | access 15분, 로그인 세션 12시간(마지막 사용 기준이 아니라 로그인부터 — 12시간 뒤 다시 로그인). 5회 실패하면 10분 잠금 |

- 표준 OIDC 클라이언트 라이브러리(예: `oidc-client-ts`)면 충분하다. Keycloak 전용 라이브러리는 필요 없다. 고르는 것은 클라이언트 쪽 판단.
- 브라우저에서 Keycloak 토큰 주소를 부를 때 CORS 는 redirect 허용 주소의 출처(`http://192.168.0.40` 등)로 열려 있다.

## 2. 바꿀 것

| 지금(auth-service) | 바꾼 뒤(Keycloak) |
|---|---|
| 로그인 화면에서 아이디·비밀번호 → `POST /auth/operator-logins` | 로그인 버튼 → Keycloak 로그인 화면으로 이동(인가 요청 + PKCE) → 돌아와서 code 를 토큰 주소에 보내 토큰을 받는다. **비밀번호를 백오피스가 받지 않는다** |
| 갱신 `POST /auth/tokens`(refresh 한 번만) | 토큰 주소에 `grant_type=refresh_token`. 갱신은 지금처럼 한 곳(single-flight)에서만 |
| 로그아웃 `POST /auth/logouts` | Keycloak 로그아웃 주소(`end_session_endpoint`)로 이동(`id_token_hint`, `post_logout_redirect_uri`) + 탭의 토큰 삭제 |
| 현재 세션 `GET /auth/sessions/current` | 쓰지 않는다 — §4 |
| 첫 로그인 비밀번호 변경(`passwordChangeRequired`) | Keycloak 이 로그인 화면에서 처리한다(관리자가 "임시 비밀번호"로 만든 경우) |
| 운영자 계정 화면(`/operator-accounts` 목록·생성·수정·비밀번호 초기화) | **내린다**(결정 L). 계정은 관리자가 Keycloak 관리 화면(`http://192.168.0.101:8180` → `pmart-staff`)에서 만든다. 메뉴에서 빼거나 "Keycloak 에서 관리" 안내로 바꾼다 |

API 를 부를 때는 지금처럼 `Authorization: Bearer <access token>` 하나다. `X-Source-System`·`Idempotency-Key` 등 다른 머리글은 그대로.

## 3. 토큰에서 화면용으로 읽는 값(화면 편의용 — 권한 판정은 서버가 한다)

| 읽는 것 | 지금(auth-service 토큰) | Keycloak 토큰 |
|---|---|---|
| 운영자 ID(화면 표시·내 담당 등) | `sub`(`OP-…`) | **`pmart_operator_id`**(`OP-…`). `sub` 는 Keycloak 내부 UUID 라 쓰지 않는다 |
| 역할(메뉴 — 2026-11-09 역할별 메뉴 요청) | `roles` | **`realm_access.roles`**. Keycloak 기본 역할(`default-roles-pmart-staff`·`offline_access`·`uma_authorization`)도 같이 들어 있으니 메뉴 판단에서 무시한다 |
| 센터 | `centers` | `centers`(같은 이름, 없으면 빠짐 = 빈 목록) |
| 사용자 종류 | `typ`(`OPERATOR`) | Keycloak 토큰의 `typ` 는 사용자 종류가 아니라 토큰 종류라 언제나 **"Bearer"** 다 — 운영자 판단에 쓸 수 없다. **발급자(`iss`)가 위 Keycloak 주소면 직원(운영자)** 으로 본다(백엔드 발급자 표도 같은 방식). 지금 `session.ts` 의 `typ === OPERATOR` 확인은 Keycloak 토큰에서 실패한다 |
| 표시 이름 | 로그인 응답 `operator.displayName` | ID 토큰의 `name`(또는 `given_name`·`family_name`) |
| 만료 | `exp` | `exp`(같음) |

## 4. "현재 세션" 화면
`GET /auth/sessions/current` 는 auth-service 에 저장된 세션을 찾는 API 라 Keycloak 토큰에는 401 `SESSION_EXPIRED` 를 준다(백엔드 확인 2026-11-11). 운영자 ID·역할·만료는 토큰에서 읽는다(§3). 세션 시작 시각이 필요하면 ID 토큰의 `auth_time`(로그인 화면 흐름에서 실린다 — 백엔드가 시험한 직접 로그인 토큰에는 없었다. 없으면 표시하지 않는다).

백엔드 확인(2026-11-11, 시험 클라이언트 `pmart-dev-cli`): ID 토큰에 `name`·`given_name`·`family_name`·`preferred_username`, refresh 갱신 200, 토큰 주소 CORS 사전 요청이 `Origin: http://192.168.0.40` 에 `Access-Control-Allow-Origin` 을 돌려줌.

## 5. 옮기는 순서
1. 백오피스가 Keycloak 로그인을 붙인다. 옮기는 동안 auth-service 로그인도 백엔드는 계속 받는다(결정 D) — 전환 스위치(설정값)로 두 방식을 고를 수 있으면 확인이 쉽다.
2. 시험 계정: 백엔드 Claude 용 `claude-dev` 는 이미 Keycloak 에 있다(비밀번호는 백엔드 `.secrets` — 공유하지 않는다). 클라이언트 시험용 계정은 사용자가 Keycloak 관리 화면에서 만든다(역할 Realm 역할, 속성 `pmart_operator_id`·`centers`).
3. LAN 백오피스(`http://192.168.0.40`)를 Keycloak 로그인으로 배포하면 이 폴더에 회신해 달라. 백엔드는 그 확인 뒤 발급자 표의 auth-service 줄에서 OPERATOR 를 뺀다 — **그 뒤로 auth-service 운영자 로그인 토큰은 401** 이다.

## 6. 오류
| 상황 | 동작 |
|---|---|
| API 401 `UNAUTHENTICATED`(access 만료·무효) | 갱신 한 번 → 실패면 Keycloak 로그인으로 |
| Keycloak 갱신 실패(`invalid_grant` — 세션 12시간 끝남·관리자가 계정 비활성·로그아웃) | 토큰 삭제 후 로그인으로 |
| redirect 가 400 `Invalid parameter: redirect_uri` | 백오피스 주소가 §1 목록에 없다 — 백엔드에 알림 |
| API 403 | 역할 부족(지금과 같음) |
