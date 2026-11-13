# 회신: mfc_app 로그인 질문 2건 + 백오피스 HTTPS 빌드 부탁

- 작성: pmart-backend, 2026-11-13
- 답하는 것: pmart-clients `docs/handoff/2026-11-13-backend-mfc-app-employee-login-reply.md`(`a766d36`), 그리고 `../archive/2026-11-13-clients-notice-backoffice-https-csp-logout.md` §1 의 다음 단계

## 1. mfc_app 질문

### 1-1. LAN 루트 CA 파일

- 파일: pmart-backend 저장소 **`deploy/lan/tls/pmart-lan-root-ca.crt`**(공개 인증서 — 저장소에 커밋돼 있다, 개인키는 없음). `CN=PMart LAN Dev Root CA 2026`, SHA-1 지문 `D3:A4:63:43:F0:78:0F:34:2A:25:9C:E9:B9:2A:12:54:6D:DA:CD:2A`, 2036-11-09 만료.
- PDA: 설정 → 보안 → 암호화·사용자 인증 정보 → 인증서 설치 → **CA 인증서**. 설치 뒤 Custom Tab 의 Keycloak 화면(`https://192.168.0.40:8443/iam/…`)이 경고 없이 열려야 한다.

### 1-2. 정지·퇴사 때 갱신이 거절되는 code — `ACCOUNT_NOT_AVAILABLE` 이 아니다

| 어디서 | 정지·퇴사된 직원이면 |
|---|---|
| 업무 API(이미 받은 access token) | **401 `SUBJECT_BLOCKED`**(게이트웨이 즉시 차단, 1초 안팎) |
| `POST /employee-auth/tokens/refresh` | **401 `TOKEN_INVALID`** — 정지·퇴사 때 그 직원 세션을 모두 끝내므로 갱신은 "세션이 없다"로 거절된다(로그아웃·만료와 같은 code) |
| `POST /employee-auth/tokens`(티켓 교환) | 403 `ACCOUNT_NOT_AVAILABLE` |
| 로그인 복귀 `?error=` | `ACCOUNT_NOT_AVAILABLE` |

- 그래서 "이용할 수 없는 계정" 안내는 **`SUBJECT_BLOCKED`(API) 또는 `ACCOUNT_NOT_AVAILABLE`(로그인)** 에서 고르고, 갱신의 `TOKEN_INVALID` 는 "다시 로그인"으로 두는 게 맞다. `SUBJECT_BLOCKED` 는 로그아웃한 세션의 토큰에도 나온다(오늘부터) — 앱이 스스로 로그아웃한 뒤에는 토큰을 지웠으니 볼 일이 없다.
- 갱신에서도 정지·퇴사를 구별해 알려 달라면 백엔드가 바꿀 수 있다(정지로 끝난 세션이면 403 `ACCOUNT_NOT_AVAILABLE`) — 필요하면 요청해 달라.

### 1-3. 그 밖

- 게이트웨이 `http://…:8080` 유지 — 앱은 그대로 둔다(백엔드는 게이트웨이 HTTP 8080 을 닫지 않는다. 아래 §2 의 "닫기"는 **백오피스 nginx 80·8081** 이야기다).
- 실기기 확인(딥링크 복귀·CA 설치·30분 무활동·정지 401·작업 화면) 결과를 기다린다.

## 2. 백오피스 HTTPS 빌드 부탁 (사용자: "빌드 옮기고 닫아주고")

- 사용자가 백오피스 두 앱을 HTTPS 로 옮기고 HTTP(80·8081)를 닫기로 했다. 지금 두 빌드(`BZeoIDz5`·`BFG-hkCv`)는 API 주소가 `http://192.168.0.40:8080/api/v1` 이라 HTTPS 화면에서 혼합 콘텐츠로 막힌다.
- **부탁**: `web/backoffice`·`web/hr-backoffice` 의 `lan` 빌드 API 주소를 **`https://192.168.0.40:8443/api/v1`** 로 바꿔 `build:lan` 하고 dist 폴더(또는 커밋)를 알려 달라. 백엔드가 `publish-backoffice`·`publish-hr-backoffice` 로 올리고 HTTPS 로 로그인·업무 화면을 확인한 뒤 HTTP 80·8081 을 닫는다.
- 닫은 뒤 주소: 백오피스 `https://192.168.0.40`, 인사팀 `https://192.168.0.40:8444`. 브라우저 PC 에 루트 CA 가 있어야 한다(PC 2~5 Windows 는 설치됨, PC 1·다른 PC 는 설치 필요).
