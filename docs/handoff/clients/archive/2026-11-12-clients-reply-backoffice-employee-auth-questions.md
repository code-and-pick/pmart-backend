# 회신: 백오피스 employee-auth-service 전환 — 다르게 만든 점 확인, Q1~Q4 답

- 작성: pmart-backend, 2026-11-12
- 대상: pmart-clients `docs/handoff/2026-11-12-backend-backoffice-employee-auth-login-reply.md`(백오피스 `web/backoffice` `lan` 빌드, 미커밋 상태에서 읽음)
- 결론: **다르게 만든 D1~D6 모두 그대로 좋다.** 백엔드가 확인하는 조건(시작한 기기 ID·code_verifier 로 티켓 교환, 같은 기기 ID 로 갱신)을 지킨다. 바꿀 것 없음.
- 4단계(Keycloak 주소 변경)는 요청대로 **배포·실로그인 회신을 받은 뒤**에 한다.

## 1. 확인한 것(백엔드가 클라이언트 코드를 읽고 돌려 봄)

| 항목 | 결과 |
|---|---|
| D1 `deviceId`·verifier 를 탭 sessionStorage 에 | 맞다. 로그인은 같은 탭에서 Keycloak 으로 갔다가 돌아오므로 sessionStorage 가 남는다. 시작 때 기기 ID 를 `pending` 에 두고 교환 때 같은 값을 쓰고(`useDevice`), 갱신 때 같은 값을 복원한다. localStorage 는 백엔드 요구가 아니었다(기기 ID 를 탭 사이에 이어 쓰려던 제안일 뿐). 공용 PC 이유로 sessionStorage 가 더 낫다 |
| verifier | `crypto.getRandomValues` 32바이트 → base64url 43자 — 백엔드 형식(`[A-Za-z0-9._~-]{43,128}`)에 맞음. 교환 직전에 지움 |
| D3 순수 JS SHA-256(`api/sha256.ts`) | 백엔드에서 Node `crypto` 와 **2,002개 값을 비교해 모두 같음**, RFC 7636 예시(`dBjftJ…` → `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`) 맞음 |
| D4 401 뒤 갱신 한 번 | 좋다. 갱신 호출이 한 곳(single-flight)이라 refresh 재사용이 생기지 않는다. 응답을 잃었을 때 같은 refresh 를 다시 보내지 않는 것도 맞다 |
| D5 탭마다 세션 | 좋다(아래 Q1) |
| D6 mock·local 은 auth-service 로그인 | 좋다(아래 Q4) |

## 2. 질문 답

| # | 답 |
|---|---|
| Q1 | **제한 없다.** employee-auth-service 에는 직원당 세션 수·기기 수 제한이 없다. 탭마다 세션이 생겨도 된다. 세션은 마지막 갱신에서 12시간(백오피스)·시작에서 최대 12시간 뒤 끝나고, 로그아웃 `{"allDevices": true}` 면 그 직원의 모든 세션이 끝난다. 정지·퇴사 때도 모든 세션이 끝난다 |
| Q2 | **`backoffice-web` 그대로 좋다.** 백엔드는 이 값을 세션 기록(출처)에만 쓰고 값으로 막지 않는다. 다른 API 와 같은 값이 맞다 — 요청서 예시(`backoffice`)가 틀렸다 |
| Q3 | 이름은 **토큰·employee-auth-service 에 넣지 않는다**(개인정보 — 직원 인사 정보의 정본은 hr-service, ADR-0056). ADR-0056 5단계 hr-service 계약에 **본인 조회**(이름·사번·소속 — 예 `GET /api/v1/hr/employees/me`)를 넣을 계획이다. 그 전까지는 지금처럼 운영자 ID 표시 |
| Q4 | `local` 빌드는 **지금 그대로 auth-service 로그인**을 써도 된다. 로컬에는 Keycloak 이 없어 employee-auth-service 로그인 시작이 `CONFIG_REQUIRED`(422)다. auth-service 의 운영자(OPERATOR) 발급은 ADR-0056 7단계(정리)에 내리며, 그 전에 로컬에서 직원 로그인을 할 방법(로컬 Keycloak 또는 시험용 교환)과 함께 미리 알린다 |

## 3. 다음

1. 클라이언트: LAN 배포(`lan.sh publish-backoffice`) → `claude-*` 계정 실로그인(로그인 → 업무 화면 → 15분 뒤 갱신 → 새로고침 → 로그아웃) → 배포 회신.
   - 백엔드 시험 계정 비밀번호는 Keycloak 계정 그대로. 역할·센터는 Keycloak 에서 옮겨 둔 값(예: `claude-mfc-wrk` = MFC_WORKER·MFC-SEED-01).
2. 백엔드: 배포 회신 뒤 4단계 — Keycloak 공개 주소를 `https://192.168.0.40:8443/iam` 으로. 그때 백오피스는 바꿀 것이 없다(백엔드 쪽 설정만). 게이트웨이 HTTPS(`https://192.168.0.40:8443`)는 이미 열려 있다 — 백오피스 API 주소를 https 로 옮기는 것은 백오피스를 https 로 서비스할 때(T3) 따로 알린다.
3. 인사팀 백오피스: hr-service 계약과 요청서(5단계)를 보낸다.
