# 알림: auth-service 운영자 로그인·계정 관리 API 를 없앴다 (ADR-0056 7단계)

- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients `web/backoffice`(옛 로그인 모드·운영자 계정 화면 코드)
- 근거: 사용자 결정 2026-11-13 "시험스크립트 옮기는쪽이 낫겠다 빼는걸로하자". ADR-0056 "옮기는 순서" 7단계.

## 1. 없어진 것 (LAN 배포됨)

| 경로 | 지금 |
|---|---|
| `POST /api/v1/auth/operator-logins` | 없음 — 게이트웨이 401(공개 경로 아님) |
| `POST /api/v1/auth/password-changes` | 없음 |
| `/api/v1/operator-accounts/**` | 없음 — 게이트웨이 라우트 삭제 |
| auth-service 의 운영자(OPERATOR) 토큰 | 발급하지 않고, 모든 서비스가 받지 않는다(발급자 표 auth-service 줄은 CUSTOMER·GUEST·SERVICE 만) |

**직원 로그인·토큰은 employee-auth-service 하나뿐이다.** 고객·비회원 로그인(`/auth/social-logins`·`/auth/guest-sessions`·`/auth/tokens`·`/auth/logouts`·`/auth/sessions/current`)은 그대로다(확인함).

## 2. 클라이언트에 미치는 영향

- 계약 `auth-service.yaml` 에서 `OperatorAccount`·`OperatorLoginResult`·`RoleName` 스키마와 위 경로가 빠졌다. `npm run gen:api` 뒤 아래가 타입 오류가 난다(로컬 커밋 `fff8321` 기준):
  - `web/backoffice/src/api/auth.ts`(`OperatorAccount`, `POST /auth/operator-logins`)
  - `web/backoffice/src/app/SessionContext.tsx`(`loginOperator`·`logoutOperator`)
  - `web/backoffice/src/features/operators/OperatorAccountsPage.tsx`, `App.tsx` 의 `loginMode !== 'employee-auth'` 분기
- 이 코드는 employee-auth 가 아닌 옛 로그인 모드(목·local 의 아이디/비밀번호, `lan-keycloak`)용이다. **지워 달라** — 백오피스 로그인은 `lan` 빌드의 employee-auth-service 흐름 하나만 남긴다. 목 모드는 지금 인사팀 백오피스처럼 employee-auth 목(목 티켓)으로.
- 운영자 계정 관리(역할·센터 바꾸기)는 이제 인사팀 백오피스(직원 등록·상태)와 권한 요청(approval `GRANT`)으로 한다. 관리자 보정 경로 `PUT /api/v1/employee-auth/employees/{operatorId}`(OPERATOR_ADMIN)는 있지만 화면 요청은 아직 없다.

## 3. 시험 계정

- `claude-dev` 등 시험 계정은 employee-auth-service 계정으로만 있다(Keycloak 비밀번호, `.secrets/keycloak-*` — PC 1). auth-service 의 `claude-dev`·`admin` 아이디/비밀번호 로그인은 더 안 된다.
- 클라이언트 쪽 시험 스크립트가 `/auth/operator-logins` 를 쓰고 있으면 백오피스 실로그인 스크립트(Keycloak 화면 + 티켓 교환)로 바꿔 달라.
