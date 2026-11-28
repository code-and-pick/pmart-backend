# Vault 구성 — Eum 과의 비교와 config-server 방식으로 바꾸는 계획 (2026-11-28, PC 1)

- 관련: ADR-0027·0029(config-server·pmart-config), ADR-0100(LAN 비밀은 Vault), 장애 기록 `2026-11-28-vault-all-rollout-incident.md`
- **정정(2026-11-29)**: 처음 판에 "Eum 의 config-server 는 Git + Vault 복합 백엔드"라고 썼으나 **틀렸다** — Eum config-server 는 `SPRING_PROFILES_ACTIVE: vault`, Git 주소 줄은 compose 에서 주석 처리돼 **Vault 만** 백엔드로 쓴다. 아래 §2·§3 을 고쳤다. 사용자의 "1번" 결정은 이 틀린 설명을 듣고 내려졌다 — 결정 내용(config-server 가 Git + Vault 를 함께 보고 서비스는 Vault 를 모른다)은 Eum 과 같은 구조가 아니라 **우리 쪽 설계**다(정정 뒤 사용자에게 알림, 2026-11-29).
- 상태: **사용자 결정 2026-11-28 — "1번"(config-server 만 Vault 를 보고 서비스는 Vault 를 모른다).** 새 ADR(ADR-0100 결정 3~6 대체)은 아직 쓰지 않았다. LAN 은 아직 바꾸지 않았다(pc2 앱 20개 Vault 직접, 나머지 `{cipher}`, `{cipher}` 삭제 보류).
- 사용자 말: "eum 이랑 동일한 구조로 가야할것같아" → (두 부분 중 무엇을 따를지 묻자) "응 1번이야".

## 1. 우리 config-server 는 원래 Git 만 본다 (지금 구조)
- 설정의 정본: GitHub private `code-and-pick/pmart-config`(PC 1 로컬 사본 `deploy/lan/.config-repo`).
- config-server(PC 4)가 배포 키(SSH)로 이 저장소를 읽어 서비스에 `application.yml`·`<앱>.yml`·`<앱>-lan.yml` 을 내려준다.
- 비밀값은 같은 Git 파일 안의 `{cipher}…` 암호문. config-server 가 자기 `encrypt.key`·salt 로 풀어 평문으로 내려준다(server-side decryption).
- 바뀐 설정 반영: pmart-config push → 웹훅 → smee → config-relay → config-server `/monitor` → Spring Cloud Bus → 서비스가 다시 받음(ADR-0033).
- ADR-0100 은 여기에 **서비스가 Vault 를 직접 읽는 길**을 더했다(서비스마다 AppRole, 자기 경로만 읽기, secret-id·토큰을 그 PC IP/32 로 묶음). config-server 는 바꾸지 않았다.

## 2. Eum(`C:\development\pmart-project\Eum-main`, PC 1 에만 있음, Git 저장소 아님)의 Vault 구성
확인한 파일: `eum_backend/docker-compose/docker-compose.yml`, `docker-compose/vault/config/config.hcl`, `docker-compose/vault/vault-init.sh`, 서비스·configserver 의 `application.yml`.

- **Vault 서버**: 서비스들과 같은 docker-compose·같은 Docker 네트워크의 컨테이너 `dseum-vault`(hashicorp/vault, 8200, raft 저장소, `tls_disable = "true"` — HTTP).
- **초기화**(`vault-init.sh`): 루트 토큰 `eum-root` 를 만들고 `secret/` 에 kv-v2 를 켠 뒤, 앱별 JSON 파일을 `vault kv put secret/<앱>` 으로 넣는다(`secret/application`, `secret/dseum-order`, `secret/dseum-payment`, `secret/dseum-gateway`, `secret/authserver`, `secret/dseum-rag` … — RAG 의 외부 모델 API 키도 여기에 patch).
- **일반 설정까지 전부 Vault 에** — 앱별 JSON 에 비밀만이 아니라 일반 설정도 들어 있다(예: 주문 서비스 `ddl-auto` 는 "vault config(dseum-order.json)"에서 정한다는 주석).
- **두 길이 같이 있다(같은 데이터)**
  1. **config-server 는 Vault 만 본다** — compose 의 configserver 가 `SPRING_PROFILES_ACTIVE: vault`, `SPRING_CLOUD_CONFIG_SERVER_GIT_URI` 등 Git 줄은 주석. `configserver/application.yml` 의 `spring.cloud.config.server.vault`(TOKEN `eum-root`). config-server 자신의 설정도 `optional:vault://` 로 Vault 에서.
  2. **서비스도 Vault 를 직접 읽는다** — 각 서비스 `spring.config.import` 에 `optional:vault://` 와 `optional:configserver:` 가 함께(순서는 서비스마다 다름 — orderserver 는 vault 먼저, 몇 서비스는 configserver 먼저), `authentication: TOKEN`, 토큰 기본값이 루트 토큰 `eum-root`(`${VAULT_TOKEN:eum-root}`), 백엔드 `secret`. compose 에서 `VAULT_TOKEN` 을 넘기는 곳이 19곳.
  - 그래서 서비스는 같은 `secret/<앱>` 데이터를 직접·config-server 경유 두 번 받는다. 기능상 한 길이면 되고, config-server 는 Vault 값을 그대로 넘겨줄 뿐이다(설정 저장소 Git 도, Vault 변경 웹훅도 없음). 이유는 코드에 없다 — 처음 config-server 를 두고 나중에 Vault 를 붙이며 정리하지 않은 흔적으로 보인다(추정).

## 3. 비교
| 항목 | Eum | 우리 (ADR-0100 지금) |
|---|---|---|
| Vault 위치 | 서비스와 **같은 compose·같은 Docker 네트워크** | pc2 에 따로, 다른 PC 서비스가 **LAN 너머로** 접속(Docker Desktop 포트 공개 8200) |
| 통신 | HTTP | HTTPS(사설 CA) |
| config-server 백엔드 | **Vault 만**(Git 없음 — 일반 설정도 Vault) | Git 만(일반 설정 + `{cipher}`) |
| 누가 Vault 를 읽나 | config-server(Vault 만) **와** 서비스(직접) 둘 다 — 같은 데이터 | 서비스(직접)만 |
| 서비스 인증 | TOKEN — **모든 서비스가 루트 토큰 하나**(기본값이 코드에 있음) | 서비스마다 AppRole(자기 경로만 읽기) + PC IP/32 묶음 |
| 저장 경로 | `secret/<앱>`(JSON 파일을 넣음) | `config/<앱>,lan`(Spring 속성 이름 그대로) |
| 최소 권한 | 없음 — 토큰 하나로 Vault 전체 읽기·쓰기 | 있음 — 한 서비스 자격이 새도 그 서비스 비밀만 |
| 오늘 같은 장애 | 생길 수 없음(같은 네트워크, IP 묶음 없음) | 생김 — Docker Desktop 이 출발지 IP 를 172.20.0.1 로 바꿔 pc3·4·5 앱 로그인 거절 |

정리: Eum 은 **"모두 한 네트워크에 있다"는 조건 덕분에 단순하고**, 대신 보안이 약하다. 우리는 서비스가 PC 5대에 흩어져 있어 Eum 의 2번(서비스 직접 + 공용 토큰)은 조건이 맞지 않고 최소 권한도 잃는다. **1번(config-server 만 Vault 를 보고 서비스는 Vault 를 모른다)은 우리 구조에도 맞는다** — Vault 에 접속하는 곳이 config-server 하나가 된다. 다만 Eum 처럼 Vault **만** 보는 것이 아니라, 일반 설정은 지금처럼 Git(pmart-config — 이력·PR 검토·웹훅 → Bus 자동 반영)에 두고 비밀만 Vault 에서 함께 받는다(Git + Vault 복합) — 이 점은 Eum 과 다르다.

## 4. 결정: 1번만 따른다
- **config-server 가 Git + Vault 복합 백엔드**로 비밀을 합쳐 내려준다. 서비스는 지금처럼 config-server 에서 설정을 받을 뿐 Vault 를 모른다.
- **2번(서비스 직접 읽기·공용 루트 토큰)은 따르지 않는다.**

### 얻는 것
- 오늘의 장애 원인이 사라진다 — 다른 PC 서비스 50여 개가 각자 Vault 에 로그인하지 않는다.
- 서비스 코드·이미지는 그대로(Vault 클라이언트는 이미지에 있으나 기본 꺼짐). 서비스별 Vault 환경변수·CA 마운트·secret-id 발급이 필요 없다.
- 비밀의 정본이 Git(`{cipher}`)이 아니라 Vault 한 곳이 된다 — 바꾸고 돌리기(회전)가 쉬워진다.
- 지금 Vault 데이터를 그대로 쓸 수 있다 — Spring Cloud Config 의 Vault 백엔드가 읽는 `{백엔드}/{앱},{프로필}` 이 지금 경로 `config/<앱>,lan` 과 같은 모양. config-server 용 정책 `config-server-read` 는 pc2 Vault 세션이 처음에 만들어 둔 것이 있다.

### 잃는 것
- 서비스별 최소 권한. config-server 가 모든 비밀을 읽어 Basic 인증 하나로 내려준다 — ADR-0027 의 약점이 그대로 남는다(지금 `{cipher}` 방식과 같은 수준).

## 5. 확인한 것 (2026-11-28)
- config-server 의존성: `spring-cloud-config-server 5.0.6`(이 판의 `VaultEnvironmentProperties` 에 AppRole 인증·`backend`·`kvVersion`·`profileSeparator`·`defaultKey`·SSL trust store 설정이 있다). 실행 경로에 `spring-cloud-vault-config 5.0.3`·`spring-vault-core 4.0.4` 도 있다.
- 아직 config-server 코드·설정은 고치지 않았다.

## 6. 할 일 (순서)
1. **새 ADR**: ADR-0100 결정 3·4·5·6(서비스 직접 읽기·AppRole·서비스별 secret-id)을 대체. 사용자가 전에 이 방식(config-server 복합 백엔드)을 "정석이 아니야"라 했던 것을 이번 결정("1번")으로 바꾼다는 점을 적는다.
2. **config-server**: Git + Vault 복합 백엔드(Vault 는 AppRole 하나, kv-v2 `config`, 프로필 구분자 `,`, 사설 CA trust store). 같은 속성이 양쪽에 있으면 Vault 가 이기게 순서를 정한다. 통합 시험(Vault 컨테이너 + 시험 Git 저장소)으로 확인.
3. **Vault 쪽 — 할 일 없음**(pc2 Vault 세션 확인 2026-11-28): AppRole `config-server`(정책 `config-server-read` — `config/data/*` 읽기만)는 처음부터 **IP 묶음 없이** 만들어져(token_ttl 1h·최대 24h, secret_id_ttl 0) PC 4 → pc2 가 172.20.0.1 로 보여도 로그인된다. Docker Desktop 이 출발지를 모두 게이트웨이로 바꾸므로 "Docker 대역 허용"은 "어디서든"과 같아 넣을 뜻이 없다. 서비스별 `svc-*` AppRole·secret-id 는 필요 없어져 관리자 토큰 작업 때 정리한다.
   - config-server 설정에 쓸 값: 마운트 kv-v2 `config`, 경로 `config/<앱>,lan`(profileSeparator `,`), 필드 = Spring 속성 이름(목록은 `[n]` — `signing.keys[0]` 는 kid 까지 Vault 에 있다. Git 쪽 목록과 섞이면 한 출처의 목록으로 통째로 바뀌는 점 주의), `application` 경로는 없으니 defaultKey 는 404 무시 또는 끔, CA `deploy/lan/tls/pmart-lan-root-ca.crt`(PEM), 주소 `https://192.168.0.34:8200`.
   - role-id: `auth/approle/role/config-server/role-id`. secret-id: 배포 토큰(pmart-deployer)으로 `auth/approle/role/config-server/secret-id` 에서 발급(정책 `+/secret-id`) — 값은 화면·로그·Git 에 두지 않고 config-server 비밀 디렉터리 파일로.
4. **LAN 적용**: config-server 먼저 배포 → 앱 하나가 config-server 를 거쳐 비밀을 받는지 확인 → `vault-apps.conf` 를 비워 pc2 앱 20개도 config-server 경로로 돌림.
5. **그 뒤에** pmart-config 의 `{cipher}` 줄 삭제(ADR-0100 결정 9 ⑤와 같은 원칙 — 전체가 동작한 뒤에만).
