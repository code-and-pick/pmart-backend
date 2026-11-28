# deploy/vault — 팀 공용 Vault 서버

비밀값의 정본(ADR-0100). LAN 서비스는 Spring Cloud Vault 로 자기 비밀을 여기서 직접 읽고, pmart-config 의 `{cipher}` 는 없앤다.
pc2 에서 운영 중: `https://192.168.0.34:8200`(TLS, 사설 CA). 서비스 연결(render.sh)·{cipher} 삭제는 PC 1 후속 — 아래 "{cipher} 제거".

바탕: `C:\development\VaultStudy\vault-config-study`(학습 프로젝트)의 LAB 08 서버 모드 — Raft 단일 노드, 정책, init 흐름.

## 파일

| 파일 | 내용 |
|---|---|
| `compose.yaml` | Vault 1.21.4 서버(Raft). 데이터 `pmart-vault_data`, 감사 로그 `pmart-vault_logs` 볼륨. 기본 `127.0.0.1:8200` |
| `config/vault.hcl` | 저장소·UI 설정 |
| `config/listener-local.hcl` · `listener-tls.hcl` | listener. `tls/vault.crt`·`vault.key` 가 있으면 TLS(LAN, 기본 0.0.0.0), 없으면 TLS 없이 127.0.0.1 만 — vault.sh 가 고르고, TLS 없이 LAN 에 여는 것은 막는다 |
| `tls/` | 서버 인증서·개인키 — **Git 제외**. PC 1 `deploy/lan/lan.sh tls-issue <pc>` 결과를 서버 PC 에 둔다 (TLS 모드는 아직 시험 안 함) |
| `seed.sh` · `seed-map.conf` | 비밀 파일 묶음 → Vault 이관. 표(경로·필드·원본 파일)만 있고 값은 없다 |
| `policies/*.hcl` | `pmart-read`(팀원·실행 스크립트, `pmart/shared/**` 읽기), `pmart-ops-read`(운영 비밀), `pmart-admin`(관리자), `config-server-read`(config-server — `config/` 읽기) |
| `verify/` | `{cipher}` 제거용: 검증(`verify-cipher.sh`)·이관(`migrate-cipher.sh`) — 아래 "{cipher} 제거" |
| `vault.sh` | 실행기: `up · status · init · unseal · setup · logs · down` |
| `.init/` | `init` 결과(unseal 키·root 토큰) — **Git 제외**, 옮긴 뒤 지운다 |

## 처음 띄우기

Git Bash 에서 실행한다(Windows Terminal 권장 — mintty 창은 숨김 입력이 안 될 수 있다). PowerShell 에서는 `bash vault.sh <명령>`.

```bash
cd deploy/vault
./vault.sh up        # sealed·미초기화 상태로 뜬다
./vault.sh init      # 최초 1회 — 키 3개(해제 2개)·root 토큰을 .init/ 파일로만 저장, 화면 출력 없음
                     #   (개수 바꾸기: VAULT_KEY_SHARES=5 VAULT_KEY_THRESHOLD=3 ./vault.sh init)
#  → 키 조각을 관리자마다 하나씩 안전한 곳(구글 드라이브, 본인만 접근)에 옮긴다
./vault.sh unseal    # 키를 숨김 입력으로 2번
./vault.sh setup     # root 토큰 숨김 입력 → pmart/ KV v2, 정책 3개, 감사 로그
```

그다음(root 토큰은 숨김 입력으로만 — 셸 기록에 남기지 않는다):
```bash
read -rsp "root 토큰: " VAULT_TOKEN; echo; export VAULT_TOKEN
docker compose exec -e VAULT_TOKEN vault vault token create -orphan -policy=pmart-admin -ttl=720h   # 관리자 토큰 — 나온 값을 바로 안전한 곳에
docker compose exec -e VAULT_TOKEN vault vault token revoke -self                                    # root 폐기
unset VAULT_TOKEN
```
1. **`-orphan` 필수** — 빼면 관리자 토큰이 root 의 자식이 되어 root 를 폐기할 때 **같이 무효가 된다**(2026-11-28 시험에서 확인).
2. root 는 필요하면 unseal 키로 다시 만들 수 있다(`vault operator generate-root`).
3. `.init/` 파일을 지운다.

시험 확인(2026-11-28, 시험용 프로젝트·포트로 실행 후 볼륨 삭제): up → init → unseal → setup(재실행 포함) → 정책(`pmart-read` 는 shared 읽기만, ops·쓰기 거부)
→ 재시작 후 sealed·데이터 유지 → 감사 로그 기록 → 관리자 토큰(-orphan)으로 root 폐기 후 쓰기·정책 수정·스냅샷.
시험하지 않은 것: 사람이 직접 치는 숨김 입력(시험은 파이프 입력), 실제 이름(`pmart-vault`)·8200 포트로 띄우기.

**재시작할 때마다** Vault 는 sealed 로 뜬다 → 키를 가진 관리자가 `./vault.sh unseal`.

### 관리자 토큰
- `vault token create -orphan -policy=pmart-admin -period=768h` 로 만든 **주기 토큰**. Vault 최대 TTL(768h=32일) 때문에 **32일 안에 한 번** 갱신해야 계속 쓴다:
  `read -rsp "관리자 토큰: " VAULT_TOKEN; echo; export VAULT_TOKEN; docker compose exec -e VAULT_TOKEN vault vault token renew; unset VAULT_TOKEN`
- 갱신을 놓치면 unseal 키로 root 를 다시 만들어(`vault operator generate-root`) 관리자 토큰을 새로 만든다.
- 갱신 명령(관리자·배포 토큰 둘 다): `./vault.sh renew` — 숨김 입력, 남은 기간을 보여 준다.
  PC 1(Vault CLI 없음)에서 배포 토큰: `read -rsp "배포 토큰: " T; echo; curl -s --cacert deploy/lan/tls/pmart-lan-root-ca.crt --ssl-revoke-best-effort -H "X-Vault-Token: $T" -X POST https://192.168.0.34:8200/v1/auth/token/renew-self -o /dev/null -w "%{http_code}
"; unset T` → 200

## 백업

```bash
./vault.sh snapshot            # 관리자 토큰 숨김 입력 → <pmart-project>/pmart-vault-backups/pmart-vault-<시각>.snap
./vault.sh snapshot <폴더>      # 다른 곳에
```
- 스냅샷은 Vault 가 암호화한다 — 복원(`vault operator raft snapshot restore`)하려면 unseal 키가 필요하다. **unseal 키와 다른 곳에** 보관한다.
- 비밀을 넣거나 바꾼 뒤에는 새로 뜬다.

## 운영 기록 (pc2, 2026-11-28)
- `pmart-vault` 를 pc2 127.0.0.1:8200(로컬 모드)에 띄움 → init(키 3개·해제 2개) → setup → 이관(아래 seed) → 관리자 토큰(-orphan, period 768h) 생성·확인 → **root 폐기**.
- unseal 키·관리자 토큰 파일은 사용자가 구글 드라이브(관리자 전용 폴더)로 옮겼고(크기 확인), PC 의 `.init/` 는 지웠다.

## 비밀 경로 (KV v2 마운트 `pmart/`)

| 경로 | 내용 | 읽기 정책 |
|---|---|---|
| `pmart/shared/infra` | DB·RAG DB·S3·Keycloak·Eureka·메일 계정 | `pmart-read` |
| `pmart/shared/signing/<발급자>` | auth·rider-auth·employee-auth 서명 개인키·kid | `pmart-read` |
| `pmart/shared/<서비스>` | 서비스별 키(토스·카카오·octomo·FCM·해시·CSRF·client secret 등) | `pmart-read` |
| `pmart/shared/clients` | 앱·시뮬레이터 env, google-services.json | `pmart-read` |
| `pmart/ops/*` | config-server 암호화 키·salt, 사설 CA 개인키, deploy key, 웹훅 secret, Slack | `pmart-ops-read` |

필드 이름은 지금 쓰는 환경변수 이름 그대로(`PMART_TOSS_SECRET_KEY` 등). 예:
`vault kv put pmart/shared/payment-service PMART_TOSS_SECRET_KEY=-` (값은 표준입력으로, 명령줄에 쓰지 않는다).

## 비밀 파일 묶음 이관 (seed)

```bash
./vault.sh seed <묶음 폴더>      # 예: /c/development/pmart-project/pmart-keys-pc6/pmart-keys-pc6
```
- 묶음 구조는 `pmart-keys-pc6`(저장소 이름부터 원래 위치를 따른 폴더). 무엇을 어디에 넣는지는 `seed-map.conf` 한 곳.
- 값은 파일 → 표준입력 → `vault kv put/patch FIELD=-` 로만 간다(화면·명령줄·임시 파일에 안 나온다). 끝나면 필드마다 원본과 **길이·sha256** 을 맞춰 본다.
- 다시 돌려도 같은 결과(경로마다 새 버전).
- 이관 표 두 개: `seed-map.conf`(외부 키·앱 — 필드 = 환경변수 이름), `seed-map-lan.conf`(LAN 전용 — 필드 = `.secrets` 파일 이름, 파일 바이트 그대로).
  `./vault.sh seed <폴더> [표]` — 표를 주지 않으면 `seed-map.conf`.
- 2026-11-28 pc2 실제 Vault 이관 기록
  - `pmart-keys-pc6` + `seed-map.conf`: 13개 경로·24개 필드 일치
  - `pmart-keys-lan`(PC 1 `.secrets` 98개, PC 1 이 sha256 98/98 확인) + `seed-map-lan.conf`: 11개 경로·83개 필드 일치
    (`shared/lan/{infra,signing,keycloak,service-clients,hash-keys,test-accounts,external}`, `ops/{config-server,tls,registry,alerts}`)
  - 넣지 않음(사용자 결정): `pmart-42b37` Firebase 키, 발급된 토큰(`customer-token`, `.kc-claude-dev-token`). 외부 키 11개는 두 묶음 sha256 이 같아 한 번만 넣었다.

## {cipher} 제거 — 서비스가 Vault 에서 직접 읽는다 (ADR-0100)

목적: pmart-config(GitHub)의 `{cipher}` 를 없애고 서비스 비밀은 Vault 에만 둔다. config-server 는 일반 설정만 내려준다.

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | `verify/verify-cipher.sh` — `{cipher}` 를 풀어 Vault 값과 sha256 비교(값 출력 없음) | 2026-11-28: 79개 중 75 일치, 4개는 형식 차이·원본 없음(이관에 지장 없음) |
| 2 | `verify/migrate-cipher.sh` — `{cipher}` 를 풀어 `config/<서비스>,lan` 에 Spring 속성 이름 그대로 넣고 sha256 확인. `./vault.sh setup` 을 먼저 돌린다 | 2026-11-28: 44경로·82필드({cipher} 79 + kid 3) 모두 일치 |
| 3 | 모든 앱에 Spring Cloud Vault(기본 꺼짐 — `common-observability` VaultDefaultsPostProcessor), 서비스별 정책·AppRole(`service-roles.sh`, PC IP 로 묶음), 배포 토큰(`pmart-deployer`) | 2026-11-28 완료(PR #564). 최신 pmart-config 기준 46경로·85필드, AppRole 53 |
| 4 | TLS 로 LAN 에(pc2 인증서) — PC 1·pc3·pc4·pc5 에서 접속 확인 | 2026-11-28 완료 |
| 5 | render.sh: Vault 환경변수(ADR-0100 결정 4)·CA 마운트·서비스별 secret-id 발급 → 이미지 재빌드 → 서비스 하나 먼저 → 전체 | 예정(PC 1) |
| 6 | **5 가 전체에서 동작한 뒤에만** pmart-config `{cipher}` 줄·signing.keys 목록·시험 사본 삭제, render.sh 암호화 단계 제거 | 예정(PC 1) |

- KV v2 마운트 **`config/`** = 서비스가 읽는 비밀만. 경로 `config/<spring.application.name>,<프로필>`, 필드 = Spring 속성 이름(목록은 `[n]`).
- **목록 주의**: Spring 은 목록을 우선순위가 높은 출처 하나에서 통째로 받는다. `signing.keys[0]` 는 `private-key` 와 `kid` 를 **둘 다** Vault 에 둔다(Git 쪽 목록은 5단계에서 지운다).
- 정책 `svc-<앱>`(자기 경로만) + AppRole `svc-<앱>`(토큰 1h·최대 24h, secret-id 90일, placement.conf 의 PC IP 로 묶음 — pc2 앱은 Docker 대역도). 정책·AppRole `config-server-read`/`config-server` 는 복합 백엔드 안을 버리면서 쓰지 않는다(정리 대상).
- 같은 비밀이 `pmart/shared/lan`(파일 형식)과 `config/`(속성 형식) 두 곳에 있다. **서비스 비밀의 정본은 `config/`**, `pmart/shared/lan` 에는 config-server 를 거치지 않는 것(postgres·Keycloak·config-server 자신 등)만 남기도록 정리한다(6단계 뒤).
- 목록 추출 `verify/list-ciphers.py`, 복호화 `verify/CipherFingerprint.java`(config-server 와 같은 EncryptorFactory, Gradle 캐시 jar).

## 운영 절차

### pc2 재부팅·Vault 재시작
1. Docker Desktop 이 뜨면 `pmart-vault` 컨테이너는 `restart: unless-stopped` 로 저절로 뜬다 — **sealed 상태**.
2. unseal 키를 가진 관리자가 `./vault.sh unseal`(키 2개, 숨김 입력) → `./vault.sh status` 로 `unsealed` 확인.
3. **unseal 전에는 LAN 앱의 새 기동·재시작이 실패**한다(fail-fast). 이미 떠 있는 앱은 영향 없음. LAN 전체를 다시 띄울 때는 **Vault unseal 을 맨 먼저**.
4. 컨테이너를 다시 만들 때(`./vault.sh up` — 설정·인증서 바꾼 뒤)도 sealed 로 뜬다 → 2.

### 정기 작업
| 언제 | 할 일 | 명령 |
|---|---|---|
| 한 달에 한 번(32일 안) | 관리자·배포 토큰 갱신 | `./vault.sh renew`(둘 다) — PC 1 은 위 "관리자 토큰" 의 curl |
| 비밀·역할을 바꾼 뒤 | 스냅샷 → unseal 키와 다른 드라이브 폴더 | `./vault.sh snapshot` |
| 90일 안에 한 번 | LAN 재배포(서비스 secret-id 가 90일에 만료 — 재배포가 새로 발급) | PC 1 render·deploy |
| 1년(2027-11-28 전) | pc2 인증서 갱신 | PC 1 `lan.sh tls-issue pc2` → `tls/vault.crt·vault.key` 교체 → `./vault.sh up` → unseal |

### 비밀 바꾸기
- 서비스 비밀: `config/<앱>,lan` 의 속성을 바꾼다(관리자 토큰, 값은 표준입력 `필드=-`) → **그 앱 재시작**(부팅 때 읽는다) → 스냅샷.
- 같은 값이 `pmart/shared/lan/…`(파일 형식)에도 있으면 같이 바꾼다(정리 전까지).
- 키가 샜으면 발급처에서 값 자체를 바꾸고 Vault 를 고친다. 감사 로그(`/vault/logs/audit.log`)로 누가 읽었는지 본다.

### 새 서비스
1. placement.conf 에 넣은 뒤 `./service-roles.sh <그 placement.conf>`(새 AppRole).
2. 비밀이 있으면 `config/<앱>,lan` 에 속성 이름으로 넣는다({cipher} 로 더하지 않는다).
3. 스냅샷.

## 규칙
- 토큰·unseal 키·비밀값을 Git·문서·일지·채팅에 넣지 않는다. 확인은 이름·글자 수로만.
- `docker compose down -v` 금지 — 데이터 볼륨이 지워지면 모든 비밀이 사라진다. 백업은 `vault operator raft snapshot save`(관리자 토큰).
- TLS 를 켜기 전에는 `VAULT_BIND` 를 127.0.0.1 밖으로 바꾸지 않는다.

## 남은 결정 (사용자)
결정됨(2026-11-28, ADR-0100): 서버 pc2 유지, TLS(사설 CA), {cipher} 는 없애고 서비스가 직접 읽음(서비스별 AppRole).
1. 팀원 로그인 방식(추천: GitHub 조직 `code-and-pick` 인증 — 팀별 정책)
2. HashiCorp Vault(BUSL 1.1) 유지 또는 OpenBao
3. `pmart/shared/lan` 정리 범위(config-server 를 거치지 않는 것만 남기기), 안 쓰는 `config-server-read`·AppRole `config-server` 지우기
