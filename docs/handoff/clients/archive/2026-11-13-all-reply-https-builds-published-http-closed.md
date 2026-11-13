# 회신: 두 백오피스 HTTPS 빌드 올림, HTTP 닫음(HTTPS 로 넘김)

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-13
- 대상: backoffice·hr-backoffice(`all`)
- 답하는 것: pmart-clients `docs/handoff/2026-11-13-backend-web-https-lan-builds.md`(작업 폴더에서 읽음 — 커밋 전), `2026-11-13-backend-area-split-reply.md`(`d6c583b` — 받음, 할 일 없음)

## 1. 올린 것

| 앱 | 빌드(클라이언트 작업 폴더 dist, 읽기만) | 주소 |
|---|---|---|
| 백오피스 | `index-Dmg9_m1j.js` | `https://192.168.0.40` |
| 인사팀 백오피스 | `index-mOfMoV39.js` | `https://192.168.0.40:8444` |

- `lan.sh publish-backoffice`·`publish-hr-backoffice` 로 올렸다(백엔드 사본도 같은 빌드). 두 빌드 API 주소 `https://192.168.0.40:8443/api/v1` 만, 인라인 스크립트 없음 확인.
- 소스(vite.config.ts 두 줄)는 아직 클라이언트 쪽 커밋 전이었다 — 커밋해 달라.

## 2. HTTP 닫음 (사용자 "빌드 옮기고 닫아주고")

- **80·8081 은 화면을 내주지 않고 HTTPS 로 넘긴다(301)**: `http://192.168.0.40/…` → `https://192.168.0.40/…`, `http://…:8081/…` → `https://…:8444/…`(경로·쿼리 유지). 북마크도 그대로 쓸 수 있다.
- 허용 목록에서 http 백오피스 주소를 뺐다: employee-auth-service 복귀 주소(http 로 로그인 시작하면 400), 게이트웨이 CORS(http 출처 403), Keycloak 로그아웃 주소. `localhost:5173·5174`(개발)는 그대로.
- CSP 의 게이트웨이 출처도 `https://…:8443` 만. HSTS 1일(`Strict-Transport-Security: max-age=86400`).

## 3. 백엔드가 확인한 것

- HTTPS 두 주소 200·새 빌드·CSP·HSTS, HTTP 4개 주소 301 대상 맞음.
- 헤드리스 Chrome: 두 로그인 화면 그려짐, CSP·혼합 콘텐츠·CORS 오류 0.
- 스크립트: HTTPS 복귀 주소로 백오피스 로그인 → 인사팀 백오피스 SSO → API 200 → 로그아웃한 세션 0.6초 만에 401 `SUBJECT_BLOCKED`, 다른 세션 200.
- **사람(또는 헤드리스) 브라우저로 로그인한 뒤 업무 화면까지 도는 것은 아직** — 회신 §3 에 적은 확인(두 주소 로그인·SSO·로그아웃·화면 조회)을 해 달라.

## 4. 그 밖

- 남은 클라이언트 일(회신 §4)은 그대로 기다린다: 옛 운영자 로그인 코드 삭제(`gen:api` 전에), 인사팀 "기한 없음" 표시, mfc_app 정지 안내·실기기.
- 앱 영역 규칙 받았다. 어느 PC 가 어느 앱 영역을 맡는지는 사용자가 정한다.
