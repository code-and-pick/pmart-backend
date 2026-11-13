# 알림: 앞으로 나눌 영역 — RAG, 백오피스(백엔드·클라이언트 한 PC) (2026-11-13 계획)

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients 전체(`all`) — 특히 backoffice·hr-backoffice·web-common
- 앞 문서: `../archive/2026-11-13-all-notice-area-split.md`(영역 나누기), `2026-11-13-clients-notice-dev-on-other-pcs.md`
- 백엔드 정본: pmart-backend `docs/operations/pc-split.md` §1-1·1-2
- 근거: 사용자 2026-11-13 — "이후엔 PC4 에 RAG 쪽, PC2 에 백오피스(백엔드랑 클라이언트 둘다)" → "아직 로그인 기능 완벽하진 않은데 지금 나눠주는게 좋을까?" → 아래 순서로 정리. PC 배치·경계는 **아직 확정 전**.

## 1. 계획

| 영역 | 언제 | PC(예정) | 범위(예정) |
|---|---|---|---|
| **rag**(백엔드만) | 지금 나눠도 됨 | PC 2 추천 | ai-assistant-service·customer-assistant-service. 클라이언트에는 영향 거의 없음(고객 앱·백오피스의 질문 화면 계약은 그대로) |
| **backoffice**(백엔드 + 클라이언트를 한 PC 가) | **로그인 마무리 뒤**(§2) | PC 4 추천 | 백엔드 `backoffice-bff`(case·audit 는 정할 것) + 클라이언트 `web/backoffice`·`web/hr-backoffice`·`web/core`·`web/ui`(웹 공통 한 주인 — 정할 것) |

- 처음 말한 배치(PC 4 = RAG, PC 2 = 백오피스)에서 바뀔 수 있다 — PC 4 에 게이트웨이·설정 서버·Eureka 가 있어 RAG 개발 부하를 피하고, 백오피스 화면 서빙이 PC 4 에 있어서. 사용자가 정한다.
- backoffice 영역이 생기면 **그 PC 가 백엔드·클라이언트 두 저장소를 다룬다.** 그래도 규칙은 같다: 저장소마다 커밋·PR 따로, 계약을 바꾸면 백엔드 계약 커밋 먼저, handoff 는 짧게라도 남긴다(다른 앱·다른 PC 가 본다). 배포는 PC 1.

## 2. 로그인 마무리 기준 (그 전까지 웹 로그인 코드는 지금처럼)

1. 두 백오피스 HTTPS 전환·HTTP 80·8081 닫기 — **클라이언트 HTTPS 빌드 대기**(`../archive/2026-11-13-clients-notice-backoffice-https-csp-logout.md`, `../mfc-app/2026-11-13-clients-reply-mfc-app-login-and-https-build.md` §2).
2. 웹메일 로그인을 Keycloak 으로 합칠지(첫 로그인 = Keycloak 임시 비밀번호, 인사팀 화면의 "사서함 초기 비밀번호"가 바뀜) — 사용자 결정 대기. 합치면 hr-backoffice 화면 변경 요청이 간다.
3. CSRF(웹 refresh token → HttpOnly 쿠키, `web/core` 로그인 코드가 바뀜) — 미뤄 둠, 할지·언제 할지 결정 대기.
4. mfc_app 실기기 확인 — **클라이언트 결과 대기**.

그 전까지 `web/core` 의 직원 로그인 코드·백오피스·인사팀 화면의 로그인 부분은 지금 클라이언트 담당이 그대로 맡는다(겹치지 않게).

## 3. 클라이언트에 부탁

- 앱 단위 영역 규칙(앞 알림 `../archive/2026-11-13-all-notice-area-split.md`)을 정할 때 이 계획을 함께 고려해 달라 — 특히 web-common(`web/core`·`web/ui`)을 backoffice 영역에 묶을지.
- §2 의 1·4 결과를 회신해 달라.
