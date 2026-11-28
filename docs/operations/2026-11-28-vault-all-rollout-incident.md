# Vault 전체 전환 실패와 되돌림 (2026-11-28, PC 1)

- 관련: ADR-0100(LAN 비밀은 Vault, 결정 5 AppRole·결정 9 전환 순서), PR #564·#566·#567·#568, `deploy/lan/vault-apps.conf`
- 상태: **되돌림 완료 — 지금 pc2 앱 20개만 Vault, pc3·pc4·pc5 앱은 기존 `{cipher}`. `{cipher}` 삭제 보류.** 고칠 길(A/B/C)은 사용자 결정 보류(관리자 토큰을 데스크톱에서 넣을 때 함께).

## 한 줄 요약
`vault-apps.conf` 를 `all` 로 바꿔 전 PC 에 배포하자 **pc3·pc4·pc5 의 앱이 Vault 로그인에 실패해 재시작을 반복**했다. pc2 Docker Desktop 이 Vault 포트(8200)를 공개하면서 **요청의 출발지 IP 를 Docker 게이트웨이(172.20.0.1)로 바꿔**, AppRole 의 "그 앱 PC IP/32" 묶음이 다른 PC 앱에 맞지 않았다. pc2 앱 20개만 Vault 로 남기고 나머지를 `{cipher}` 로 다시 배포해 약 10분 만에 복구했다.

## 시간 순서 (KST)
| 시각 | 한 일 | 결과 |
|---|---|---|
| ~21:41 | payment-service 하나만 Vault(첫 앱) 배포 | 정상 — 감사 로그 12:41:41Z(UTC) `svc-payment-service` 로그인·`config/data/payment-service,lan` 읽음. TEST_AUTO 주문 1건 확정 |
| 22:0x | 앱 54개 이미지 재빌드(3c8b26fed, Spring Cloud Vault 5.0.3) | 54개 push |
| 22:1x | `vault-apps.conf` = `all`(fba0acfb3) → render 확인 | **Vault 0개로 렌더** — 버그 ① |
| 22:1x | render.sh 수정(fc231ff58) → 다시 render | 53개(54 − config-server). pc2 20·pc3 14·pc4 9·pc5 10, pc1 0 |
| 22:20 | `lan.sh deploy all` 시작 | |
| 22:31 | 배포 끝(종료 코드 0) → 상태 확인 | **pc4 api-gateway·eureka-server·auth-service·approval·audit·backoffice-bff·case·center-master·notification, pc3 cdc-inventory-service 재시작 반복** — 장애 ② |
| 22:3x | 원인 확인(eureka-server 로그) → `vault-apps.conf` = pc2 앱 20개(1175a7666) | |
| 22:32~22:41 | pc4 → pc3 → pc5 를 Vault 없이 다시 배포 | 전 PC 101 컨테이너 running·재시작 0 |
| 22:4x | 확인: 게이트웨이 https health 200, 고객 토큰 → 게이트웨이 `/me/addresses` 200 | 복구 |

**영향**: 약 22:2x~22:41 동안 pc4 의 게이트웨이·인증·eureka 등이 재시작을 반복 — LAN 의 앱·백오피스 로그인과 API 가 불안정했다. 데이터 변경·유실은 없다(`{cipher}` 를 지우지 않아 되돌리기가 설정 한 줄로 됐다).

## 원인
### ① render.sh 의 `all` 판정 버그 (#566 — 고침 fc231ff58)
- `vault-apps.conf` 를 읽을 때 주석 줄이 빈 칸을 남겨 `VAULT_APPS` 가 `"  all  "`(앞뒤 두 칸)이 되었고, 판정은 `[ "$VAULT_APPS" = " all " ]` 로 정확히 비교해 빗나갔다. 앱 이름(payment-service)은 포함 여부(`*" 앱 "*`)로 봐서 드러나지 않았다.
- 수정: `VAULT_APPS=" $(echo $VAULT_APPS) "` — 앞뒤를 한 칸으로.
- render 단계에서 "PC 별 Vault 앱 수"를 세어 보고 배포 전에 잡았다.

### ② AppRole CIDR 묶음과 Docker Desktop 포트 공개 (장애)
- 로그(pc4 eureka-server):
  ```
  VaultLoginException: Cannot login using AppRole: source address "172.20.0.1" unauthorized by CIDR restrictions on the role
  400 Bad Request on POST "https://192.168.0.34:8200/v1/auth/approle/login"
  ```
- ADR-0100 결정 5: secret-id·토큰을 **placement.conf 의 그 앱 PC IP/32** 로 묶는다(pc2 앱만 Docker 대역도 허용).
- 그런데 Vault 는 pc2 의 Docker Desktop 컨테이너로 돌고 8200 을 포트 공개로 연다. Docker Desktop(Windows)의 포트 공개는 들어온 연결을 프록시해 **Vault 가 보는 출발지가 원래 PC IP 가 아니라 Docker 게이트웨이(172.20.0.1)** 가 된다.
- 그래서 pc2 앱(Docker 대역 허용)만 로그인되고, pc3·pc4·pc5 앱은 모두 거절 → `spring.cloud.vault.fail-fast=true` 라 기동 실패 → `restart` 로 반복.

### 왜 미리 못 잡았나
- 첫 앱을 **pc2 앱(payment-service)** 으로 골랐다 — pc2 는 Docker 대역 허용이라 이 문제를 드러낼 수 없는 PC 였다.
- Vault 쪽 시험은 pc2 안 로그인과 다른 PC 의 **연결(health 200)** 까지였고, 다른 PC 에서의 **AppRole 로그인**은 해 보지 않았다(health 는 인증이 없다).
- 전체를 한 번에 넓혔다 — 다른 PC 앱 하나로 먼저 확인하는 단계가 없었다.

## 되돌림
- `deploy/lan/vault-apps.conf` 를 pc2 앱 20개로(1175a7666) — pc2 는 그대로 Vault, 나머지 PC 렌더에서 Vault 환경변수가 빠진다.
- `lan.sh deploy pc4` → `pc3` → `pc5`(게이트웨이·인증이 있는 pc4 먼저).
- `{cipher}` 가 pmart-config 에 그대로 있어 Vault 없는 앱은 config-server 로 전과 같이 비밀을 받았다. **`{cipher}` 를 먼저 지웠다면 되돌릴 수 없었다** — ADR-0100 결정 9 의 "전체가 동작한 뒤에만 ⑤" 순서가 지켜준 것.

## 지금 상태 (2026-11-28 22:41 이후)
- Vault: pc2 앱 20개(payment·order·claim·cart·commerce·RAG 새 서비스 등) — 감사 로그로 로그인·읽기 확인.
- `{cipher}`: pc3·pc4·pc5 앱 33개. config-server 는 원래 Vault 대상이 아니다(render 가 뺀다).
- 이미지: 앱 54개 모두 3c8b26fed(Vault 클라이언트 포함, 기본 꺼짐).
- 배포 토큰 파일 `deploy/lan/.secrets/vault-deployer-token` 은 PC 1 에 계속 둔다(#568) — `vault-apps.conf` 에 앱이 있으면 모든 `lan.sh deploy` 가 쓴다.

## 고칠 길 (사용자 결정 보류 — 관리자 토큰을 데스크톱에서 넣을 때 함께)
| 안 | 내용 | 득실 |
|---|---|---|
| A | AppRole CIDR 를 풀거나 Docker 게이트웨이 대역까지 넓힘(`service-roles.sh` 다시) | 바로 됨. 묶음의 뜻이 약해짐 — secret-id 가 새면 어느 PC 에서든 씀 |
| B | Vault 를 pc2 Windows 에 직접(vault.exe 서비스 + 스냅샷 복원) | 출발지 IP 가 보여 결정 5 의 뜻대로. 운영 방식이 바뀜 |
| C | 인증서 로그인(TLS 클라이언트 인증서) | IP 에 기대지 않음. 인증서 발급·배포 일이 늘어남 |

pc2 Vault 세션 추천: A 로 먼저 풀고 나중에 B.

## 다시 넓힐 때의 순서 (이번 교훈)
1. 고칠 길을 적용한 뒤 **pc2 가 아닌 PC 의 앱 하나**(예: pc4 의 영향이 작은 앱)만 `vault-apps.conf` 에 더해 배포 → 그 앱의 실제 AppRole 로그인·기동 확인.
2. PC 별로 넓힘(pc5 → pc3 → pc4, 게이트웨이·인증이 있는 pc4 는 마지막) — 한 PC 씩 상태·재시작 수를 본다.
3. 전체가 Vault 로 동작한 뒤에만 `{cipher}` 삭제(결정 9 ⑤).
4. 배포 전 render 에서 PC 별 Vault 앱 수를 센다(이번 ① 을 잡은 방법).
