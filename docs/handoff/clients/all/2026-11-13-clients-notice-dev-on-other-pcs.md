# 알림: 다른 PC 에서 클라이언트를 개발할 때 — 지금 방식 그대로 (2026-11-13)

- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients — PC 1 이 아닌 PC(PC 3·PC 5 등)에서 작업하는 사람·Claude 포함
- 근거: 사용자 요청 2026-11-13 "다른 PC들의 클라이언트 프로젝트쪽도 지금 방식처럼 작업을 해야하는데 일단 클라이언트한테 이내용을 공유해줘".
- 백엔드 쪽 같은 내용: pmart-backend `docs/operations/dev-on-other-pcs.md`(백엔드 개발자용 — 같이 읽어 두면 좋다).

## 1. 두 저장소를 같은 폴더에, 둘 다 최신으로

- `gen:api`(`tools/web-tools/gen-api.mjs`)·`tools/gen_api.dart` 는 옆 폴더 `../pmart-backend/contracts` 를 읽는다. 다른 PC 에도 **pmart-backend 를 옆에 받아 두고 `git pull`** 한다.
- 2026-11-13 백엔드 계약이 바뀌었다 → `gen:api` 뒤 아래가 깨진다:
  - auth-service 운영자 로그인·계정 관리 삭제(`/auth/operator-logins`·`/operator-accounts`, `OperatorAccount`) — 백오피스의 옛 로그인 모드·운영자 계정 화면 코드는 지운다(알림 `../archive/2026-11-13-clients-notice-auth-operator-login-removed.md`).
- 클라이언트 저장소 규칙(백엔드 파일은 읽기만, 회신은 `docs/handoff/`)은 그대로.

## 2. 실서버(LAN)에 붙어 시험할 때 — 직원 로그인

- 직원 로그인은 **employee-auth-service 하나**: 웹은 `app=backoffice`·`hr-backoffice`(SSO), PDA 는 `app=mfc`(SSO 없음). 흐름은 지금 코드(`web/core` `createEmployeeAuth`, mfc_app `ApiAuthRepository`) 그대로.
- 시험 계정 비밀번호는 PC 1 `pmart-backend/deploy/lan/.secrets/`(git 에 없음). 다른 PC 에는 **필요한 파일만** 사용자가 복사한다:

| 파일 | 계정 | 역할 |
|---|---|---|
| `keycloak-claude-dev-password` | `claude-dev` | `OPERATOR_ADMIN`(백오피스·승인함) |
| `keycloak-claude-approver-password` | `claude-approver` | `HR_ADMIN` + 승인 역할(인사팀 백오피스) |
| `keycloak-claude-mfc-sup-password` | `claude-mfc-sup` | `MFC_SUPERVISOR` · `MFC-SEED-01` |
| `keycloak-claude-mfc-wrk-password` | `claude-mfc-wrk` | `MFC_WORKER` · `MFC-SEED-01`(PDA 시험) |

  Keycloak 관리자 비밀번호(`keycloak-admin-password`)와 `keycloak-staff-passwords.tsv` 같은 묶음 파일은 옮기지 않는다. 비밀번호·토큰은 출력·커밋하지 않는다.
- 브라우저 없이 토큰만 필요하면(스크립트) 백엔드 `deploy/lan/checks/staff_auth.py` `staff_token("claude-dev")` 를 써도 된다(LAN 시험 교환 — 같은 비밀번호 파일).

## 3. 로컬(목 서버 대신 로컬 백엔드)에서 시험할 때

- 로컬 백엔드(local 프로필)에는 Keycloak 이 없어 실제 로그인 흐름은 안 된다. 직원 토큰은 **`POST http://localhost:8511/api/v1/internal/dev-tokens`**(local 프로필 employee-auth-service, 본문 `{"operatorId","roles","centers"}`)로 받는다 — 로컬 전용, LAN·운영에는 없다. 화면 흐름 시험은 지금처럼 목 서버(MSW·목 레포지토리)로.

## 4. LAN 배포 — PC 1 백엔드가 올린다

- 웹 빌드는 **직접 PC 4 에 복사하지 말고** 빌드 결과(dist 경로·커밋)를 알려 주면 백엔드가 `publish-backoffice`·`publish-hr-backoffice` 로 올린다. 직접 복사하면 백엔드의 다음 PC 4 배포 때 덮인다(11-13 실제로 한 번 덮였다).
- 앱(APK)은 지금처럼 디버그 빌드를 기기에 설치. 게이트웨이 `http://192.168.0.40:8080/api/v1`(앱) 유지.

## 5. 지켜야 할 것 (지금 LAN 에 걸린 것)

- **HTTPS**: 백오피스 `https://192.168.0.40`, 인사팀 `https://192.168.0.40:8444`(HTTP 80·8081 은 HTTPS 빌드가 오면 닫는다 — 사용자 결정). 웹 `lan` 빌드 API 주소는 `https://192.168.0.40:8443/api/v1` 로 옮긴다(알림 `../archive/2026-11-13-clients-notice-backoffice-https-csp-logout.md`). 브라우저 PC 와 PDA 에는 LAN 루트 CA(`pmart-backend/deploy/lan/tls/pmart-lan-root-ca.crt`)를 설치한다(PC 2~5 Windows 는 설치됨).
- **CSP**: 인라인 `<script>`·`eval`·인라인 `style="…"`(HTML)·외부 CDN·`dangerouslySetInnerHTML` 금지. 외부 출처가 필요하면 백엔드에 요청.
- **로그아웃 즉시 차단**: 로그아웃한 세션 토큰은 바로 401 `SUBJECT_BLOCKED` — 401 이면 토큰을 지우고 로그인 화면(지금 처리 그대로).
- 새 시험 직원은 인사팀 백오피스로 등록한다(사서함·계정·안내 메일 자동, 소속 센터 `MFC_WORKER` 자동 승인).
