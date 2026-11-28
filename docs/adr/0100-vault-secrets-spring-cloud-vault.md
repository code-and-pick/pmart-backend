# ADR-0100 LAN 서비스 비밀은 Vault 에 두고 서비스가 Spring Cloud Vault 로 직접 읽는다 — pmart-config 의 {cipher} 를 없앤다

- **결정 3·4·5·6 은 ADR-0101 로 대체**(2026-11-28 — 서비스 직접 읽기 대신 config-server 가 Git + Vault 복합 백엔드로 내려준다. 전체 전환 때 pc3·4·5 앱의 AppRole IP 묶음 로그인 실패). 결정 1·2·7·8·9 ⑤ 는 유지.
- 상태: 승인 (2026-11-28, 사용자 결정 — "cipher를 없애려고 지금 vault 서버를 운용하려는건데", "서비스가 vault에서 직접 읽어야지", "이왕하는거면 tls 바로 하는게 낫지 않아?", 서버 위치 "pc2에서 그대로"). 구현: Vault 서버·데이터·클라이언트 코드는 끝(PR #564, 기본 꺼짐). LAN 연결(render.sh)·{cipher} 삭제는 후속(아래 결정 9).
- 대체: ADR-0027 결정 4(비밀은 pmart-config 에 `{cipher}`, config-server 가 복호화) — 결정 9 의 5단계가 끝나면. 결정 7(config-server 자신의 비밀 configtree)은 유지하되 `encrypt.key`·`encrypt.salt` 는 {cipher} 가 없어지면 필요 없다. `tech-stack.md` "비밀정보: Vault 또는 Cloud Secret Manager" 중 **LAN 은 Vault**.
- 범위: LAN(docker compose 5대). 운영(ADR-0029 — AWS EC2, GraalVM Native)·Kubernetes 는 이 ADR 밖 — 같은 구조(서비스가 Vault 에서 직접)로 이어 갈 수 있지만 인증 방식(EC2·Kubernetes 인증)과 Native 동작은 따로 정한다.
- 관련: ADR-0027, ADR-0029, ADR-0057(LAN 사설 CA), `deploy/vault/README.md`, `.claude/rules/security-config.md`, `libraries/common-observability`(VaultDefaultsPostProcessor)

## 배경
- ADR-0027 은 비밀을 GitHub `pmart-config` 에 `{cipher}` 암호문으로 두었다. 키 하나(`config-encrypt-key`)가 새면 저장소 이력의 모든 비밀이 풀리고, config-server 접속 정보 하나로 모든 서비스의 비밀을 받을 수 있다(ADR-0027 "결과"에 적은 약점). 비밀 원본은 PC 1 의 `deploy/lan/.secrets/` 파일·개인 PC 의 `.env`·키 폴더에 흩어져 있었다.
- 비밀 관리는 처음부터 Vault 로 계획돼 있었고(`tech-stack.md`), 사용자가 운용을 미뤄 두었다. 2026-11-28 사용자 지시로 Vault 서버를 띄우고 {cipher} 를 없애기로 했다.

## 결정
1. **팀 공용 Vault 서버 1대** — pc2(192.168.0.34), `deploy/vault/compose.yaml`(Vault 1.21.4, 단일 노드 Raft, named volume). **TLS 로 LAN 에** 연다(`https://192.168.0.34:8200`, ADR-0057 사설 CA 로 발급한 pc2 인증서). TLS 없이 LAN 에 여는 것은 `vault.sh` 가 막는다.
2. **저장 구조(KV v2)**
   - `config/<spring.application.name>,lan` — 서비스가 읽는 비밀. 필드 = **Spring 속성 이름 그대로**(목록은 `[n]`). 지금 {cipher} 82개 + `signing.keys[0].kid` 3개(46경로·85필드), 원래 암호문 값과 sha256 일치로 옮겼다(`verify/migrate-cipher.sh`).
   - `pmart/shared/…`·`pmart/ops/…` — 외부 키·앱 키·LAN `.secrets` 원본(파일 이름 그대로)·운영 비밀(config-server 키, 사설 CA 개인키, deploy key 등). config-server 를 거치지 않는 것(postgres·Keycloak·config-server 자신·render)의 출처.
3. **서비스가 Vault 에서 직접 읽는다(Spring Cloud Vault)** — config-server 는 일반 설정만. 서비스마다 자기 경로만 읽으므로 한 서비스 자격 증명이 새도 그 서비스 비밀만 노출된다(최소 권한). 운영 Kubernetes 의 Vault 인증과 같은 구조로 이어진다.
4. **기본 꺼짐, LAN 만 켬** — 모든 앱에 `spring-cloud-starter-vault-config`(convention). `common-observability` 의 `VaultDefaultsPostProcessor` 가 `spring.cloud.vault.enabled=false` 를 가장 낮은 우선순위로 넣는다. **application.yml 은 바꾸지 않는다** — `vault://` import 는 설정 데이터 단계(후처리보다 먼저)라 yml 에 두면 로컬·시험도 Vault 에 붙는다. LAN 컨테이너만 환경변수로 켠다: `SPRING_CONFIG_IMPORT=optional:configserver:,vault://`(Vault 가 뒤라 같은 속성은 Vault 가 이긴다), `SPRING_CLOUD_VAULT_ENABLED=true`, `…_URI`, `…_FAILFAST=true`, `…_AUTHENTICATION=APPROLE`, `…_APPROLE_ROLEID`·`…_SECRETID`, `…_KV_BACKEND=config`, `…_KV_PROFILESEPARATOR=,`, `…_KV_DEFAULTCONTEXT=`(빈 값), `…_SSL_TRUSTSTORE=file:<CA>`·`…_SSL_TRUSTSTORETYPE=PEM`.
5. **서비스마다 AppRole** — 정책 `svc-<앱>`(자기 `config/` 경로 읽기만) + AppRole `svc-<앱>`(토큰 1h·최대 24h, secret-id 90일). secret-id·토큰은 placement.conf 의 **그 앱 PC IP 로 묶는다**(다른 PC 에서는 못 쓴다). Vault PC(pc2)의 앱은 Docker 게이트웨이(172.21.0.1 확인)로 들어오므로 Docker 대역도 허용. `service-roles.sh` 가 placement.conf 에서 만든다(2026-11-28 53개).
6. **배포는 배포 토큰으로** — 정책 `pmart-deployer`(role-id 읽기·secret-id 발급만, 비밀·역할 설정 못 바꿈). render.sh 가 배포 때 숨김 입력으로 받아 서비스별 secret-id 를 발급해 PC 별 `build/<pc>/.env`(git 제외)에 넣는다. 서비스의 첫 로그인 열쇠(secret-id)만 PC 에 남고 비밀 값은 남지 않는다.
7. **목록은 통째로** — Spring 은 목록을 우선순위가 가장 높은 출처 하나에서 통째로 받는다. `signing.keys[0]` 처럼 {cipher} 가 든 목록 항목은 같은 항목의 일반 값(kid)도 Vault 에 두고, Git 쪽 목록은 지운다(`VaultClientLanIT` 로 확인).
8. **Vault 자체의 열쇠** — unseal 키 3개(해제 2개, Shamir)는 관리자 구글 드라이브(사용자 결정 — 처음 넣을 비밀의 원본도 구글 드라이브). root 토큰은 초기 설정 뒤 폐기. 평소에는 관리자 토큰(`pmart-admin`, -orphan, 32일 주기 갱신). 감사 로그 켬. 백업은 Raft 스냅샷(`vault.sh snapshot`, unseal 키와 다른 곳).
9. **전환 순서** — ① Vault·데이터·AppRole·코드(완료) → ② render.sh 에 결정 4 의 환경변수·CA 마운트·secret-id 발급(PC 1) → ③ 이미지 재빌드 → ④ 서비스 하나(payment-service) 먼저 → 전체 → ⑤ **그 뒤에만** pmart-config 의 `{cipher}` 줄·signing.keys 목록·서비스 시험 사본 삭제, render.sh 암호화 단계 제거. ⑤ 를 먼저 하면 서비스가 비밀 없이 기동 실패한다.

## 대안
- **config-server 를 Git + Vault 복합 백엔드로**(학습 프로젝트 방식 — 정정 2026-11-29: Eum 의 config-server 는 실제로는 Vault 만 본다. → 이 안은 ADR-0101 로 채택): config-server 하나만 바꾸면 되지만 config-server 가 모든 비밀을 읽어 Basic 인증 하나로 내려준다 — ADR-0027 의 약점이 그대로. 사용자: "정석이 아니야".
- **render.sh 가 Vault 에서 원본을 읽고 {cipher} 유지**(키만 Vault 에): 변경이 가장 작지만 {cipher} 를 없애는 목적과 맞지 않는다.
- **SOPS 등 암호화 파일을 Git 에**: 서버가 필요 없지만 키 교체·회수·권한 분리·감사가 안 되고 {cipher} 와 같은 성질이다.
- **PC 마다 로컬 Vault**: "한 곳에서 관리"가 안 된다.
- **HTTP 로 먼저 붙이고 TLS 는 나중**: 서비스 50여 개 설정을 두 번 손봐야 한다. 사용자: TLS 바로.

## 결과
- **Vault 가 기동 의존성이 된다**: pc2 Vault 가 꺼지거나 sealed 면 LAN 앱의 **새 기동·재시작이 실패**한다(fail-fast — 비밀 없이 빈 값으로 뜨지 않게). 이미 떠 있는 앱은 영향 없음. pc2 재부팅 뒤에는 unseal 키를 가진 관리자가 `vault.sh unseal` 해야 한다(`deploy/vault/README.md` 운영 절차).
- 같은 비밀이 `pmart/shared/lan`(파일 형식)과 `config/`(속성 형식) 두 곳에 있다. **서비스 비밀의 정본은 `config/`**. 바꿀 때는 `config/<앱>,lan` 을 고치고 그 앱을 재시작한다. `pmart/shared/lan` 은 config-server 를 거치지 않는 것만 남기도록 정리한다(후속).
- 토큰 갱신(관리자·배포, 32일 안에 한 번)과 secret-id 만료(90일 — 그동안 재배포가 없으면 재시작 때 로그인 실패)를 챙겨야 한다.
- 새 서비스를 추가하면 placement.conf 반영 뒤 `service-roles.sh`(AppRole)와 비밀 넣기(`config/<앱>,lan`)가 필요하다.
- GraalVM Native(보류 중)에서 Spring Cloud Vault 동작은 확인하지 않았다.
- HashiCorp Vault 는 BUSL 1.1 — 내부 사용. 필요하면 CLI·API 가 거의 같은 OpenBao 로 바꿀 수 있다.
- 시험: `VaultDefaultsPostProcessorTest`(4), `VaultClientLanIT`(3 — Testcontainers Vault dev-tls: AppRole+TLS, 환경변수 이름, Vault 우선, 다른 경로 거부, 목록 통째, 기본 꺼짐), config-server 17·payment-service ServiceBootIT 3(기동 변화 없음).
