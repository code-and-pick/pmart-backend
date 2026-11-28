# ADR-0101 LAN 서비스 비밀은 config-server 가 Git + Vault 복합 백엔드로 내려준다 — 서비스는 Vault 를 직접 읽지 않는다

- 상태: **승인** (2026-11-28, 사용자 결정 — "eum 이랑 동일한 구조로 가야할것같아" → Eum 의 두 부분(① config-server 복합 백엔드 ② 서비스 직접 읽기·공용 루트 토큰) 중 "응 1번이야"). 구현 전.
- 경위: 사용자는 처음(ADR-0100) **서비스별 최소 권한**을 이유로 복합 백엔드를 뺐다("정석이 아니야"). 여러 PC 의 출발지 IP 장애(배경) 뒤 **단순함을 택해** 바꿨다 — 최소 권한이 없어진다는 설명을 듣고 다시 확인(pc2 Vault 세션 경유, 2026-11-28 "eum 프로젝트 방향으로 가려면 pc1이 결정한대로 진행해").
- 대체: **ADR-0100 결정 3·4·5·6**(서비스가 Spring Cloud Vault 로 직접 읽음, 기본 꺼짐·LAN 환경변수로 켬, 서비스별 AppRole·IP 묶음, 배포 토큰으로 서비스별 secret-id 발급). ADR-0100 의 **대안 1("config-server 를 Git + Vault 복합 백엔드로" — 그때 사용자 "정석이 아니야")을 이번 결정으로 채택**한다.
- 유지: ADR-0100 결정 1(pc2 Vault, TLS)·2(KV v2 `config/<앱>,lan`, 필드 = Spring 속성 이름)·7(목록은 통째로)·8(unseal·관리자 토큰·감사·스냅샷)·9 의 ⑤(전체가 동작한 뒤에만 `{cipher}` 삭제). ADR-0027 결정 4 대체(`{cipher}` 제거)도 그대로.
- 범위: LAN. 운영(AWS·Kubernetes·Native)은 ADR-0100 과 같이 이 ADR 밖.
- 관련: ADR-0027·0029·0033(config-server·Bus), ADR-0057(사설 CA), ADR-0100, `docs/operations/2026-11-28-vault-all-rollout-incident.md`(장애), `docs/operations/2026-11-28-vault-eum-comparison-and-config-server-plan.md`(Eum 비교·계획)

## 배경
- 2026-11-28 ADR-0100 결정 3~6 대로 전체 앱을 Vault 직접 읽기로 배포하자 **pc3·pc4·pc5 의 앱이 모두 AppRole 로그인에 실패**해 재시작을 반복했고(게이트웨이·인증 포함 약 10분), pc2 앱 20개만 남기고 되돌렸다. pc2 의 Docker Desktop 포트 공개가 출발지 IP 를 Docker 게이트웨이(172.20.0.1)로 바꿔, "그 앱 PC IP/32" 묶음(결정 5)이 다른 PC 앱에 맞지 않았다. Docker Desktop 에서는 "Docker 대역 허용"이 곧 "어디서든"이라 묶음으로 고칠 수도 없다.
- 학습 프로젝트 Eum 은 Vault 를 서비스와 같은 Docker 네트워크에 두고 ① config-server 가 Git + Vault 를 같이 보며 ② 서비스도 공용 루트 토큰으로 직접 읽는다. 우리는 서비스가 PC 5대에 흩어져 ② 의 조건(한 네트워크)이 맞지 않고, 공용 루트 토큰은 최소 권한이 없다. ① 은 우리 구조에도 맞는다 — Vault 에 접속하는 곳이 config-server 하나가 된다.

## 결정
1. **config-server(PC 4)가 Git + Vault 복합 백엔드**(Spring Cloud Config `composite`: `git` + `vault`)로 `/{앱}/{프로필}` 응답에 Vault 비밀을 합쳐 내려준다. **같은 속성은 Vault 가 이긴다**(복합 순서에서 Vault 를 앞에 둔다).
2. **서비스는 Vault 를 모른다** — 지금처럼 config-server 에서만 받는다. 서비스의 Spring Cloud Vault 는 기본 꺼짐 그대로 두고(`VaultDefaultsPostProcessor`), LAN 에서도 켜지 않는다. `deploy/lan/vault-apps.conf` 는 비운다(지금 pc2 앱 20개 직접 읽기는 config-server 경로가 확인되면 되돌린다).
3. **Vault 백엔드 설정** — kv-v2 마운트 `config`, 경로 `config/<앱>,<프로필>`(profileSeparator `,`), 공통 경로 `application` 은 쓰지 않는다(없음 — defaultKey 를 끄거나 404 무시), 주소 `https://192.168.0.34:8200`, 신뢰 CA 는 ADR-0057 사설 CA(`pmart-lan-root-ca.crt`, PEM).
4. **config-server 의 Vault 인증은 AppRole `config-server` 하나** — 정책 `config-server-read`(`config/data/*` 읽기만, 처음 setup 때 만듦). IP 묶음 없음(토큰 1h·최대 24h, secret-id 90일 — #569, render 가 배포마다 새로 발급해 옛 것은 저절로 사라진다. 90일 동안 config-server PC 재배포가 없으면 재시작 때 로그인 실패). role-id·secret-id 는 config-server 자기 비밀처럼 configtree 디렉터리 파일로 받는다(ADR-0027 결정 7과 같은 방식 — Git·이미지·application.yml·로그에 두지 않음). secret-id 는 배포 토큰(`pmart-deployer`)으로 `auth/approle/role/config-server/secret-id` 에서 발급한다.
5. **실패하면 기동하지 않는다** — Vault 에 닿지 못하거나 로그인이 안 되면 config-server 응답을 실패로 낸다(복합 오류를 조용히 넘기지 않음 — 비밀 없이 빈 값으로 서비스가 뜨지 않게). 서비스는 지금처럼 config-server fail-fast.
6. **`local` 프로필(개발자 PC)은 바뀌지 않는다** — native 파일 저장소만, Vault 없음(로컬 설정에는 비밀이 없다).
7. **전환 순서** — ① config-server 코드·통합 시험(Vault 컨테이너 + 시험 Git 저장소: 합쳐짐·Vault 우선·목록 통째·Vault 실패 시 오류) → ② config-server 의 role-id·secret-id 파일과 compose(PC 1 render) → ③ config-server 만 배포, Vault 감사 로그로 config-server 읽기 확인, `{cipher}` 앱 하나·pc2 앱 하나가 config-server 를 거쳐 같은 값을 받는지 확인 → ④ `vault-apps.conf` 비우고 pc2 재배포 → ⑤ **그 뒤에만** pmart-config 의 `{cipher}` 줄·signing.keys 목록·서비스 시험 사본 삭제, config-server `encrypt.key`·salt 제거(ADR-0100 결정 9 ⑤).

## 대안
- **ADR-0100 그대로 + AppRole IP 묶음 풀기**(장애 뒤 A안): 바로 되지만 secret-id 가 새면 어느 PC 에서든 쓸 수 있어 묶음의 뜻이 없어지고, 서비스 50여 개의 Vault 설정·secret-id 발급·만료 관리가 남는다.
- **Vault 를 pc2 Windows 에 직접 설치**(B안): 출발지 IP 가 보여 결정 5 의 뜻대로 되지만 운영 방식이 바뀌고, 서비스별 자격 관리는 그대로다.
- **인증서 로그인**(C안): IP 에 기대지 않지만 서비스마다 클라이언트 인증서 발급·배포·갱신이 늘어난다.
- **Eum 그대로(② 서비스 직접 + 공용 루트 토큰)**: 토큰 하나로 Vault 전체 읽기·쓰기, 토큰이 코드 기본값에 들어간다. 따르지 않는다.

## 결과
- **잃는 것: 서비스별 최소 권한.** config-server 가 모든 서비스 비밀을 읽어 Basic 인증 하나로 내려준다(ADR-0027 "결과"의 약점이 그대로 — 지금 `{cipher}` 와 같은 수준). config-server 접속 정보(Basic 비밀번호)는 지금처럼 PC 별 비밀 파일로 지킨다.
- **얻는 것**: 다른 PC 서비스가 Vault 에 직접 붙지 않아 Docker Desktop NAT·IP 묶음 문제가 없다. 서비스 코드·이미지·환경변수 변화 없음. 비밀의 정본이 Git 암호문이 아니라 Vault 한 곳(바꾸기·회수·감사 가능). 서비스별 `svc-*` AppRole·정책·secret-id 발급은 필요 없어진다(정리는 pc2 Vault 세션, 관리자 토큰 작업 때).
- **Vault 는 config-server 를 거친 기동 의존성**: Vault 가 꺼지거나 sealed 면 config-server 응답이 실패해 **서비스의 새 기동·재시작이 실패**한다(이미 떠 있는 서비스는 영향 없음). config-server 자신은 Git 만으로도 뜬다.
- **비밀을 바꾸면**: Vault `config/<앱>,lan` 을 고친 뒤 그 앱 재시작(또는 `/actuator/busrefresh`). Vault 변경은 GitHub 웹훅이 없어 `/monitor` 로 자동 전달되지 않는다.
- 목록(`signing.keys[0]` 등)은 한 출처에서 통째로 오므로 그 목록의 일반 값(kid)도 Vault 에 둔다(ADR-0100 결정 7 유지 — 복합 응답에서도 같은지 통합 시험으로 확인).
- `render.sh` 의 서비스 Vault 환경변수·CA 마운트·secret-id 발급(#566~#568)은 쓰지 않게 된다 — 정리는 ⑤ 뒤에 따로.

## 확인 (2026-11-28, PC 1 — 전환 ①)
- 구현: config-server `application.yml` 의 `vault` 프로필(LAN 은 `SPRING_PROFILES_ACTIVE=git,vault`, Vault order 1·Git order 2, kv-v2 `config`, 구분자 `,`, AppRole), `VaultTrustStorePostProcessor`(Vault 백엔드는 신뢰 저장소를 PKCS12 로만 읽어 — 형식 설정이 없다 — `pmart.config.vault.ca-pem` 의 PEM 을 기동 때 PKCS12 임시 파일로 바꿔 넣는다. 파일·인증서가 없으면 기동 실패). role-id·secret-id 는 configtree 파일 `spring.cloud.config.server.vault.app-role.role-id`·`secret-id`.
- `ConfigServerVaultIT`(4 — Vault 1.21.4 dev-tls 컨테이너, LAN 과 같은 정책 `config/data/*` 읽기만): Vault 출처가 Git 보다 앞·같은 속성은 Vault 값·Git 의 다른 값 유지 / Vault 경로 없는 앱은 Git 만 / 서비스(config client)는 비밀은 Vault 값·목록(`pmart.auth.signing.keys`)은 Vault 것 1개로 통째로 / 틀린 secret-id 면 config-server 응답 500(`VaultLoginException: invalid role or secret ID`)·fail-fast 서비스 기동 실패. `VaultTrustStorePostProcessorTest`(4). 기존 `ConfigServerIT` 9·`MonitorBusIT` 3·`ConfigCipherToolTest` 5 그대로 통과(`:platform-services:config-server:check`).
- 남은 것: ② render.sh(config-server 의 role-id·secret-id 파일·CA 마운트·프로필) → ③ LAN.
