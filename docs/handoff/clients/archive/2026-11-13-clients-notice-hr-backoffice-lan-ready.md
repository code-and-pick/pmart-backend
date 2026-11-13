# 알림: 인사팀 백오피스 LAN 준비 끝 — `:8081` 서빙, 인사 시험 계정, Keycloak 역할 정리

- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients(`web/hr-backoffice`, `web/backoffice`)
- 앞 문서: `2026-11-13-clients-request-hr-backoffice.md`, `2026-11-13-clients-reply-hr-backoffice-questions.md`
- 읽은 것: pmart-clients 로컬 커밋 `fff8321`(아직 push 전) — 인사팀 백오피스 목 서버 구현·백오피스 `GRANT` 표시.

## 1. LAN 에 준비된 것

| 항목 | 상태 |
|---|---|
| hr-service·권한 요청 연동 | 배포됨(5-5). 게이트웨이 `/api/v1/hr/**` |
| 로그인 앱 `hr-backoffice` | 복귀 `http://192.168.0.40:8081/auth/callback`·`http://d603-16:8081/auth/callback`·`http://localhost:5174/auth/callback` 허용(다른 주소 400), SSO 켬. 로그인 시작 302 확인 |
| Keycloak 로그아웃 허용 | `…:8081/login`, `localhost:5174/login` |
| 게이트웨이 CORS | `http://192.168.0.40:8081`·`http://d603-16:8081` 허용(확인 — 다른 출처 403) |
| **PC 4 `:8081` 서빙** | nginx `hr-backoffice-web` 이 떠 있다(지금은 "빌드가 아직 없다" 안내 페이지). 지금 백오피스와 같은 nginx 설정(SPA 대체·`/assets/` 장기 캐시) |

**빌드 올리는 방법**: `npm run build:lan -w web/hr-backoffice` 결과 폴더를 백엔드에 알려 주면 백엔드가 `deploy/lan/lan.sh publish-hr-backoffice <dist>` 로 올린다(지금 백오피스 `publish-backoffice` 와 따로라 서로 덮지 않는다). 지금 백오피스처럼 PC 4 에 직접 복사하면 백엔드의 다음 `deploy pc4` 때 덮이니, 직접 복사했으면 알려 달라(백엔드 사본을 맞춘다).

## 2. 시험 계정

| 계정 | 역할(employee-auth-service) | 쓰임 |
|---|---|---|
| `claude-approver` | 기존 승인 역할 + **`HR_ADMIN`** | 인사팀 시험(직원 등록). Keycloak 비밀번호는 `deploy/lan/.secrets/keycloak-claude-approver-password`(PC 1, git 제외) |
| `claude-dev` | `OPERATOR_ADMIN` | 권한 승인(인사팀이 올린 `GRANT` 를 승인함에서 결정 — 요청자와 다른 사람) |
| `heeji` | `HR_ADMIN`·`PRICE_APPROVER` | 실제 인사 담당(사용자 결정) |

- 백엔드 E2E(2026-11-13) 17/17 통과: 등록 → 계정 발급 → 안내 메일 도착 → `MFC_WORKER` 자동 승인·`MD` 심사 → 토큰 역할 → 정지 즉시 401 → 퇴사. 시험 직원 `claude-hr-e2e-071253@pmart.com`(P2026-00014)은 퇴사 상태로 남아 있다(지우지 않는 설계).
- 기존 직원 13명은 P2026-00001~00013. 고용 형태·소속·시작일은 자리값.

## 3. Keycloak 정리(ADR-0056 7단계 일부) — 클라이언트 영향

- Keycloak Realm 의 업무 역할(`OPERATOR_ADMIN`·`MD`·…)과 `centers` 속성을 지웠다. **Keycloak 토큰에는 이제 기본 역할만** 있고, PMart API 는 Keycloak 토큰을 받지 않는다(401). 역할·센터는 employee-auth-service 토큰에만 있다.
- 백오피스의 옛 빌드 모드 `lan-keycloak`(Keycloak 토큰을 API 에 직접 쓰는 것)는 더 동작하지 않는다 — 지금 `lan` 빌드(employee-auth-service 로그인)만 쓴다. 지워도 된다.

## 4. 부탁

1. `fff8321` push, 인사팀 백오피스 `build:lan` 결과 전달.
2. 서빙 뒤 실로그인 확인(백오피스 ↔ 인사팀 백오피스 SSO, 실제 등록·발급·409 code)과 지금 백오피스의 `GRANT` 표시 LAN 배포.
