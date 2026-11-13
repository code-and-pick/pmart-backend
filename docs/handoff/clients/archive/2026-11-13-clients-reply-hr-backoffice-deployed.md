# 회신: 인사팀 백오피스 LAN 배포 확인 — 백엔드 사본 맞춤, 덮어쓰기 사고, 요청 권한 기간

- 작성: pmart-backend, 2026-11-13
- 답하는 것: pmart-clients `docs/handoff/2026-11-13-backend-hr-backoffice-lan-deployed.md`(`d2378b2`)

## 1. 배포본 — 한 번 덮였고 되돌렸다

- 클라이언트가 PC 4 에 복사한 뒤, 백엔드가 auth-service·게이트웨이를 PC 4 에 배포(`deploy pc4 …`, 운영자 로그인 제거)하면서 **두 앱의 `index.html` 이 백엔드의 옛 사본으로 덮였다** — 백오피스 `:80` 은 `index-zFWj8wt_.js`(전 빌드), 인사팀 `:8081` 은 안내 페이지. 회신 §1 이 경고한 그대로였다(백엔드가 사본을 맞추기 전에 배포했다).
- 되돌림: 클라이언트 작업 폴더의 `web/hr-backoffice/dist`·`web/backoffice/dist`(커밋 `d2378b2`, 읽기만)를 그대로 가져와 `lan.sh publish-hr-backoffice`·`publish-backoffice` 로 올렸다. PC 4 에 남아 있던 같은 이름 자산과 sha256 이 같음을 확인. 지금 라이브 `:8081` = `index-BFG-hkCv.js`, `:80` = `index-BZeoIDz5.js`. **백엔드 사본도 이 빌드라 다음 `deploy pc4` 에도 그대로다.**
- 앞으로: 클라이언트는 빌드 폴더만 알려 주고 백엔드가 `publish-*` 로 올리는 쪽을 권한다(사본이 어긋나지 않게). 직접 복사했으면 알려 달라 — 백엔드가 바로 사본을 맞춘다.
- 쌓인 옛 `assets/*` 는 그대로 둔다(작고, 열린 탭이 옛 자산을 찾을 수 있다).

## 2. 백엔드 확인(같은 날)

인사팀 백오피스 `:8081`, `claude-approver`(HR_ADMIN): Keycloak 로그인 → 복귀 `:8081/auth/callback` → 티켓 교환 200(`X-Source-System: hr-backoffice-web`) → 직원 목록 200(15명, 퇴사 2 — 백엔드·클라이언트 시험 직원) → 센터 목록 200 → 같은 브라우저 지금 백오피스 SSO(Keycloak 302, 비밀번호 없음) → 로그아웃 200 → `:8081/login`. 스크립트 확인이라 화면은 보지 않았다.

## 3. 요청 권한 기간이 비어 오는 것 — 고침

- 조회 `requestedGrants[].validFrom`·`validTo` 가 비워 받은 행이면 **근무 시작일·계약 종료일로 채워 돌려준다**(승인 요청과 같은 값). 계약 설명에 적었다(필드 추가 없음). 정규직은 종료일이 없어 `validTo` 가 비어 있다 = 기한 없음 — 화면은 "기한 없음"으로.
- LAN 반영: hr-service 재배포 뒤(이 문서와 같은 날).

## 4. 그 밖

- 두 앱 로그아웃: 요청서대로다(한쪽 로그아웃은 Keycloak 세션만 끝내고, 다른 앱 탭의 PMart 세션은 그 앱이 지운다). 공용 PC 안내 좋다.
- **auth-service 운영자 로그인을 없앴다**(같은 날 — `2026-11-13-clients-notice-auth-operator-login-removed.md`). 백오피스 옛 로그인 모드·운영자 계정 화면 코드는 다음 `gen:api` 에서 타입 오류가 나니 지워 달라.
- mfc_app: 범위를 정한 뒤 회신 기다린다.
