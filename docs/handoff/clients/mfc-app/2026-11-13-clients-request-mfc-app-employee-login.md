# mfc_app(PDA) 직원 로그인 — employee-auth-service(ADR-0056 6단계)

- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients `apps/mfc_app`(Android, Flutter)
- 근거: ADR-0056 "옮기는 순서" 6단계(mfc_app 로그인 새로), 결정 14 ③(PDA 무활동 30분), 계약 `contracts/openapi/employee-auth-service.yaml`, 설계 `docs/architecture/auth/employee-auth-structure.md`.
- 앞 문서: `../archive/2026-11-12-clients-request-backoffice-employee-auth-login.md`(백오피스 — 같은 흐름. 그 문서 §8 "PDA 는 따로 요청한다"의 그 요청)
- 지금 mfc_app: 직원 로그인 API 가 없어 토큰을 받지 못한다(`providers.dart` BR-02 주석 — 갱신은 늘 세션 만료). **이 요청으로 그 빈자리를 채운다.**
- 백엔드 상태: **준비 끝, LAN 에서 돈다.** employee-auth-service 의 앱 `mfc` 가 등록돼 있고(복귀 `pmart-mfc://login`, SSO 없음, 무활동 30분), 2026-11-12 게이트웨이 HTTPS 로 PDA 흐름(Keycloak 화면 → 티켓 → 딥링크 복귀 → 교환)을 스크립트로 확인했다. 토큰 모양은 예전 운영자 토큰과 같아 **업무 API·계약은 바뀌지 않는다**(`typ=OPERATOR`, `sub=OP-…`, `roles`, `centers`).

## 1. 흐름 (백오피스와 같고, 브라우저 대신 Custom Tab + 딥링크)

```
1) 앱: code_verifier(43~128자, [A-Za-z0-9-._~]) 만들기 → 메모리(또는 보안 저장소)에 잠깐 보관
       code_challenge = BASE64URL(SHA-256(code_verifier))
       deviceId = 이 PDA 의 기기 ID(지금 DeviceIdProvider 값 그대로)
2) Custom Tab(Chrome Custom Tabs)으로 연다:
   GET <게이트웨이>/api/v1/employee-auth/logins?app=mfc&deviceId=<deviceId>&codeChallenge=<challenge>&returnUri=pmart-mfc%3A%2F%2Flogin
   → Keycloak 로그인 화면(https://192.168.0.40:8443/iam/…) → 아이디·비밀번호 → employee-auth 콜백 → 302 pmart-mfc://login?ticket=…
3) 딥링크로 앱 복귀:  pmart-mfc://login?ticket=<티켓>   (성공)
                     pmart-mfc://login?error=<코드>    (실패 — §4)
4) 앱: POST <게이트웨이>/api/v1/employee-auth/tokens
      헤더 Idempotency-Key: <uuid>, X-Source-System: mfc-app, X-Device-Id: <deviceId>
      본문 {"ticket": "<티켓>", "codeVerifier": "<1)의 verifier>"}
   → 200 {operatorId, roles, centers, tokens:{accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, sessionId}}
5) verifier 를 지운다. 토큰은 지금처럼 TokenStore(보안 저장소)에.
```

- 로그인은 **브라우저(Custom Tab) 이동**이어야 한다 — 302 를 따라 Keycloak 화면으로 간다. WebView 는 쓰지 않는다(Keycloak 비밀번호 입력을 앱이 보지 않게).
- 티켓은 **60초·1회용**, 로그인을 시작한 **deviceId + code_verifier** 로만 바뀐다. 틀리게 한 번 쓰면 버려진다(다시 로그인).
- **공용 PDA 라 SSO 가 없다**: 로그인할 때마다 Keycloak 이 비밀번호를 묻고(`prompt=login`), Keycloak 세션은 로그인 직후 끝난다. 다음 사람이 Custom Tab 을 열어도 앞 사람으로 로그인되지 않는다.
- `roles` 가 비어 있으면 권한 승인 대기(업무 API 403) — "권한 승인 대기" 안내. 새 직원은 인사팀이 등록하면 소속 센터의 `MFC_WORKER` 가 자동 승인된다.

## 2. 값 (LAN)

| 항목 | 값 |
|---|---|
| 앱 이름 | `app=mfc` |
| 복귀 주소 | `pmart-mfc://login` 하나(다른 값은 400). Android 인텐트 필터로 이 스킴·호스트를 받는다 |
| `X-Source-System` | `mfc-app`(지금 값 그대로) |
| 게이트웨이 | 지금 mfc_app 이 쓰는 주소 그대로(`http://192.168.0.40:8080` 또는 `https://192.168.0.40:8443`). Keycloak 화면은 늘 `https://192.168.0.40:8443/iam`(아래 §5) |
| 갱신 | `POST /api/v1/employee-auth/tokens/refresh` 본문 `{"refreshToken"}`, 헤더 `Idempotency-Key`·`X-Source-System`·`X-Device-Id`(로그인한 deviceId) → `TokenPair`. 지금 `refresher: (_) async => null` 자리에 넣는다 |
| 로그아웃 | `POST /api/v1/employee-auth/logouts`(Bearer, 헤더 `Idempotency-Key`·`X-Source-System`, 본문 `{"allDevices": false}`) → 200 `EmployeeLogoutResult`(PDA 는 `idpLogoutUri` 가 비어 있다 — Keycloak 세션이 이미 없다). 그 뒤 토큰 삭제 |
| 현재 세션 | `GET /api/v1/employee-auth/sessions/current`(Bearer) |
| access token | 15분 — 만료 전 갱신 |
| 세션 | **무활동 30분**(마지막 갱신에서 30분 안에 갱신이 없으면 끝) + 시작에서 최대 12시간. 끝나면 다시 로그인 |
| refresh token | **한 번만**. 갱신 응답의 새 값으로 바꿔 둔다. 다시 쓰면 세션 종료(`TOKEN_REUSED`) — 동시에 두 번 갱신하지 않게 한 곳에서 |

## 3. 화면

- 로그인 화면: "직원 계정으로 로그인" 버튼 하나(아이디·비밀번호 칸 없음 — Keycloak 화면에서 입력).
- 로그인 뒤: 지금 작업 화면. 센터는 토큰 `centers`(MFC 작업자는 보통 하나).
- 로그아웃 버튼(교대·자리 비움). 30분 무활동이면 다음 요청에서 갱신이 401 → 로그인 화면으로.
- 정지·퇴사된 직원은 이미 받은 토큰도 즉시 401 `SUBJECT_BLOCKED` 가 된다 → 로그인 화면으로("이용할 수 없는 계정").

## 4. 오류

| 어디서 | code | 안내 |
|---|---|---|
| 복귀 `?error=` | `EMPLOYEE_NOT_REGISTERED` | PMart 직원으로 등록되지 않은 계정 — 인사팀 문의 |
| | `ACCOUNT_NOT_AVAILABLE` | 정지·퇴사(또는 근무 시작일 전) |
| | `IDP_ERROR` | 로그인을 취소했거나 Keycloak 오류 — 다시 |
| | `IDP_REJECTED`·`IDP_UNAVAILABLE` | 일시 오류 — 잠시 뒤 다시 |
| 티켓 교환 | 401 `TICKET_INVALID`(없음·만료·사용됨·다른 기기·verifier 불일치 — 이유를 나누지 않음) | 다시 로그인 |
| | 403 `ACCOUNT_NOT_AVAILABLE` | 위와 같음 |
| Custom Tab 에서 400 `LOGIN_STATE_INVALID` | 로그인 화면을 너무 오래 두었거나(10분) 뒤로 가기로 다시 제출 — 앱에서 다시 시작 |

## 5. PDA 준비(LAN)

- Keycloak 화면이 `https://192.168.0.40:8443` 이라 **PDA 에 LAN 루트 CA 를 설치**해야 Custom Tab 이 경고 없이 연다(Android 설정 → 보안 → 인증서 설치 → CA 인증서, 파일 `deploy/lan/tls/pmart-lan-root-ca.crt` — 백엔드가 전달). Chrome 은 사용자가 설치한 CA 를 브라우징에 쓴다. 앱의 API 호출(Dio)도 `https://…:8443` 을 쓰면 network-security-config 에 사용자 CA 허용이 필요하다(`http://…:8080` 이면 필요 없음 — 지금처럼).
- 시험 계정: 인사팀이 등록하면 비밀번호 설정 메일이 사내 메일로 간다(웹메일 `https://192.168.0.101:8190`). 백엔드 시험 계정 `claude-mfc-wrk`(MFC_WORKER, MFC-SEED-01)도 있다 — Keycloak 비밀번호가 필요하면 백엔드에 요청.

## 6. 회신 부탁

1. 흐름·값에서 막히는 것(Custom Tab 라이브러리, 딥링크 받기, 갱신 동시성).
2. 게이트웨이를 `http://…:8080` 으로 둘지 `https://…:8443` 로 옮길지(후자면 앱 network-security-config 필요).
3. 구현 뒤 실기기 확인(로그인·갱신·30분 무활동·로그아웃·정지 즉시 401) 결과.
