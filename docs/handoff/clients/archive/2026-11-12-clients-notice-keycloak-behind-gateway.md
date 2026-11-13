# 알림: Keycloak 로그인 화면이 게이트웨이 HTTPS 뒤로 옮겨졌다(ADR-0056 4단계) — 확인 스크립트 다시 돌려 달라
- **회신(2026-11-13)**: pmart-clients `7852899` — 4단계 뒤 실로그인 확인 통과(로그인 화면이 `https://192.168.0.40:8443` 으로 열림). PC 1 은 CA 미설치라 시험 Chrome 에서 인증서 검증을 끄고 확인했다(사람이 CA 설치된 PC 브라우저로 보는 확인은 남음).

- 작성: pmart-backend, 2026-11-12
- 대상: pmart-clients 백오피스(`web/backoffice` `lan`), mfc_app(앞으로의 직원 로그인), 시험 스크립트
- 앞 문서: 배포 회신 `pmart-clients docs/handoff/2026-11-12-backend-backoffice-employee-auth-deployed.md`(받음 — 4단계 시작 신호), `2026-11-12-clients-request-backoffice-sso-logout.md`

## 1. 바뀐 것(LAN, 2026-11-12 밤)

| 항목 | 전 | 후 |
|---|---|---|
| Keycloak 로그인 화면 주소 | `http://192.168.0.101:8180/realms/pmart-staff/…` | **`https://192.168.0.40:8443/iam/realms/pmart-staff/…`**(게이트웨이 HTTPS 경유) |
| employee-auth-service 콜백 | `http://192.168.0.40:8080/api/v1/employee-auth/logins/callback` | **`https://192.168.0.40:8443/api/v1/employee-auth/logins/callback`** |
| Keycloak 로그아웃(SSO) | — | `https://192.168.0.40:8443/iam/realms/pmart-staff/protocol/openid-connect/logout?…`(로그아웃 응답의 `idpLogoutUri`) |
| 백오피스가 부르는 주소 | 바뀌지 않음 — 로그인 시작·티켓 교환·갱신은 지금 그대로(`http://192.168.0.40:8080/api/v1/employee-auth/…`) | 같음 |

백오피스 코드는 바꿀 것이 없다(Keycloak 주소를 모르므로). 로그인 중 브라우저가 `https://192.168.0.40:8443` 을 지나간다는 것만 다르다.

## 2. 꼭 확인할 것 — 인증서

- 로그인 화면이 **LAN 사설 루트 CA**(`pmart-backend/deploy/lan/tls/pmart-lan-root-ca.crt`) 인증서다. 로그인하는 **브라우저가 이 CA 를 믿어야** 경고 없이 열린다.
  - PC 2~5 Windows: 설치돼 있다.
  - **PC 1: 아직 설치 안 됨**(관리자 확인 창을 원격으로 누를 수 없어서). PC 1 의 헤드리스 Chrome 시험은 인증서 오류가 난다 — 시험에만 `--ignore-certificate-errors` 를 쓰거나, PC 1 에 CA 를 설치한 뒤 돌려 달라(사용자 작업: PC 1 에서 `deploy/lan/lan.sh tls-trust pc1`).
  - 휴대폰(mfc_app Custom Tabs): 휴대폰에 CA 설치가 필요하다(사설 CA 계획 §6, T6).
- 백엔드가 PC 1 에서 확인할 때는 CA 파일을 직접 지정했다(브라우저 아님).

## 3. 백엔드가 확인한 것(게이트웨이 HTTPS 경유, `claude-dev`)

- 백오피스(SSO): 첫 로그인 비밀번호 → 새 탭은 비밀번호 없이 → 로그아웃 200 `idpLogoutUri` → Keycloak 이 `http://192.168.0.40/login` 으로 돌려보냄 → 다음 로그인은 다시 비밀번호.
- PDA(mfc): 매번 비밀번호, 콜백 → `pmart-mfc://login?ticket=…` → 토큰·티켓 재사용 401·갱신 200.
- 업무 API(`/centers`·`/approvals`·`/products`) 200.

## 4. 해 달라는 것

1. 배포 회신 §4 대로 같은 확인 스크립트를 다시 돌려 결과를 알려 달라(위 §2 인증서 주의).
2. SSO 로그아웃 변경(`2026-11-12-clients-request-backoffice-sso-logout.md`)을 반영해 달라 — 반영 전까지는 로그아웃해도 Keycloak 세션이 남아 같은 브라우저의 다음 로그인이 비밀번호 없이 된다.
3. 알아 둘 것: Keycloak 본인 계정 화면(`…/realms/pmart-staff/account`)은 게이트웨이가 중계하지 않아 열리지 않는다(관리 경로와 같이 막음). 비밀번호 설정·변경은 Keycloak 메일 링크로 한다. 백오피스에 "계정 관리" 링크를 넣지 않는다.
