# LAN 공유 개발 환경 (deploy/lan)

사내 LAN PC 여러 대에 PMart 서비스를 나눠 띄운다. Kubernetes(Helm) 이전 단계의 통합 확인용이며 운영 환경이 아니다.
PC 접속·원격 docker 설정은 `docs/worklog/2026-10-24.md` 참조. 배포 오류와 해결 기록은 `docs/operations/`(예: `2026-11-04-lan-deploy-incidents.md` — config-server 설정 요청 폭주).

| 파일 | 역할 |
|---|---|
| `pc-bootstrap.ps1` | 새 LAN PC 처음 준비(그 PC 에서 관리자로 한 번) — SSH 접속까지 |
| `placement.conf` | 배치 정보 — PC 이름·IP·ssh 별칭·구성요소. PC 수는 가변 |
| `render.sh` | 배치 → `build/<pc>/compose.yml`, `.env`, `init-db.sql` 생성 |
| `init-secrets.sh` | `.secrets/`(git 제외)에 DB 비밀번호·auth 서명키·S3 자격 증명 생성. 있으면 유지. **재생성하면 LAN 과 값이 달라지므로** 잃어버렸으면 PC 1 백업(`%USERPROFILE%\.pmart-lan-secrets-backup\`, 저장소 밖)에서 되돌린다 |
| `.secrets/keycloak-claude-dev-password`·`keycloak-admin-password` | **LAN 시험 직원 토큰**(2026-11-13 — auth-service 운영자 로그인을 없앤 뒤): `deploy/lan/checks/staff_auth.py` 가 Keycloak(pmart-dev-cli) 로그인 → employee-auth-service 시험용 교환으로 `claude-dev`(OPERATOR_ADMIN) 토큰을 받고, 다른 시험 계정은 실행마다 Keycloak 비밀번호를 새로 정한다. 시험 스크립트·`seed/mfc_layout.py`·`seed/Seed.java` 가 쓴다. 옛 `claude-dev-login`·`claude-dev-password`·`claude-dev-hash`·`operator-admin-*` 는 더 쓰지 않는다 |
| `.secrets/registry.env` | Docker Hub 액세스 토큰(읽기·쓰기, 사용자가 발급 — 저장소 밖). `set -a; . deploy/lan/.secrets/registry.env; set +a` 로 `REGISTRY_USERNAME`·`REGISTRY_PASSWORD` 를 넣으면 Jib 이 Docker Desktop 로그인 대신 이 값으로 push 한다(401 회피) |
| `lan.sh` | 실행기: `render` / `deploy` / `status` / `logs` / `restart` / `down` / `config-push` |
| `config-server/github-host-key.conf` | config-server 가 GitHub 에 SSH 로 붙을 때 확인하는 호스트 키(공개 지문과 대조함, 비밀 아님) |
| `.config-repo/` | 설정 저장소 GitHub `code-and-pick/pmart-config` 의 로컬 사본(git 제외). **설정은 여기서 고치고** `config-push` 로 올린다 |
| `watch.sh` | 감시: 각 PC 구성요소를 LAN 에서 실제 프로토콜로 점검, 연속 실패 시 그 PC 에서 재시작. PC IP 변경 감지 |
| `ipcheck.sh` | PC 의 실제 LAN IP 와 `placement.conf` IP 비교(`watch.sh` 가 사용, 단독 실행 가능) |
| `watch-install.ps1` | PC 1 작업 스케줄러에 `watch.sh` 5분 간격 등록(로그온 중 실행, `-S4U` 는 관리자 필요, `-Remove` 로 해제) |

## 주소 규칙
- 서비스 포트는 서비스마다 고유(gateway 8080, 8101~8507)라 **호스트 포트 = 컨테이너 포트**.
- 다른 PC 의 구성요소는 `extra_hosts` 로 가리킨다. 그래서 어느 PC 에서든 주소가 같다:
  `kafka-1:9092,kafka-2:9092,kafka-3:9092`(3노드 클러스터, ADR-0037), `auth-service:8501`, `redis:6379`, `zipkin:9411`, `postgres-<pc>:15432`, `es-search:9200`, `<service>:<port>`.
  게이트웨이 업스트림 기본값(`http://<service>:<port>`)도 그대로 맞는다.
- **새 구성요소를 더하면** 그 이름이 다른 PC 들의 `extra_hosts` 에도 새로 들어간다. 다른 PC 의 서비스가 그 이름을 부르면(예: 발급자 표의 JWKS 주소)
  그 PC 들의 앱을 **다시 만들어야** 한다(`lan.sh deploy <pc> <앱…>`). `lan.sh restart` 는 compose 변경을 반영하지 않는다 —
  2026-11-12 employee-auth-service 를 더했을 때 재시작만 한 PC 2~4 서비스가 `UnknownHostException: employee-auth-service`(JWKS)로 직원 토큰에 500 을 냈다.
  `deploy` 는 그 PC postgres 도 먼저 `up` 하므로 postgres 도 다시 만들어질 수 있다(데이터는 볼륨에 남는다).
- PostgreSQL 은 DB 가 필요한 서비스가 있는 PC 마다 하나(컨테이너 안에서도 15432). 그 PC 서비스의 DB 만 만든다.
- Debezium Connect 는 한 곳. `connector-registrar` 가 모든 PC 의 PostgreSQL(`DB_HOSTS`)을 훑어 커넥터를 만든다.
- 설정은 config-server 가 설정 저장소(pmart-config)에서 준다(아래 "설정" 절). `local` 프로파일은 LAN 에서 쓰지 않는다.

## 사용
```bash
# 1) 이미지 push (서비스마다; digest 가 build/jib-image.digest 에 남는다)
./gradlew :services:<name>:jib -PcontainerRegistry=docker.io/heeji4445 -PimageTag=<sha> -PgitRevision=<sha> --no-configuration-cache   # jib 은 구성 캐시와 호환되지 않음(ci-cd.md)
# 2) 파일 생성·배포
deploy/lan/lan.sh render
deploy/lan/lan.sh deploy all            # 또는 deploy pc5 delivery-tracking-service
deploy/lan/lan.sh status all
deploy/lan/lan.sh logs pc2 order-service 200
# 3) 시험 데이터(승인 API 경로, 다시 돌려도 안전) — deploy/lan/seed/README.md
java deploy/lan/seed/Seed.java
```
이미지는 태그가 아니라 **digest** 로 지정한다(ci-cd.md). push 하지 않은 서비스가 배치에 있으면 render 가 중단된다.

`jib` 이 Docker Hub 매니페스트 PUT 에서 401 로 실패하면(2026-10-30 여러 번 발생. Docker Desktop 재로그인 직후에는 되다가 다시 실패 — 저장된 단기 토큰을 Jib 이 갱신 없이 읽는 것으로 추정. 근본 해결은 ci-cd.md 대로 `REGISTRY_USERNAME`·`REGISTRY_PASSWORD`(Docker Hub 액세스 토큰) 환경변수) 같은 Jib 로 데몬에 만들고 Docker CLI 로 올린 뒤 그 digest 를 적는다:
```bash
./gradlew :services:<name>:jibDockerBuild -PcontainerRegistry=docker.io/heeji4445 -PimageTag=<sha> -PgitRevision=<sha> --no-configuration-cache
docker push heeji4445/pmart-<name>:<sha>                  # 출력 끝의 digest: sha256:… 를
printf %s 'sha256:…' > services/<name>/build/jib-image.digest
```

## 설정 (config-server, ADR-0027·0029)
- **설정의 정본은 GitHub `code-and-pick/pmart-config`(private) 저장소**다. 서비스 `application.yml` 에는 이름·config-server 접속(부트스트랩)만 있다.
  - `application.yml` 모든 서비스 공통 · `application-<프로필>.yml` 환경 공통(local·lan)
  - `<앱>.yml` 서비스 고유(포트·permit-paths·토픽 도메인 등) · `<앱>-<프로필>.yml` 환경별(DB·다른 서비스 주소·키)
- 모든 Spring 앱(33개)은 부팅할 때 pc4 의 `config-server:8888` 에서 `lan` 프로필(`SPRING_PROFILES_ACTIVE=lan`)로 설정을 받는다. **받지 못하면 뜨지 않는다**(fail-fast, compose `restart` 로 다시 시도). 이미 떠 있는 앱은 config-server 가 내려가도 영향이 없다.
- 비밀값은 `{cipher}…` 암호문(키 `.secrets/config-encrypt-key`·`-salt`, 저장소에는 없음). `.env` 에는 Spring 이 아닌 컨테이너(postgres·connector-registrar·s3)의 비밀과 config-server 접속 비밀번호(`PMART_CONFIG_PASSWORD`)만 남는다.
- **설정을 바꾸려면**: `deploy/lan/.config-repo/` 에서 파일을 고친다 → `deploy/lan/lan.sh config-push "메시지"`(바뀐 **키 이름**만 보여 주고 commit·push) → 웹훅이 연결돼 있으면 바뀐 파일의 앱이 **스스로 다시 받는다**(아래 '설정 실행 중 갱신'). 재시작이 필요한 설정이거나 웹훅이 없으면 `lan.sh restart <pc> <앱>`.
  - 비밀값을 넣거나 바꿀 때: 평문을 저장소에 쓰지 않는다. 한 줄 `키<TAB>평문Base64` 파일을 만들어
    `./gradlew -q :platform-services:config-server:configCipher -PcipherIn=… -PcipherOut=… -PcipherKeyFile=deploy/lan/.secrets/config-encrypt-key -PcipherSaltFile=deploy/lan/.secrets/config-encrypt-salt`
    로 암호문을 받아 `'{cipher}<암호문>'` 으로 넣는다(입력 파일은 바로 지운다).
- `lan.sh deploy` 는 사본에 미커밋·미push 가 있으면 멈추고, config-server 를 먼저 올려 UP 을 확인한 뒤 나머지를 올린다. `render` 는 DB 를 쓰는 앱의 `<앱>-lan.yml` DB 주소가 `placement.conf` 의 PC(`postgres-<pc>`)와 같은지 확인한다 — **배치를 바꾸면 저장소도 바꾼다.**
- 로컬 개발(개발자 PC): config-server 를 먼저 `./gradlew :platform-services:config-server:bootRun --args='--spring.profiles.active=local'`(pmart-config clone 을 파일로 읽음, 기본 `deploy/lan/.config-repo`) → 서비스는 지금처럼 `--spring.profiles.active=local`.
- 통합 시험은 config-server 없이 돈다: 서비스마다 `src/integrationTest/resources/pmart-config/` 에 정본 사본(`application.yml`·`<앱>.yml`)이 있다 — **정본을 바꾸면 `deploy/lan/config-copies.sh sync`** (확인만: `check`, 배포 전 `lan.sh deploy` 도 확인한다).

### 처음 한 번 (사용자 GitHub 권한)
1. `deploy/lan/lan.sh render` — `init-secrets.sh` 가 `config-encrypt-key`·`config-encrypt-salt`·`config-password`·`config-deploy-key`(ed25519) 를 만든다.
2. GitHub 에 **private** 저장소 `code-and-pick/pmart-config` 를 만든다.
3. 저장소 Settings → Deploy keys → `deploy/lan/.secrets/config-deploy-key.pub` 를 **읽기 전용**(Allow write access 끔)으로 등록.
4. config-server 이미지 push 후 `deploy/lan/lan.sh deploy all`.

### 설정 실행 중 갱신 (웹훅 + Bus, ADR-0033)
```
pmart-config push → GitHub 웹훅 → smee.io 채널 → config-relay(pc4) → config-server POST /monitor(서명 검사)
  → Kafka springCloudBus → 바뀐 파일의 앱(<앱>*.yml → 그 앱, application*.yml → 모든 앱)이 config-server 에서 다시 받는다
```
- **다시 읽히는 것**: `logging.level.*`, 게이트웨이 라우트, setter 형 `@ConfigurationProperties`. **재시작해야 하는 것**: DB·Kafka·Redis 주소, `*.enabled` 처럼 켜고 끄는 값, record 형 설정, `eureka.*`. 모르면 재시작.
- 확인: `lan.sh logs pc4 config-server` 에 `Refresh for: <앱>`, 해당 앱 로그에 `Keys refreshed [...]`.
- `config-relay` 는 `render.sh` 가 config-server PC 에 자동으로 붙인다(placement.conf 에 적지 않는다, 포트 없음). 뜰 때 npm 에서 `smee-client` 를 받는다.
- 웹훅이 403 이면(서명 불일치) 갱신만 안 되고 앱은 그대로다 — 재시작으로 반영한다. ADR-0033 '결과'(smee 재직렬화) 참고.

#### 처음 한 번 (사용자 GitHub 권한)
1. `deploy/lan/init-secrets.sh` — `config-webhook-secret`(서명 비밀)과 `smee-url`(smee.io 새 채널) 을 만든다. 둘 다 `.secrets/`(git 제외).
2. GitHub `code-and-pick/pmart-config` → Settings → Webhooks → Add webhook:
   - Payload URL: `.secrets/smee-url` 의 주소(config-server 주소가 아니다) · Content type: **application/json**(기본 form 이면 /monitor 가 못 읽는다) · Secret: `.secrets/config-webhook-secret` 의 값(앞뒤 공백·줄바꿈 없이) · SSL verification: Enable
   - Which events: **Just the push event** · Active
   - 값은 파일을 메모장으로 열어 복사한다(이 대화의 `!` 명령으로 출력하지 않는다).
3. config-server 이미지 push 후 `lan.sh render` → `lan.sh deploy pc4`(config-server·config-relay) → 나머지 앱 재배포(새 이미지에 Bus 가 들어 있다).
4. GitHub Recent Deliveries 는 언제나 200 이다 — GitHub 가 보내는 곳은 smee.io 이고 smee.io 는 받기만 하면 200 을 준다. config-server 의 결과(200·403)는 `lan.sh logs pc4 config-relay` 의 `POST http://config-server:8888/monitor - <상태>` 로만 보인다(ping 은 push 가 아니라 403). 설정 파일 하나를 push 해서 위 '확인'으로 본다. push 가 403 이면 Secret 불일치 또는 smee 재직렬화(ADR-0033 '결과').

## 서비스 등록·조회 (Eureka, ADR-0032)
- pc4 의 `eureka-server:8761`. 대시보드 `http://192.168.0.40:8761` — 사용자 `pmart`, 비밀번호 `deploy/lan/.secrets/eureka-password`(`init-secrets.sh` 가 만든다).
- 모든 Spring 앱(config-server 제외)은 lan 프로필에서 **자기 PC 의 LAN IP + 포트**로 등록한다(`PMART_HOST_IP`, render.sh 가 placement.conf 의 IP 로 넣는다). 설정은 저장소 `application-lan.yml` 의 `eureka.*`.
- 게이트웨이는 `lb://<서비스>`(api-gateway-lan.yml, 등록이 없으면 503 — 재배포 직후 10~20초), 서비스 간 호출은 common-web 이 주소의 호스트(서비스 이름)가 등록돼 있으면 그 인스턴스로 바꾸고, 없으면 원래 주소(이름 + `extra_hosts`)로 보낸다.
- Kafka·PostgreSQL·Redis·Zipkin·Elasticsearch 주소는 Eureka 와 상관없이 이름 + `extra_hosts` 그대로.

## 관측 (ADR-0030·0031)
| 화면 | 주소 | 내용 |
|---|---|---|
| Grafana | `http://192.168.0.40:3000` (admin / `.secrets/grafana-admin-password`) | 대시보드 **PMart 개요**: PC별 Windows C: 여유(`host-disk`, :9100), 앱 UP/DOWN, 미처리 메시지, HTTP 요청·5xx·응답 시간, Kafka 소비 지연, JVM 힙, DB 커넥션 |
| Prometheus | `http://192.168.0.40:9090` | 수집 대상 = `placement.conf` 의 모든 Spring 앱(render 가 `monitoring/prometheus.yml` 생성), 보존 7일 또는 2GB |
| Alertmanager | `http://192.168.0.40:9093` | 알림 규칙 `monitoring/alert-rules.yml`(앱 DOWN·미처리 메시지·소비 지연·5xx·**Windows C: 여유 15%/10GB 미만**). **전송 채널 없음** — 화면에서 본다 |
| Kibana | `http://192.168.0.40:5601` | 앱 로그(Discover, 데이터 뷰 `pmart-logs`). `traceId:"…"` 로 한 요청을 전 서비스에서 따라간다. 3일(ILM) 또는 색인 1GB·전체 4GB(log-janitor)에서 삭제. 인프라(kafka·connect 등) 로그와 초당 500건을 넘는 폭주 구간은 빠진다 — `lan.sh logs` 로 본다 |
| 로그 ES | `http://192.168.0.40:9200` (`es-logs`) | 앱 로그(`pmart-logs-*`)만, ADR-0038 |
| 검색 ES | `http://192.168.0.222:9200` (`es-search`) | 상품 검색 색인(`products`·`sku-prices`·`sku-handlings` 별칭, 재색인은 `elastic/REINDEX.md`, nori+n-gram)만, ADR-0031·0038 |
| Kafka 지표 | `http://192.168.0.40:9308/metrics` (`kafka-exporter`) | 브로커 수·파티션 리더·ISR·소비 그룹 지연, ADR-0037 |

- 로그 수집(Elastic Stack, ADR-0031): PC 마다 `log-shipper`(Filebeat)가 Docker Desktop VM 의 `/var/lib/docker/containers` 를 읽어(앱 JSON 로그만, 초당 500건, 메모리 큐) pc4 `logstash-logs:5044` 로 보낸다 → es-logs `pmart-logs-YYYY.MM.DD`. render 가 모든 PC 에 자동으로 붙인다. `es-init-logs` 는 로그 ILM·색인 틀·Kibana 데이터 뷰를, `es-init-search`(pc3)는 검색 색인·별칭을 만들고 끝나며(배포마다 다시 돌아도 같다) 같은 쪽 Logstash 는 그 뒤에 뜬다. `log-janitor` 가 5분마다 es-logs 크기 상한을 지킨다(ADR-0038).
- 상품 검색 색인: pc3 `logstash-search` 의 파이프라인 `search-index`(search-service 소유 `src/main/logstash/search-index.conf`)가 이력 토픽 3개를 읽어 es-search 에 색인한다(소비 그룹은 `elastic/search-index.env` 의 `PMART_SEARCH_GROUP_ID`). 재색인은 `elastic/reindex.sh`·[`elastic/REINDEX.md`](elastic/REINDEX.md)(새 색인 + 새 소비 그룹 + 별칭 교체).
- Kafka(ADR-0037): 3노드 KRaft 클러스터 kafka-1(pc2)·kafka-2(pc3)·kafka-3(pc4), 복제 3·min ISR 2, 브로커 자동 토픽 생성 끔. `lan.sh deploy` 는 대상 PC 의 Kafka 노드를 모두 먼저 띄우고 기다린 뒤 `kafka/topics-init.sh` 를 실행한다(노드 하나만으로는 healthy 가 되지 않는다).
- Kafka 이벤트 이력(ADR-0034): 이력 토픽 3개만 무제한 보관(`topics-init.sh` 가 멱등 보정). 토픽 크기(리더 기준)·보관 설정 지표는 `watch.sh` 가 kafka-1 에서 측정해 pc2 `host-disk` 가 내보낸다. 백업·복원·소비 그룹 처리·vhdx 주의는 [`kafka/README.md`](kafka/README.md).
- 미처리 메시지: 재시도(3회)를 다 쓴 Kafka 메시지는 `{topic}.DLT` 와 그 서비스 DB `dead_letter` 에 남는다. 원인을 고친 뒤
  ```bash
  curl -s http://<PC IP>:<포트>/api/v1/internal/dead-letters?status=OPEN            # 목록
  curl -s http://<PC IP>:<포트>/api/v1/internal/dead-letters/<id>                   # 본문·헤더
  curl -s -X POST -H 'Content-Type: application/json' -d '{"actor":"이름"}' http://<PC IP>:<포트>/api/v1/internal/dead-letters/<id>/replays
  curl -s -X POST -H 'Content-Type: application/json' -d '{"actor":"이름","reason":"사유"}' http://<PC IP>:<포트>/api/v1/internal/dead-letters/<id>/discards
  ```
  DB 가 없는 search-service 는 `dead_letter` 없이 `{topic}.DLT` 에만 남는다(색인은 버전 비교로 멱등 — 다시 넣으면 된다).

## API 문서 (Swagger UI, 2026-11-06)
- `http://192.168.0.40:8090` — 구성요소 `api-docs`(`swaggerapi/swagger-ui`, api-gateway PC 에 자동으로 붙음). 위쪽 목록에서 서비스를 고른다.
- 명세는 `contracts/openapi/*.yaml`(계약 정본) 그대로다. render 가 복사하면서 `servers` 만 게이트웨이 절대 주소(`http://<게이트웨이 PC>:8080/api/v1`)로 바꾼다 — 그래서 "Try it out" 은 게이트웨이를 거친다(`/internal/**` 은 게이트웨이가 막는다).
- 호출하려면 **Authorize** 에 토큰(`Bearer` 없이 값만)을 넣는다. 보기만은 토큰 없이 된다. 게이트웨이 CORS 에 `http://192.168.0.40:8090` 이 있어야 한다(pmart-config `api-gateway-lan.yml`, 바꾸면 게이트웨이 재시작).
- 계약을 고친 뒤 반영: `lan.sh deploy <게이트웨이 PC> api-docs`(파일만 바뀌면 컨테이너는 그대로 — 브라우저 새로 고침). LAN 전용, 운영에는 띄우지 않는다.

## 장애 대비 (2026-10-27, `docs/worklog/2026-10-27.md`)
- **컨테이너 고정 IP**: compose 네트워크 `172.28.0.0/24`, 구성요소는 배치 순서대로 `.10~`, 자동 배정은 `.192/26`.
  Docker Desktop VM 이 리셋되면 새 VM 이 이전 포트 전달 규칙(옛 컨테이너 IP)을 복원하는데, IP 가 바뀌면 LAN 포트가 엉뚱한 컨테이너로 간다.
  네트워크 설정을 바꾼 뒤에는 `lan.sh down <pc>` → `lan.sh deploy <pc>` (볼륨은 남는다).
- **Connect 로그**: 파일 appender 를 끄고 stdout 만(json-file 20MB×3 제한). `org.apache.kafka.clients.admin.internals` 는 WARN.
- **감시**: `deploy/lan/watch.sh [--dry-run]`. 앱 health, PostgreSQL SSLRequest, Kafka ApiVersions(kafka-1~3 각각), Redis PING, ES 클러스터 상태(es-search·es-logs), logstash-logs Beats 포트 TCP 연결(HTTP 아님), logstash-search API(SSH, LAN 포트 없음), Zipkin, Connect 커넥터·작업 RUNNING 을
  PC 1 에서 점검한다. 같은 구성요소가 2회 연속 실패하면 `lan.sh restart`(PC 당 10분 쿨다운), 전부 실패면 'PMart Docker Desktop Start' 예약 작업부터.
  상태·로그는 `~/.pmart-lan-watch/` (`watch.log`, `last-<pc>`). Kafka 가 재시작되면 Connect 작업이 UNASSIGNED 로 남는데(재배정 안 됨), 이것도 감시가 Connect 재시작으로 복구한다.

## IP 변경 시 복구 (2026-10-28)
PC 2·3·4·5 는 DHCP 로 IP 를 받는다(공유기는 우리가 설정할 수 없음, 지금까지는 같은 IP 가 나왔다). 반면 `placement.conf` 의 IP 는
각 PC compose 의 `extra_hosts`·Kafka 광고 주소·Connect 의 DB 주소에 그대로 들어가므로, 한 PC 의 IP 가 바뀌면 **모든 PC** 를 다시 배포해야 한다.

- **PC 1 도 배포 대상**(2026-11-15 — 감시 묶음 zipkin·prometheus·alertmanager·grafana): PC 1 에는 SSH 서버가 없어 `lan.sh`·`run-ps.sh` 가 `pmart-pc1` 은 이 PC 에서 바로 실행·복사한다. `%USERPROFILE%\.docker-pmart\config.json` = `cliPluginsExtraDirs`(compose 플러그인)만. 빌드 PC 라 꺼져도 업무가 멈추지 않는 것만 둔다.
- **SSH 는 컴퓨터 이름으로 접속**한다(PC 1 `~/.ssh/config`: `HostName DS603-12`/`D603-15`/`D603-16`, `AddressFamily inet`).
  - 이 LAN 에는 PC 이름을 아는 DNS 가 없어(DNS 서버 = 인터넷 업체) 이름은 LLMNR/NetBIOS 방송으로만 찾는다 — 상대 PC 가 바쁘면 가끔 `Could not resolve hostname`(2026-11-15 배포 중 두 번). `run-ps.sh`·`lan.sh copy_files` 는 이름 해석 실패만 3초 쉬고 최대 3번 다시 한다(원격에서 아무것도 돌지 않은 실패라 안전). 원인·근본 해결 방법·확인 명령은 `docs/operations/2026-11-15-ssh-hostname-resolution.md`.
  호스트 키는 `HostKeyAlias`(`pmart-pc2` 등) 이름으로 `known_hosts` 에 있어 IP 가 바뀌어도 같은 키로 검증한다(검증은 끄지 않는다).
  그래서 IP 가 바뀌어도 `lan.sh`·`run-ps.sh` 는 그대로 접속된다.
- **감지**: `watch.sh` 가 5분마다 이름으로 IP 를 조회한다(`ipcheck.sh`). 판정이 바뀔 때만 `~/.pmart-lan-watch/watch.log` 에 남고, 현재 판정은 `ip-<pc>` 파일.

  | 판정 | 뜻 | 감시 동작 |
  |---|---|---|
  | `OK` | 조회된 LAN 주소 중 배치 IP 가 있음 | 평소대로 |
  | `LOOKUP_FAIL` / `NO_LAN_ADDR` | 이름 조회 실패 / LAN 서브넷 주소 없음(IPv6 링크 로컬·WSL 가상 어댑터만) — **IP 변경 아님** | 평소대로 |
  | `UNVERIFIED <ip>` | 이름이 다른 LAN 주소를 가리키나 그 주소의 SSH 호스트 키가 이 PC 와 다르거나 응답 없음 | 평소대로, 사람이 확인 |
  | `AMBIGUOUS <ip들>` | 호스트 키가 일치하는 LAN 주소가 여럿 | 평소대로, 사람이 확인 |
  | `CHANGED <새 ip>` | 다른 LAN 주소에서 **이 PC 의 호스트 키가 확인됨** = IP 변경 확정 | **모든 재시작 보류**(재시작으로는 복구 안 됨) |
- 수동 확인: `deploy/lan/ipcheck.sh pmart-pc4 192.168.0.40` → 판정 한 줄.

### 절차 (`CHANGED` 가 떴을 때)
1. 확인: `cat ~/.pmart-lan-watch/ip-*`, `deploy/lan/ipcheck.sh <별칭> <배치 IP>`, `ssh <별칭> hostname`(이름으로 접속되는지).
2. `deploy/lan/placement.conf` 에서 그 PC 의 IP 만 새 IP 로 고친다(감시는 이 파일을 고치지 않는다).
3. `deploy/lan/lan.sh render` → `grep -rn <옛 IP> deploy/lan/build/` 결과가 비어 있는지 확인.
4. `deploy/lan/lan.sh deploy all` — 바뀐 PC 뿐 아니라 **전체**(다른 PC 의 `extra_hosts` 도 바뀜). 컨테이너는 다시 만들어지지만 볼륨(DB·Kafka 데이터)은 남는다.
5. 확인: `deploy/lan/watch.sh --dry-run` 이 PC 마다 OK, `ip-<pc>` 가 `OK`, Connect 커넥터 전부 RUNNING(`curl http://<pc3 IP>:8083/connectors?expand=status`),
   게이트웨이 `http://<pc4 IP>:8080` 응답.
6. `placement.conf` 변경을 커밋하고 worklog 에 남긴다.

## 사내 메일 (2026-11-12, ADR-0056 결정 9)

- PC 5: `mailserver`(docker-mailserver 16.0.1, `pmart.com` 로컬 전용, **LAN 포트 없음**)·`roundcube`(1.7.6, **https://192.168.0.101:8190**, 사설 CA). 설정 `deploy/lan/mail/`.
- 밖으로 보내지 않는다: `mail/postfix-main.cf` 의 `default_transport`·`relay_transport` = error(외부 주소는 `5.7.1 PMart LAN mail stays inside pmart.com` 으로 반송).
- 같은 PC Docker 네트워크(172.28.0.0/24)는 `PERMIT_DOCKER=connected-networks` — Keycloak 이 HELO 로 컨테이너 ID 를 보내 FQDN 검사에 걸렸다.
- 기본 사서함 `no-reply@`(Keycloak 발신)·`postmaster@` 은 `mail-accounts-init` 이 계정 파일에 없을 때만 넣는다(비밀번호 `.secrets/mail-*-password`, 서버엔 해시만). 계정 파일은 `mail-config` 볼륨 — 직원 사서함은 나중에 mail-provisioner(hr-service 와 함께).
- 사서함 수동 추가(예비): `ssh pmart-pc5 docker exec pmart-lan-mailserver-1 setup email add <id>@pmart.com <비밀번호>`(별도 터미널), 목록 `setup email list`.
- Keycloak `pmart-staff` SMTP = `mailserver:25`, 발신 `no-reply@pmart.com`(Realm 파일·실행 중 Realm 둘 다).
- `postfix-main.cf`·Roundcube 설정 파일만 바꾸면 compose 가 컨테이너를 다시 만들지 않는다 → `lan.sh restart pc5 mailserver`(또는 roundcube).

## LAN HTTPS 사설 CA (2026-11-12, `docs/architecture/lan-https-private-ca.md`)

- 루트 CA: 공개 인증서 `deploy/lan/tls/pmart-lan-root-ca.crt`(커밋, 기기·앱에 설치), 개인키 `.secrets/tls/pmart-lan-root-ca.key`(10년, `init-secrets.sh` 가 없을 때만 만든다 — 다시 만들면 모든 기기에 다시 설치).
- PC 서버 인증서: `lan.sh tls-issue <pc|all>` → `.secrets/tls/<pc>.crt·key`(1년). SAN = LAN IP + 컴퓨터 이름 + `tls/extra-sans.conf`. **IP 가 바뀌면 다시 발급.** `watch.sh` 가 만료 30일 전부터 하루 한 번 로그.
- 기기 신뢰: `lan.sh tls-trust <pc|all>`(PC 2~5 원격, Windows LocalMachine\Root), `lan.sh tls-trust pc1`(이 PC — **관리자 승인 창**). Firefox·안드로이드는 문서 §6.
- 아직 어느 구성요소도 HTTPS 로 뜨지 않는다 — 메일 서버(ADR-0056 2단계)부터 쓰고, 게이트웨이·Keycloak 은 ADR-0056 4단계와 같은 배포에서.

## 새 PC 추가 (2026-11-10, PC 5 192.168.0.101)

1. **그 PC 에서(사용자)**: 관리자 PowerShell 로 `deploy/lan/pc-bootstrap.ps1` 실행(그 PC 에 sshd·비밀번호 로그인이 이미 되면 PC 1 의 별도 터미널에서 `scp deploy/lan/pc-bootstrap.ps1 <사용자>@<IP>:pc-bootstrap.ps1` → `ssh <사용자>@<IP> powershell -NoProfile -ExecutionPolicy Bypass -File pc-bootstrap.ps1` — 비밀번호는 사용자가 직접 입력) — OpenSSH 서버·PC 1 LAN 공개 키·SSH 기본 셸 PowerShell·방화벽(SSH·ping, 192.168.0.0/24). 끝에 컴퓨터 이름·사용자를 출력한다.
   Docker Desktop 설치, `%USERPROFILE%\.docker-pmart` 로 Docker Hub 읽기 전용 로그인(`docker --config "$env:USERPROFILE\.docker-pmart" login`, 별도 터미널)도 사용자가 한다.
2. **PC 1**: `~/.ssh/config` 에 `Host pmart-pcN`(HostName = 컴퓨터 이름, HostKeyAlias pmart-pcN, AddressFamily inet, User, IdentityFile ~/.ssh/pmart_lan.pem) → 첫 접속 호스트 키 확인.
3. **PC 1 → SSH 로 맞춤(PC 3 기준, `docs/worklog/2026-10-28.md` 11:40)**: 방화벽 `PMart Docker published ports (LAN)`(com.docker.backend.exe), `.docker-pmart\config.json` `cliPluginsExtraDirs`, `.wslconfig` 메모리, 예약 작업 'PMart Docker Desktop Start', Docker Desktop AutoStart, 절전 끔.
4. `placement.conf` 에 줄 추가(구성요소는 사용자 결정) → `lan.sh render` → `lan.sh deploy pcN` → `watch.sh --dry-run`.

### LAN 환경에 참여하지 않는 PC 에 SSH 만 잇기 — PC 6 (2026-11-28, 192.168.0.217)
PC 6 은 같은 LAN(192.168.0.x)에 있지만 **집 Native 환경(pmart-native — LAN 서비스를 부르지 않고 혼자 도는 로컬 환경)을 만드는 PC** 다. LAN 5대 환경(PC 1~5 배포)에는 참여하지 않는다 — `placement.conf`·`render`·`deploy`·`watch` 대상이 아니고, 위 3·4 단계는 하지 않는다. PC 1 이 SSH 로 붙는 것은 **키 파일 같은 Git 밖 자료를 옮길 때만**이다(사용자 2026-11-28, 네이티브 작업 세션이 PC 1 → PC 6 으로 옮겨 감).

1. **PC 6 에서(사용자, 관리자 PowerShell)**: 위 1단계와 같이 `pc-bootstrap.ps1` 실행 — OpenSSH 서버, PC 1 LAN 공개 키(`administrators_authorized_keys`), SSH 기본 셸 PowerShell, 방화벽 SSH·ping. 스크립트는 `192.168.0.0/24` 전체에 22번을 연다 — PC 1 만 열려면 실행 뒤
   `Set-NetFirewallRule -DisplayName 'PMart SSH (LAN)' -RemoteAddress 192.168.0.32`.
   파일을 옮기는 방법: USB·공유 폴더로 `pc-bootstrap.ps1` 하나만(공개 키뿐이라 비밀 아님). Docker Hub 로그인·Docker 설정은 이 문서 대상이 아니다(PC 6 의 일).
2. **PC 1**: `~/.ssh/config` 에 `Host pmart-pc6`(HostName = PC 6 컴퓨터 이름 또는 192.168.0.217, HostKeyAlias pmart-pc6, AddressFamily inet, User = PC 6 관리자 계정, IdentityFile ~/.ssh/pmart_lan.pem) → `ssh pmart-pc6 hostname` 으로 첫 접속·호스트 키 확인. `deploy/lan/build/pc6` 은 만들지 않는다(LAN 배포 대상 아님).
3. **키 옮기기(PC 1 → PC 6, 사용자가 옮길 목록을 정한 뒤)**: 받는 폴더는 저장소 밖(예 `D:\pmart-keys\`, Git 제외 — pmart-native `runtime/.env` 의 `SECRETS_DIR`). `scp <파일> pmart-pc6:D:/pmart-keys/` 로 파일만 — 값은 화면·로그·메시지·Git 에 남기지 않는다. 옮긴 뒤 파일 이름 목록만 네이티브 작업 세션에 알린다.
   - 후보(기능마다 선택, 원본 `deploy/lan/.secrets/`): toss-secret-key·toss-client-key, kakao-rest-api-key·kakao-mobility-rest-api-key·kakao-admin-key.txt·kakao-rider-admin-key.txt, octomo-api-key.txt, pmart-123-firebase-adminsdk-*.json. pmart-clients: `apps/customer_app/.secrets/customer.env`, 두 앱 `android/app/google-services.json`(PC 6 clone 의 같은 경로로). `%USERPROFILE%\.android\debug.keystore`(세 앱 서명 — 옮기면 소셜 콘솔 그대로). pmart-rider-simulation `.env.local`.
   - **옮기지 않는다**(LAN 전용): config-encrypt-key·salt, 토큰 서명 키 3종, 사설 CA·인증서, LAN DB·Keycloak·Eureka·S3·Grafana 비밀번호, service-clients, SSH 키, registry.env. 집 환경 계정·키는 PC 6 에서 새로 만든다.
   - 상품 seed zip·사진 보정 자료(`p마트 상품\`·`상품데이터 보정\`)는 Git·공개 저장소·운영에 올리지 않는 규칙 — PC 6 로 복사할지도 사용자가 정한다.
4. 다 옮기면 PC 6 의 22번을 닫아도 된다(`Disable-NetFirewallRule -DisplayName 'PMart SSH (LAN)'`) — 다시 옮길 때 켠다.

## 주의
- 원격 작업 폴더 `%USERPROFILE%\pmart-lan`, compose 프로젝트 `pmart-lan`. 같은 PC 의 다른 프로젝트 컨테이너는 건드리지 않는다.
- `down` 은 볼륨을 남긴다. DB 를 새로 만들려면 원격에서 `docker volume rm pmart-lan_pgdata` (서비스 DB 가 늘었을 때 `init-db.sql` 은 새 볼륨에서만 실행됨).
- 서명키를 다시 만들면 발급된 토큰이 모두 무효가 된다.
- 비밀정보는 `.secrets/`·`build/` 에만 있다(둘 다 git 제외). 원격 PC 에는 `.env` 로 복사된다.

## 확인 스크립트 (deploy/lan/checks)
- `mfc_e2e.py` — MFC 처음부터 끝까지(예약 → 작업 → 피킹 → 포장 → 배차 요청 → 취소 → 복귀 → 재고 원복). 실제 API 만, 재고는 원래대로 돌아간다. 배포 뒤 `PYTHONIOENCODING=utf-8 python -I deploy/lan/checks/mfc_e2e.py`. 고객 로그인·결제·라이더 인계는 없다.
