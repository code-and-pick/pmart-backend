# ADR-0027 LAN 설정은 Spring Cloud Config Server(GitHub 저장소, {cipher})로 부팅 때 받는다

- 상태: 승인, 결정 2·3·5 는 ADR-0029, 결정 6 은 ADR-0033(웹훅 + Bus 실행 중 갱신), 결정 4({cipher})는 ADR-0100(Vault — 서비스가 직접 읽음, 전환 순서 끝나면)으로 대체 (2026-11-02, 사용자 결정 "configuration 서버 바로 만들어줘" — 저장소 GitHub, 전체 서비스, 비밀은 {cipher}, 부팅 때만 읽음)
- 대체: `docs/architecture/tech-stack.md`·`service-map.md` 의 "Spring Cloud Config/Eureka 서비스는 두지 않는다"(2026-10-23) 중 **Config 부분, LAN(compose) 환경에 한해**. Eureka(서비스 검색)는 계속 두지 않는다.
- 관련: `.claude/rules/security-config.md`(설정·서비스 검색, 비밀정보), `deploy/lan/render.sh`·`lan.sh`, ADR-0012(이미지 공개), ADR-0026

## 배경
- LAN 은 Kubernetes 가 아니라 PC 3대의 docker compose 다. 설정·비밀값은 `render.sh` 가 `.secrets/` 와 배치 파일에서 PC 별 `compose.yml` 환경변수·`.env` 로 만들어 왔다. 서비스마다 같은 값(Kafka·JWKS·Zipkin·DB 사용자)이 compose 에 반복되고, 값을 바꾸면 PC 별 파일을 다시 만들어 복사해야 했다.
- 사용자 결정(2026-11-02): 부팅할 때 서비스에게 설정을 주는 Configuration 서버를 지금 만든다. 저장소는 GitHub, 33개 Spring 앱 전체, 비밀은 저장소에 `{cipher}` 로, 실행 중 갱신은 하지 않는다.
- 서비스 검색은 이미 이름(compose 서비스명·`extra_hosts`, 운영은 Service DNS)으로 해결되어 이번 범위가 아니다.

## 결정
1. **`platform-services/config-server`**(Spring Cloud Config Server, 포트 8888)를 둔다. 업무 로직·업무 API 없음. LAN 은 pc4 에 하나.
2. **저장소 = GitHub `code-and-pick/pmart-config`(private)**, 환경별 폴더(`lan/`)를 `search-paths` 로 고른다. `lan/application.yml` = 모든 앱 공통, `lan/<spring.application.name>.yml` = 앱별.
   - 서버는 **읽기 전용 Deploy key**(ed25519, SSH)로 읽는다. GitHub 호스트 키는 공개 지문과 대조해 `deploy/lan/config-server/github-host-key.conf` 에 고정한다(strict host key checking).
   - 저장소 내용은 **사람이 고치지 않고 `render.sh` 가 만든다**(정본은 지금처럼 `placement.conf`·`render.sh`·`.secrets/`). `lan.sh config-push` 가 바뀐 키 이름만 보여 주고 commit·push 한다(PC 1 사용자 GitHub 자격 증명). `lan.sh deploy` 는 render 결과가 저장소 main 과 다르면 멈춘다.
3. **키 이름은 기존 `${PMART_*}` 자리표시자 그대로** 둔다. 서비스의 `application.yml` 은 바뀌지 않고, 자리표시자가 환경변수 대신 Config 서버 값으로 채워진다. 자리표시자가 아니라 속성 바인딩에 기대던 값(auth-service 서명키·첫 관리자)은 정식 속성 이름(`pmart.auth.signing.keys[0].*`, `pmart.auth.operator.bootstrap.*`)으로 둔다.
4. **비밀값은 `{cipher}`**(Spring Cloud `EncryptorFactory` — AES-256, key + 16진수 salt, 값마다 무작위 IV)로 저장소에 두고 **서버가 복호화해** 내려준다(server-side decryption). 암호화는 오프라인 `configCipher` 태스크(같은 `EncryptorFactory`)로 하고, 같은 평문의 기존 암호문은 재사용해 저장소에 의미 없는 변경을 만들지 않는다.
   - 암호화 키·salt 는 **저장소에 두지 않는다**(`.secrets/config-encrypt-*`, LAN 은 config-server 컨테이너에만).
   - 서버의 **`/encrypt`·`/decrypt` 는 막는다**(인증 정보 하나로 모든 비밀을 복호화할 수 있게 되므로).
   - 이것은 `security-config.md` "비밀정보를 Git 에 저장하지 않는다"의 **예외**다: Git 에는 암호문만 있고 키는 Git·이미지·application.yml 밖에 있다. 키가 새면 저장소 이력의 모든 비밀이 노출되므로, 키 유출 시 키와 **비밀값 자체**를 함께 바꾼다(키만 바꾸면 옛 커밋의 암호문이 남는다).
5. **클라이언트는 기본 꺼짐**: convention plugin(`pmart.spring-service`·`pmart.spring-gateway`)이 `spring-cloud-starter-config` 를 넣고, 각 `application.yml` 은 `spring.config.import: optional:configserver:` + `spring.cloud.config.enabled: false`. 로컬 실행·시험·Helm(Kubernetes)은 지금과 같다(ConfigMap·환경변수).
   - LAN compose 만 `SPRING_CLOUD_CONFIG_ENABLED=true`·`URI`·`FAIL_FAST=true`·Basic 인증(사용자 `pmart`, `.secrets/config-password`)을 준다. **설정을 받지 못하면 기동하지 않는다**(빈 설정으로 뜨지 않음). 다시 시도는 compose `restart`, `lan.sh deploy` 는 config-server 를 먼저 띄우고 UP 을 확인한 뒤 나머지를 올린다.
   - 원격 값이 환경변수·로컬 파일보다 우선한다(Spring Cloud Config 기본). LAN 에서 값을 바꾸려면 render → config-push → 재시작.
6. **부팅 때만 읽는다**: `@RefreshScope`·`/actuator/refresh`·Spring Cloud Bus 는 쓰지 않는다. 설정을 바꾸면 해당 앱을 재시작한다.
7. **config-server 자신의 비밀**(encrypt key·salt, Basic 인증 비밀번호, Deploy key)은 compose 가 마운트한 디렉터리를 `configtree:` 로 읽는다(파일 이름 = 속성 이름). 여러 줄 PEM 을 환경변수로 넘기지 않기 위해서다.
8. **Spring 이 아닌 컨테이너**(postgres·connector-registrar·s3)는 지금처럼 `.env` 로 받는다.

## 대안
- **환경변수 유지(기존)**: 가장 단순하고 Kubernetes 방식과 같다. 사용자 결정으로 LAN 에 Config 서버를 둔다.
- **저장소를 이 저장소 폴더(native)로**: GitHub 접근 키가 필요 없지만, 사용자 결정은 GitHub.
- **비밀은 환경변수 유지**: Git 에 암호문도 두지 않는 쪽이 더 안전하다. 사용자 결정은 `{cipher}`(설정 한 곳 관리).
- **Vault**: 비밀 관리에 더 적합하지만 인프라가 하나 늘어난다.
- **실행 중 갱신(Bus)**: Kafka 에 Bus 토픽·`@RefreshScope` 가 필요하다. 지금은 필요 없다.

## 결과
- 영향: 모든 Spring 앱(33개 — `pmart.spring-service`·`pmart.spring-gateway` 의존성, 각 `application.yml` 두 설정), 새 `platform-services/config-server`, `gradle/libs.versions.toml`, LAN 배포 스크립트(`init-secrets.sh`·`render.sh`·`lan.sh`·`placement.conf`). `libraries/` 는 바뀌지 않는다.
- LAN 의 모든 Spring 앱이 부팅 때 config-server 에 의존한다. config-server 가 내려가 있으면 **이미 떠 있는 앱은 영향이 없고**, 새로 뜨거나 재시작하는 앱만 뜨지 못한다. config-server 는 GitHub 에 닿지 못하면 기동하지 않는다(`clone-on-start`).
- 모든 앱이 같은 Basic 인증 정보를 쓴다 → 앱 하나가 털리면 다른 앱의 설정·비밀도 읽을 수 있다(compose 환경변수 방식과 같은 수준). 앱별 자격 증명은 필요해지면 따로 결정한다.
- 운영(Kubernetes)은 이 ADR 범위가 아니다 — 지금처럼 ConfigMap·Secret Manager(클라이언트 꺼짐). 운영에 Config 서버를 쓰려면 새 ADR 로 정한다.
- GitHub 저장소 생성·Deploy key 등록은 사용자 GitHub 권한으로 한다(`deploy/lan/README.md` 절차).
