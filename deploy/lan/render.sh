#!/usr/bin/env bash
# placement.conf → PC별 배포 파일 생성: deploy/lan/build/<pc>/{compose.yml,.env,init-db.sql,...}
# - 서비스 포트·필요 인프라(DB/Kafka/Redis/JWKS)는 각 서비스의 src/main/resources/application.yml 에서 읽는다(정본 한 곳).
# - 이미지: docker.io/heeji4445/pmart-<service>@<digest>. digest 는 jib push 결과(build/jib-image.digest). 없으면 중단.
# - 다른 PC 의 구성요소는 extra_hosts(<이름>:<IP>)로 가리킨다. 호스트 포트 = 컨테이너 포트라 주소가 어디서나 같다.
# - 비밀정보(.env)는 deploy/lan/.secrets 에서 읽어 build/ 에만 쓴다(둘 다 git 제외).
# - 컨테이너 IP 고정(SUBNET, 배치 순서대로 .10~): Docker Desktop VM 이 리셋되면 새 VM 이 이전 포트 전달 규칙(옛 컨테이너 IP)을
#   복원하는데 컨테이너는 IP 를 새로 받아 LAN 포트가 엉뚱한 컨테이너로 간다(docs/worklog/2026-10-27.md). IP 가 같으면 복원 규칙이 맞는다.
#   자동 배정은 IP_RANGE 로 몰아 고정 IP 와 겹치지 않게 한다. 네트워크 설정이 바뀌면 deploy 전에 down 이 필요하다.
set -euo pipefail
cd "$(dirname "$0")/../.."
LAN=deploy/lan
OUT=$LAN/build
SECRETS=$LAN/.secrets
REGISTRY="${PMART_IMAGE_REGISTRY:-docker.io/heeji4445}"
SUBNET_PREFIX=172.28.0   # 각 PC 의 기존 Docker 네트워크(172.17~19)와 겹치지 않는 대역

[ -f "$SECRETS/db-password" ] || { echo "비밀정보 없음: $LAN/init-secrets.sh 먼저 실행" >&2; exit 1; }

# ---- placement 읽기 (줄 이어쓰기 \ 처리) ----
declare -a PCS=()
declare -A IP=() SSH=() COMPS=() WHERE=()
while read -r name ip ssh comps; do
  [ -z "${name:-}" ] && continue
  PCS+=("$name"); IP[$name]=$ip; SSH[$name]=$ssh; COMPS[$name]="$comps"
  for c in $comps; do
    if [ "$c" = postgres ]; then WHERE[postgres-$name]=$name; continue; fi
    [ -n "${WHERE[$c]:-}" ] && { echo "중복 배치: $c (${WHERE[$c]}, $name)" >&2; exit 1; }
    WHERE[$c]=$name
  done
done < <(sed -e 's/#.*//' "$LAN/placement.conf" | sed -e ':a' -e '/\\$/N; s/\\\n//; ta' | tr -s ' \t' ' ')

# ---- Kafka KRaft 클러스터(ADR-0037): placement 의 kafka-1·kafka-2·kafka-3 ----
KAFKA_NODES=""; KAFKA_VOTERS=""; KAFKA_BOOTSTRAP=""
for c in kafka-1 kafka-2 kafka-3; do
  [ -n "${WHERE[$c]:-}" ] || continue
  KAFKA_NODES+="$c "
  KAFKA_VOTERS+="${KAFKA_VOTERS:+,}${c#kafka-}@$c:9094"
  KAFKA_BOOTSTRAP+="${KAFKA_BOOTSTRAP:+,}$c:9092"
done
[ -n "${WHERE[kafka]:-}" ] && { echo "placement.conf: kafka 대신 kafka-1·kafka-2·kafka-3 (ADR-0037)" >&2; exit 1; }
[ "$KAFKA_NODES" = "kafka-1 kafka-2 kafka-3 " ] || echo "경고: Kafka 노드가 3개가 아니다($KAFKA_NODES) — 복제 3·min ISR 2 는 3노드 전제" >&2
KAFKA_RF=3; KAFKA_MIN_ISR=2
# 클러스터 ID: 세 노드가 같아야 하고 볼륨을 만든 뒤에는 바꾸면 안 된다. 비밀이 아니므로 여기 고정(2026-11-05 생성, base64url 16바이트)
KAFKA_CLUSTER_ID=mQfULla_AX-vG10Mf2a4kw
# 토픽 크기 지표(ADR-0034)를 재는 노드·PC — 리더 기준이라 한 노드에서 클러스터 전체를 본다
KAFKA_METRICS_NODE=kafka-1

INFRA=" postgres kafka-1 kafka-2 kafka-3 connect redis zipkin s3 es-search es-logs backoffice-web hr-backoffice-web mfc-backoffice-web rider-backoffice-web cdc-backoffice-web prometheus alertmanager grafana kibana logstash-search logstash-logs log-shipper es-init-search es-init-logs host-disk log-janitor config-relay docs-relay es-exporter-search es-exporter-logs kafka-exporter api-docs rag-pgvector ollama keycloak mailserver roundcube "
# extra_hosts 에서 뺄 구성요소(아래 extra_hosts 설명). 새로 붙이는 보조 구성요소는 여기에 둔다
# RAG 서비스(ADR-0051): DB 는 같은 PC 의 rag-pgvector(서비스마다 DB·계정), 공용 postgres 에 DB 를 만들지 않는다
RAG_APPS=" ai-assistant-service customer-assistant-service embedding-service "
is_rag() { [[ "$RAG_APPS" == *" $1 "* ]]; }
# rag-pgvector·ollama(ADR-0051)는 같은 PC 의 RAG 서비스만 컨테이너 이름으로 부른다. rag-pgvector 는 LAN 포트를 열지 않는다. ollama 는 2026-11-16 부터 LAN 11434(RAG 개발 PC 용)
# RAG 서비스는 게이트웨이가 Eureka(lb://)로 찾는다 — 이름으로 부르는 다른 PC 가 없어 넣지 않는다(넣으면 다른 PC 컨테이너가 전부 재생성)
# 사내 메일(ADR-0056 결정 9)·웹메일도 같은 PC(Keycloak·hr-service)만 이름으로 부른다
# hr-service 는 게이트웨이가 Eureka 로, mail-provisioner 는 같은 PC 의 hr-service 만 이름으로 부른다(ADR-0056 5-5)
NO_EXTRA_HOST=" es-exporter-search es-exporter-logs kafka-exporter api-docs rag-pgvector ollama ai-assistant-service customer-assistant-service integration-service chunking-service embedding-service docs-relay mailserver roundcube hr-service mail-provisioner hr-backoffice-web mfc-backoffice-web rider-backoffice-web cdc-backoffice-web "
ELASTIC=$LAN/elastic
SEARCH_SVC=services/search-service
MON=$LAN/monitoring
# 검색 ES 와 로그 ES 분리(ADR-0038): es-search·logstash-search(검색 색인) / es-logs·logstash-logs·kibana(로그)
for c in elasticsearch logstash; do
  [ -n "${WHERE[$c]:-}" ] && { echo "placement.conf: $c 대신 es-search·es-logs / logstash-search·logstash-logs (ADR-0038)" >&2; exit 1; }
done
# 로그 수집(ADR-0031): es-logs 가 배치돼 있으면 모든 PC 에 log-shipper(Filebeat), 각 ES PC 에 es-init-<종류>(색인·보존 준비, 1회)을
# 자동으로 붙인다(log-janitor 는 아래 host-disk 뒤). 줄 끝에 붙여 기존 구성요소의 고정 IP 가 바뀌지 않는다.
if [ -n "${WHERE[es-logs]:-}" ]; then
  for pc in "${PCS[@]}"; do COMPS[$pc]+=" log-shipper"; done
  COMPS[${WHERE[es-logs]}]+=" es-init-logs"
fi
if [ -n "${WHERE[es-search]:-}" ]; then COMPS[${WHERE[es-search]}]+=" es-init-search"; fi
# 디스크 감시(ADR-0030): prometheus 가 있으면 모든 PC 에 host-disk(node-exporter). Docker Desktop 이 마운트한 Windows C: 의
# 실제 여유 공간을 잰다 — 컨테이너·Elasticsearch 가 보는 디스크는 1TB 가상 디스크라 실제 C: 보다 먼저 차지 않는다.
if [ -n "${WHERE[prometheus]:-}" ]; then
  for pc in "${PCS[@]}"; do COMPS[$pc]+=" host-disk"; done
fi
# 로그 색인 크기 상한(색인 1GB·전체 4GB — ILM 은 기간만 지운다)
if [ -n "${WHERE[es-logs]:-}" ]; then COMPS[${WHERE[es-logs]}]+=" log-janitor"; fi
# GitHub 웹훅 중계(ADR-0033): config-server PC 에 config-relay. 맨 뒤에 붙여 기존 구성요소의 고정 IP 가 바뀌지 않는다
if [ -n "${WHERE[config-server]:-}" ]; then
  COMPS[${WHERE[config-server]}]+=" config-relay"; WHERE[config-relay]=${WHERE[config-server]}
fi
# Elasticsearch 지표(2026-11-03, ADR-0038 로 ES 마다): es-<종류>·prometheus 가 있으면 그 ES PC 에 es-exporter-<종류>. 맨 뒤에 붙여 고정 IP 유지
for k in search logs; do
  if [ -n "${WHERE[es-$k]:-}" ] && [ -n "${WHERE[prometheus]:-}" ]; then
    COMPS[${WHERE[es-$k]}]+=" es-exporter-$k"; WHERE[es-exporter-$k]=${WHERE[es-$k]}
  fi
done
# API 문서(2026-11-06 사용자 요청): api-gateway PC 에 Swagger UI 하나 — contracts/openapi 전체를 목록으로, "Try it out" 은
# 게이트웨이(8080)로 보낸다. LAN 전용(운영에는 띄우지 않는다). 맨 뒤에 붙여 기존 구성요소의 고정 IP 유지
if [ -n "${WHERE[api-gateway]:-}" ]; then
  COMPS[${WHERE[api-gateway]}]+=" api-docs"; WHERE[api-docs]=${WHERE[api-gateway]}
fi
# 문서 저장소 웹훅 중계(ADR-0095, 2026-11-28): integration-service PC 에 docs-relay — GitHub code-and-pick/pmart-all-docs 웹훅을 smee 채널에서 받아
# integration-service 로 넘긴다(config-relay 와 같은 방식, 채널은 따로). 아래 LATE 로 줄 맨 뒤에 둔다
if [ -n "${WHERE[integration-service]:-}" ]; then
  COMPS[${WHERE[integration-service]}]+=" docs-relay"; WHERE[docs-relay]=${WHERE[integration-service]}
fi
# 위 보조 구성요소가 생긴 뒤 placement 에 더한 구성요소: 그 PC 줄의 맨 뒤(보조 구성요소 뒤)로 옮겨 기존 고정 IP 를 그대로 둔다
# (2026-11-17 사용자 "보조 컨테이너 안 건드리고 싶은데" — 줄 중간에 끼면 뒤 보조 7개가 다시 만들어진다). 새로 붙일 것도 여기에 이어 적는다
LATE=" rider-backoffice-web cdc-backoffice-web cdc-quality-service cdc-inbound-service fleet-service transport-planning-service transport-execution-service integration-service chunking-service embedding-service rag-query-service docs-relay "
for pc in "${PCS[@]}"; do
  for c in $LATE; do
    if [[ " ${COMPS[$pc]} " == *" $c "* ]]; then
      COMPS[$pc]="$(echo " ${COMPS[$pc]} " | sed "s/ $c / /")"; COMPS[$pc]+=" $c"
    fi
  done
done
# Prometheus 수집 대상: 배치된 모든 Spring 앱(인프라 제외) <PC IP>:<포트>/actuator/prometheus
apps_all() { for c in "${!WHERE[@]}"; do [[ "$c" == postgres-* || "$INFRA" == *" $c "* ]] || echo "$c"; done | sort; }
src_dir() { for d in services platform-services; do [ -d "$d/$1" ] && { echo "$d/$1"; return; }; done; echo "서비스 없음: $1" >&2; exit 1; }
yml() { echo "$(src_dir "$1")/src/main/resources/application.yml"; }
# 설정 저장소 정본(ADR-0029)으로 옮긴 앱: 저장소 최상위에 <앱>-lan.yml 이 있다. 포트 등 서비스 고유 설정은 <앱>.yml 에 있다
migrated() { [ -f "$LAN/.config-repo/$1-lan.yml" ]; }
port_of() {
  local p; p="$(awk '/^server:/{s=1;next} /^[^ ]/{s=0} s&&/^  port:/{print $2; exit}' "$(yml "$1")")"
  [ -z "$p" ] && [ -f "$LAN/.config-repo/$1.yml" ] && p="$(awk '/^server:/{s=1;next} /^[^ ]/{s=0} s&&/^  port:/{print $2; exit}' "$LAN/.config-repo/$1.yml")"
  echo "$p"
}
needs() { grep -q "\${$2" "$(yml "$1")"; }
# DB 가 필요한 앱 = Flyway 마이그레이션 폴더가 있는 앱(옮긴 앱의 yml 에는 ${PMART_DB_URL} 이 없다)
needs_db() { needs "$1" PMART_DB_URL || [ -d "$(src_dir "$1")/src/main/resources/db/migration" ]; }
db_of() { echo "${1//-/_}"; }
digest_of() {
  local f; f="$(src_dir "$1")/build/jib-image.digest"
  [ -f "$f" ] || { echo "digest 없음: $f (먼저 jib push)" >&2; exit 1; }
  cat "$f"
}

for c in redis zipkin auth-service eureka-server; do
  [ -n "${WHERE[$c]:-}" ] || echo "경고: $c 가 배치되지 않음" >&2
done
if [ -n "${WHERE[config-relay]:-}" ] && [ ! -f "$SECRETS/smee-url" ]; then
  echo "비밀정보 없음: $SECRETS/smee-url ($LAN/init-secrets.sh 먼저 — 웹훅 중계 채널, ADR-0033)" >&2; exit 1
fi
if [ -n "${WHERE[docs-relay]:-}" ] && [ ! -f "$SECRETS/rag-docs-smee-url" ]; then
  echo "비밀정보 없음: $SECRETS/rag-docs-smee-url (문서 저장소 웹훅 채널, ADR-0095 — pmart-all-docs 웹훅 Payload URL 과 같은 값)" >&2; exit 1
fi

secret() { tr -d '\r\n' < "$SECRETS/$1"; } # Windows openssl 은 CRLF 로 쓴다

# ---- Vault(ADR-0100): 서비스가 자기 비밀을 pc2 Vault 의 config/<앱>,lan 에서 직접 읽는다(Spring Cloud Vault, 서비스별 AppRole) ----
# 켤 앱 = deploy/lan/vault-apps.conf(git 추적 — 한 줄에 앱 이름 또는 all, # 주석). 셸의 PMART_VAULT_APPS 가 있으면 그것이 이긴다(시험용).
#   파일을 정본으로 두는 까닭: lan.sh deploy 는 매번 render 한다 — 켠 목록이 셸 변수뿐이면 다른 세션의 배포가 Vault 설정을 빼고,
#   {cipher} 삭제 뒤에는 비밀 없이 기동 실패한다(2026-11-28 #566 검토). 비면 지금처럼(Vault 를 쓰지 않음). 한 서비스 먼저 → 전체(ADR-0100 결정 9).
# 켠 앱은 배포마다 secret-id 를 새로 받는다(배포 토큰 pmart-deployer). secret-id 는 .env 에만.
# config-server 는 대상이 아니다(설정 저장소·자기 비밀은 configtree).
VAULT_APPS_FILE=$LAN/vault-apps.conf
if [ -n "${PMART_VAULT_APPS+x}" ]; then
  VAULT_APPS=" $PMART_VAULT_APPS "
  [ -f "$VAULT_APPS_FILE" ] && echo "Vault: PMART_VAULT_APPS 가 $VAULT_APPS_FILE 보다 우선(셸 값:${VAULT_APPS% })" >&2
else
  VAULT_APPS=" $( [ -f "$VAULT_APPS_FILE" ] && sed -e 's/#.*//' "$VAULT_APPS_FILE" | tr -s ' \t\r\n' ' ' ) "
fi
# 공백 정리: 앞뒤를 꼭 한 칸으로(주석 줄이 빈 칸을 남겨 "  all  " 이 되면 아래 all 비교가 빗나간다 — 2026-11-28 전체 전환 때 0 개로 렌더됨)
VAULT_APPS=" $(echo $VAULT_APPS) "
VAULT_URI="${PMART_VAULT_URI:-https://192.168.0.34:8200}"
VAULT_CA=$LAN/tls/pmart-lan-root-ca.crt
vault_any() { [[ "$VAULT_APPS" =~ [^[:space:]] ]]; }
vault_on() { [ "$1" != config-server ] && vault_any && { [ "$VAULT_APPS" = " all " ] || [[ "$VAULT_APPS" == *" $1 "* ]]; }; }
# Vault API — 배포 토큰은 표준입력으로 curl 헤더에(-H @-), 명령줄에 두지 않는다. 실패하면 빈 출력
vault_api() {
  printf 'X-Vault-Token: %s\n' "$VAULT_DEPLOYER_TOKEN" \
    | curl -sf --cacert "$VAULT_CA" --ssl-revoke-best-effort -H @- -X "$1" "$VAULT_URI/v1/$2" 2>/dev/null || true
}
json_field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
if vault_any; then
  [ -f "$VAULT_CA" ] || { echo "Vault CA 없음: $VAULT_CA" >&2; exit 1; }
  # 배포 토큰: ① 셸의 VAULT_DEPLOYER_TOKEN ② 파일(PMART_VAULT_TOKEN_FILE, 기본 .secrets/vault-deployer-token — git 제외.
  # 사용자가 메모장으로 저장해 **계속 둔다** — Vault 앱을 켜면 모든 배포가 토큰을 쓰고 세션은 숨김 입력에 칠 수 없다(2026-11-28 사용자 "추천대로").
  # BOM·CRLF·공백은 뗀다) ③ 숨김 입력. 어느 쪽이든 명령줄·로그에는 안 남는다
  VAULT_TOKEN_FILE="${PMART_VAULT_TOKEN_FILE:-$SECRETS/vault-deployer-token}"
  if [ -z "${VAULT_DEPLOYER_TOKEN:-}" ] && [ -f "$VAULT_TOKEN_FILE" ]; then
    VAULT_DEPLOYER_TOKEN="$(sed -e '1s/^\xEF\xBB\xBF//' "$VAULT_TOKEN_FILE" | tr -d '\r\n\t ')"
    echo "Vault 배포 토큰: 파일 $VAULT_TOKEN_FILE" >&2
  fi
  if [ -z "${VAULT_DEPLOYER_TOKEN:-}" ]; then
    read -rsp "Vault 배포 토큰(pmart-deployer, 입력은 보이지 않음): " VAULT_DEPLOYER_TOKEN; echo >&2
  fi
  [ -n "$(vault_api GET auth/token/lookup-self | json_field id)" ] \
    || { echo "Vault 배포 토큰이 안 맞거나 $VAULT_URI 에 닿지 않음(sealed?) — pc2 deploy/vault/vault.sh status" >&2; exit 1; }
  echo "Vault: $VAULT_URI — 켠 앱:${VAULT_APPS% }" >&2
fi

DB_PASSWORD="$(secret db-password)"
rm -rf "$OUT"

# ---- 설정 저장소(ADR-0029) ----
# Spring 앱의 설정은 config-server 가 GitHub code-and-pick/pmart-config 에서 준다. 정본은 그 저장소(사람이 고친다) — 여기서 만들지 않는다.
# 이 스크립트는 compose·.env·config-server 비밀만 만들고, 배치(placement.conf)와 저장소가 어긋나지 않는지만 확인한다.
for f in config-encrypt-key config-encrypt-salt config-password config-deploy-key config-webhook-secret eureka-password; do
  [ -f "$SECRETS/$f" ] || { echo "비밀정보 없음: $SECRETS/$f ($LAN/init-secrets.sh 먼저)" >&2; exit 1; }
done
CFG_CLONE=$LAN/.config-repo
[ -f "$CFG_CLONE/application.yml" ] || { echo "설정 저장소 사본 없음: $CFG_CLONE (lan.sh deploy·config-push 가 clone 한다)" >&2; exit 1; }
for pc in "${PCS[@]}"; do
  dir=$OUT/$pc; mkdir -p "$dir"
  comps=" ${COMPS[$pc]} "

  # extra_hosts: 이 PC 에 없는 모든 구성요소
  hosts=""
  for c in "${!WHERE[@]}"; do
    [ "${WHERE[$c]}" = "$pc" ] && continue
    # 다른 PC 가 이름으로 부르지 않는 구성요소(Prometheus 는 IP 로 수집)는 넣지 않는다 — 넣으면 다른 PC 의 x-common 이 바뀌어
    # 그 PC 의 컨테이너(Kafka·Postgres 포함)가 전부 재생성된다(2026-11-03). 이미 배포된 이름은 같은 이유로 그대로 둔다
    [[ " $NO_EXTRA_HOST " == *" $c "* ]] && continue
    hosts+="      - \"$c:${IP[${WHERE[$c]}]}\""$'\n'
  done
  hosts="$(printf '%s' "$hosts" | sort)"
  VAULT_ENV_LINES="" # 이 PC 앱들의 Vault secret-id(아래 .env 에)

  {
    echo "# 생성 파일(deploy/lan/render.sh) — 직접 고치지 말고 placement.conf·render.sh 를 고친다."
    echo "# PC: $pc (${IP[$pc]})"
    echo "name: pmart-lan"
    echo
    echo "x-common: &common"
    echo "  restart: unless-stopped"
    echo "  logging: { driver: json-file, options: { max-size: \"20m\", max-file: \"3\" } }"
    if [ -n "$hosts" ]; then echo "  extra_hosts:"; echo "$hosts" | sed 's/^      /    /'; fi
    echo
    echo "services:"

    n=10
    for c in ${COMPS[$pc]}; do
      ip_addr="$SUBNET_PREFIX.$n"; n=$((n + 1))
      case "$c" in
      postgres)
        cat <<EOF
  postgres:
    <<: *common
    image: postgres:17-alpine
    # 컨테이너 안에서도 15432 로 듣는다 → 같은 PC(별칭 postgres-$pc)와 다른 PC(extra_hosts) 모두 postgres-$pc:15432
    command: ["postgres", "-p", "15432", "-c", "wal_level=logical", "-c", "max_wal_senders=20", "-c", "max_replication_slots=20", "-c", "max_connections=200"]
    environment:
      POSTGRES_USER: pmart
      POSTGRES_PASSWORD: \${PMART_DB_PASSWORD}
      POSTGRES_DB: pmart
    ports: ["15432:15432"]
    networks: { default: { aliases: [postgres-$pc], ipv4_address: $ip_addr } }
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./init-db.sql:/docker-entrypoint-initdb.d/10-init-db.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U pmart -p 15432"]
      interval: 5s
      timeout: 3s
      retries: 30

EOF
        ;;
      kafka-[123])
        # KRaft 클러스터 노드(ADR-0037): 노드마다 broker+controller. 이름 kafka-N 의 N 이 node id
        cat <<EOF
  $c:
    <<: *common
    image: apache/kafka:4.1.1
    # 9092 = 클라이언트·브로커 사이, 9094 = 컨트롤러 투표(9093 은 Alertmanager 가 쓴다)
    ports: ["9092:9092", "9094:9094"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      KAFKA_NODE_ID: ${c#kafka-}
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: $KAFKA_VOTERS
      # 같은 PC 는 컨테이너 DNS, 다른 PC 는 extra_hosts(kafka-N → 그 PC IP)로 kafka-N:9092·9094 에 닿는다
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9094
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://$c:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: $KAFKA_RF
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: $KAFKA_RF
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: $KAFKA_MIN_ISR
      KAFKA_DEFAULT_REPLICATION_FACTOR: $KAFKA_RF
      KAFKA_MIN_INSYNC_REPLICAS: $KAFKA_MIN_ISR
      # 브로커 자동 생성 끔(ADR-0037 결정 4) — 토픽은 Connect·Stream 바인더·topics-init.sh 가 명시적으로 만든다(메타데이터 요청만으로 생기지 않게)
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
      KAFKA_LOG_DIRS: /var/lib/kafka/data
      CLUSTER_ID: $KAFKA_CLUSTER_ID
    volumes:
      # 단일 노드 때의 kafkadata 와 다른 이름 — 옛 볼륨은 롤백용으로 남는다(계획 6장)
      - kafkacluster:/var/lib/kafka/data
    mem_limit: 2g
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 30

EOF
        ;;
      connect)
        db_hosts=""
        for p in "${PCS[@]}"; do [ -n "${WHERE[postgres-$p]:-}" ] && db_hosts+="postgres-$p:15432 "; done
        cat <<EOF
  connect:
    <<: *common
    image: quay.io/debezium/connect:3.7
    ports: ["8083:8083"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      # 로그는 stdout 만(파일 appender 끔) → 위 json-file 크기 제한을 받는다. 파일 appender 는 회전만 하고 지우지 않아
      # Kafka 불통 때 AdminClient rebootstrap 반복 로그로 /kafka/logs 가 142GB 까지 찼다(2026-10-25~26).
      CONNECT_LOG4J_LOGGERS: "INFO, stdout"
      # 같은 반복 로그(AdminMetadataManager "Rebootstrapping …")의 출처. 이름은 소문자 패키지만 가능(엔트리포인트가 소문자로 바꾼다)
      CONNECT_LOG4J_LOGGER_ORG_APACHE_KAFKA_CLIENTS_ADMIN_INTERNALS: WARN
      BOOTSTRAP_SERVERS: $KAFKA_BOOTSTRAP
      GROUP_ID: pmart-connect
      CONFIG_STORAGE_TOPIC: _connect.configs
      OFFSET_STORAGE_TOPIC: _connect.offsets
      STATUS_STORAGE_TOPIC: _connect.status
      # 내부 토픽 복제 수는 CONNECT_ 접두어로만 들어간다 — Debezium 엔트리포인트는 *_STORAGE_TOPIC 만 옮기고,
      # 접두어 없는 *_STORAGE_REPLICATION_FACTOR 는 무시해 이미지 기본 파일의 1 로 만들었다(2026-11-06 전환, 재배치로 3 복구)
      CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR: $KAFKA_RF
      CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR: $KAFKA_RF
      CONNECT_STATUS_STORAGE_REPLICATION_FACTOR: $KAFKA_RF
      KEY_CONVERTER: org.apache.kafka.connect.storage.StringConverter
      VALUE_CONVERTER: org.apache.kafka.connect.storage.StringConverter
      CONNECT_REST_ADVERTISED_HOST_NAME: connect
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8083/connectors >/dev/null"]
      interval: 10s
      timeout: 5s
      retries: 30

  connector-registrar:
    <<: *common
    image: postgres:17-alpine
    depends_on:
      connect: { condition: service_healthy }
    # 마운트한 스크립트를 셸이 계속 열고 있으면 Windows(Docker Desktop)가 호스트 파일 덮어쓰기를 막는다(배포 scp 실패, 2026-11-03) → 복사해서 실행
    entrypoint: ["/bin/sh", "-c", "cp /registrar/connector-registrar.sh /tmp/r.sh && exec /bin/sh /tmp/r.sh"]
    environment:
      CONNECT_URL: http://connect:8083
      DB_HOSTS: "${db_hosts% }"
      DB_USER: pmart
      DB_PASSWORD: \${PMART_DB_PASSWORD}
      INTERVAL: "15"
    volumes:
      - ./connector-registrar.sh:/registrar/connector-registrar.sh:ro
      - ./connectors:/connectors:ro

EOF
        ;;
      redis)
        cat <<EOF
  redis:
    <<: *common
    # 게이트웨이 처리율 제한·delivery-tracking 위치 캐시. 원장 용도 금지.
    image: redis:7-alpine
    ports: ["6379:6379"]
    networks: { default: { ipv4_address: $ip_addr } }
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20

EOF
        ;;
      s3)
        # 상품 이미지 저장소(SeaweedFS mini, ADR-0016). deploy/local 과 같은 이미지·설정. 자격 증명은 .secrets 에서, 시작 시 파일로 쓴다.
        cat <<EOF
  s3:
    <<: *common
    image: chrislusf/seaweedfs:4.48
    environment:
      S3_ACCESS_KEY: \${PMART_S3_ACCESS_KEY}
      S3_SECRET_KEY: \${PMART_S3_SECRET_KEY}
    entrypoint:
      - sh
      - -c
      - >-
        umask 077 &&
        printf '{"identities":[{"name":"app","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin","Read","List","Tagging","Write"]}]}'
        "\$\$S3_ACCESS_KEY" "\$\$S3_SECRET_KEY" > /tmp/s3.json &&
        chown seaweed /tmp/s3.json &&
        exec /entrypoint.sh mini -dir=/data -s3.config=/tmp/s3.json -admin.ui=false
        -s3.port.iceberg=0 -s3.port.lance=0 -bucket=pmart-product-content,pmart-customer,pmart-delivery-evidence,pmart-review,pmart-case,pmart-mfc-quality-evidence,pmart-storefront
    ports: ["8333:8333"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - s3data:/data
    healthcheck:
      test: ["CMD-SHELL", "curl -s -o /dev/null http://127.0.0.1:8333/"]
      interval: 5s
      timeout: 3s
      retries: 30

EOF
        ;;
      backoffice-web)
        # 임시 백오피스(React SPA) 정적 서빙 — CORS 출처 http://d603-16(pmart-config api-gateway-lan.yml)과 같은 PC·80. 파일은 render 가 backoffice/ 에 둔다
        cat <<EOF
  backoffice-web:
    <<: *common
    image: nginx:1.27-alpine
    ports: ["80:80", "443:443"] # HTTPS(ADR-0057). 80 은 HTTPS 로 넘기기만(2026-11-13 HTTP 닫음, T3)
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./backoffice:/usr/share/nginx/html:ro
      - ./backoffice-nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./tls:/etc/pmart-tls:ro # 이 PC 서버 인증서·키(LAN 사설 CA)
    healthcheck:
      test: ["CMD-SHELL", "nc -z 127.0.0.1 443 || exit 1"] # HTTPS 대기만(80 은 301 이라 wget 이 사설 인증서 HTTPS 로 따라가다 실패 — 2026-11-15)
      interval: 10s
      timeout: 3s
      retries: 10

EOF
        ;;
      hr-backoffice-web)
        # 인사팀 백오피스(React SPA, pmart-clients web/hr-backoffice `npm run build:lan`, ADR-0056 5-6) — 지금 백오피스와 같은 nginx 설정, 8081.
        # 게이트웨이 CORS 출처 http://192.168.0.40:8081·http://d603-16:8081(api-gateway-lan.yml). 파일은 render 가 hr-backoffice/ 에 둔다
        cat <<EOF
  hr-backoffice-web:
    <<: *common
    image: nginx:1.27-alpine
    ports: ["8081:80", "8444:443"] # HTTPS 8444(ADR-0057). 8081 은 https://…:8444 로 넘기기만(2026-11-13 HTTP 닫음)
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./hr-backoffice:/usr/share/nginx/html:ro
      - ./backoffice-nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./tls:/etc/pmart-tls:ro # 이 PC 서버 인증서·키(LAN 사설 CA)
    healthcheck:
      test: ["CMD-SHELL", "nc -z 127.0.0.1 443 || exit 1"] # HTTPS 대기만(80 은 301 이라 wget 이 사설 인증서 HTTPS 로 따라가다 실패 — 2026-11-15)
      interval: 10s
      timeout: 3s
      retries: 10

EOF
        ;;
      mfc-backoffice-web)
        # MFC 백오피스(별도 앱, 2026-11-15 사용자 "백오피스 mfc는 떼어내고" — pmart-clients web/mfc-backoffice(제안) `npm run build:lan`) — 같은 nginx 설정, 8445.
        # 게이트웨이 CORS 출처 https://192.168.0.40:8445·https://d603-16:8445(api-gateway-lan.yml). 파일은 render 가 mfc-backoffice/ 에 둔다
        cat <<EOF
  mfc-backoffice-web:
    <<: *common
    image: nginx:1.27-alpine
    ports: ["8082:80", "8445:443"] # HTTPS 8445(ADR-0057). 8082 는 https://…:8445 로 넘기기만
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./mfc-backoffice:/usr/share/nginx/html:ro
      - ./backoffice-nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./tls:/etc/pmart-tls:ro # 이 PC 서버 인증서·키(LAN 사설 CA)
    healthcheck:
      test: ["CMD-SHELL", "nc -z 127.0.0.1 443 || exit 1"] # HTTPS 대기만(80 은 301 이라 wget 이 사설 인증서 HTTPS 로 따라가다 실패 — 2026-11-15)
      interval: 10s
      timeout: 3s
      retries: 10

EOF
        ;;
      rider-backoffice-web)
        # 라이더 백오피스(별도 앱, 2026-11-17 사용자 "라이더 백오피스 새로 만들어야 할 것 같아서" — pmart-clients web/rider-backoffice(제안) `npm run build:lan`) — 같은 nginx 설정, 8446.
        # 게이트웨이 CORS 출처 https://192.168.0.40:8446·https://d603-16:8446(api-gateway-lan.yml). 파일은 render 가 rider-backoffice/ 에 둔다.
        # HTTP 는 8084(8083 은 PC 3 Debezium connect 와 헷갈려 피함)
        cat <<EOF
  rider-backoffice-web:
    <<: *common
    image: nginx:1.27-alpine
    ports: ["8084:80", "8446:443"] # HTTPS 8446(ADR-0057). 8084 는 https://…:8446 로 넘기기만
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./rider-backoffice:/usr/share/nginx/html:ro
      - ./backoffice-nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./tls:/etc/pmart-tls:ro # 이 PC 서버 인증서·키(LAN 사설 CA)
    healthcheck:
      test: ["CMD-SHELL", "nc -z 127.0.0.1 443 || exit 1"] # HTTPS 대기만(mfc-backoffice-web 과 같은 이유)
      interval: 10s
      timeout: 3s
      retries: 10

EOF
        ;;
      cdc-backoffice-web)
        # DC 백오피스(별도 앱, 2026-11-25 사용자 "Dc 백오피스 만들어줘 … 통합 백오피스에서 sso로 클릭해서 이동" — pmart-clients web/cdc-backoffice(제안) `npm run build:lan`) — 같은 nginx 설정, 8447.
        # 게이트웨이 CORS 출처 https://192.168.0.40:8447·https://d603-16:8447(api-gateway-lan.yml). 파일은 render 가 cdc-backoffice/ 에 둔다. HTTP 8085 → 8447. 계획 docs/cdc/cdc-backoffice-plan.md
        cat <<EOF
  cdc-backoffice-web:
    <<: *common
    image: nginx:1.27-alpine
    ports: ["8085:80", "8447:443"] # HTTPS 8447(ADR-0057). 8085 는 https://…:8447 로 넘기기만
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./cdc-backoffice:/usr/share/nginx/html:ro
      - ./backoffice-nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./tls:/etc/pmart-tls:ro # 이 PC 서버 인증서·키(LAN 사설 CA)
    healthcheck:
      test: ["CMD-SHELL", "nc -z 127.0.0.1 443 || exit 1"] # HTTPS 대기만(mfc-backoffice-web 과 같은 이유)
      interval: 10s
      timeout: 3s
      retries: 10

EOF
        ;;
      es-search|es-logs)
        # Elasticsearch(ADR-0031, Eum 과 같은 판). ADR-0038: es-search = 상품 검색 색인만, es-logs = 앱 로그만. 보안 끔(LAN 시험망).
        # 색인을 자동으로 만들지 않는다 — 검색 ES 는 시스템 색인만, 로그 ES 는 pmart-logs-*·zipkin-*(추적)와 시스템 색인만(매핑 없는 색인·엉뚱한 쪽 색인이 생기지 않게).
        # nori(한국어 분석)는 검색 ES 에만 — 시작 때 없으면 설치(인터넷 필요). 로그 ES 는 기본 명령
        if [ "$c" = es-search ]; then
          auto_idx="+.*,-*"
          es_cmd='    command: ["sh", "-c", "bin/elasticsearch-plugin list | grep -q analysis-nori || bin/elasticsearch-plugin install --batch analysis-nori; exec /usr/local/bin/docker-entrypoint.sh eswrapper"]'
        else
          auto_idx="+pmart-logs-*,+zipkin-*,+.*,-*"; es_cmd="" # zipkin-*: 추적 저장소(2026-11-06)
        fi
        cat <<EOF
  $c:
    <<: *common
    image: docker.elastic.co/elasticsearch/elasticsearch:8.18.0
${es_cmd:+$es_cmd
}    environment:
      discovery.type: single-node
      cluster.name: pmart-${c#es-}
      xpack.security.enabled: "false"
      action.auto_create_index: "$auto_idx"
      ES_JAVA_OPTS: -Xms768m -Xmx768m
    ports: ["9200:9200"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      # 분리 전 ES 의 esdata 와 다른 이름 — 옛 볼륨은 롤백용으로 남는다
      - es${c#es-}:/usr/share/elasticsearch/data
    mem_limit: 1536m
    healthcheck:
      test: ["CMD-SHELL", "curl -s -o /dev/null http://127.0.0.1:9200/"]
      interval: 5s
      timeout: 3s
      retries: 120

EOF
        ;;
      prometheus)
        # 지표 수집(ADR-0030). 대상은 아래에서 monitoring/prometheus.yml 로 생성.
        # 보존: 7일 또는 2GB 중 먼저 닿는 쪽(디스크 상한 — 2026-10-25 pc3 Connect 로그 142GB 사고 교훈)
        cat <<EOF
  prometheus:
    <<: *common
    image: prom/prometheus:v3.5.0
    command: ["--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.retention.time=7d", "--storage.tsdb.retention.size=2GB", "--web.enable-lifecycle"]
    ports: ["9090:9090"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./monitoring/alert-rules.yml:/etc/prometheus/alert-rules.yml:ro
      - ./monitoring/eureka-password:/etc/prometheus/secrets/eureka-password:ro
      - promdata:/prometheus
    mem_limit: 1g

EOF
        ;;
      alertmanager)
        cat <<EOF
  alertmanager:
    <<: *common
    image: prom/alertmanager:v0.28.1
    command: ["--config.file=/etc/alertmanager/alertmanager.yml"]
    ports: ["9093:9093"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./monitoring/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
      # Slack 웹훅 주소(.secrets/slack-alert-webhook) — 없으면 빈 폴더, 경보 받는 곳은 none
      - ./monitoring/alertmanager-secrets:/etc/alertmanager/secrets:ro

EOF
        ;;
      grafana)
        cat <<EOF
  grafana:
    <<: *common
    image: grafana/grafana:12.0.2
    ports: ["3000:3000"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: \${PMART_GRAFANA_ADMIN_PASSWORD}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_ANALYTICS_REPORTING_ENABLED: "false"
      GF_DEFAULT_LOCALE: ko-KR
    volumes:
      - ./monitoring/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./monitoring/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafanadata:/var/lib/grafana

EOF
        ;;
      kibana)
        # 로그 화면(ADR-0031, OpenSearch Dashboards 대체)
        cat <<EOF
  kibana:
    <<: *common
    image: docker.elastic.co/kibana/kibana:8.18.0
    ports: ["5601:5601"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      ELASTICSEARCH_HOSTS: '["http://es-logs:9200"]'
      TELEMETRY_OPTIN: "false"
    mem_limit: 1g

EOF
        ;;
      logstash-search|logstash-logs)
        # ADR-0038: logstash-search = 검색 색인(도메인 이벤트 Kafka → es-search, search-service 소유 파이프라인),
        #           logstash-logs = 로그(Filebeat 5044 → es-logs). 디스크: 영구 큐 256MB·DLQ 64MB(각각)
        k="${c#logstash-}"
        echo "  $c:"
        echo "    <<: *common"
        echo "    image: docker.elastic.co/logstash/logstash:8.18.0"
        [ "$k" = logs ] && echo '    ports: ["5044:5044"]'
        cat <<EOF
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      PMART_ES_URL: http://es-$k:9200
      LS_JAVA_OPTS: -Xms512m -Xmx512m
EOF
        if [ "$k" = search ]; then
          echo "      PMART_KAFKA_BOOTSTRAP: $KAFKA_BOOTSTRAP"
          echo "      PMART_CONTENT_BASE_URL: http://product-content-service:$(port_of product-content-service)"
          # 검색 색인 대상 색인·소비 그룹·DLT(elastic/search-index.env — 재색인 때만 바꾼다, elastic/REINDEX.md)
          grep -E '^PMART_SEARCH_[A-Z_]+=' "$LAN/elastic/search-index.env" | sed 's/^\([^=]*\)=\(.*\)$/      \1: "\2"/'
        fi
        cat <<EOF
    volumes:
      - ./elastic/logstash/logstash.yml:/usr/share/logstash/config/logstash.yml:ro
      - ./elastic/logstash/pipelines-$k.yml:/usr/share/logstash/config/pipelines.yml:ro
      - ./elastic/logstash/pipeline:/usr/share/logstash/pipeline:ro
      # 분리 전 logstash 의 lsdata 와 다른 이름(영구 큐·DLQ) — 옛 볼륨은 롤백용으로 남는다
      - ls$k:/usr/share/logstash/data
    mem_limit: 1536m
    depends_on:
      es-init-$k: { condition: service_completed_successfully }

EOF
        ;;
      log-shipper)
        # PC 마다 하나: Docker Desktop VM 안의 컨테이너 로그 파일 → Logstash(ADR-0031). 앱 로그만·초당 500건·메모리 큐
        cat <<EOF
  log-shipper:
    <<: *common
    image: docker.elastic.co/beats/filebeat:8.18.0
    user: root
    command: ["filebeat", "-e", "--strict.perms=false"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      PMART_PC: $pc
      PMART_LOGSTASH_HOST: logstash-logs
    volumes:
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
      - ./elastic/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro
      - fbdata:/usr/share/filebeat/data
    mem_limit: 256m

EOF
        ;;
      host-disk)
        # Windows C: 여유 공간(9p drvfs 마운트, 읽기 전용) → node_filesystem_avail_bytes{mountpoint="/host/windows-c"}
        # 토픽 지표 노드(kafka-1)가 있는 PC 만: watch.sh 가 쓰는 metrics/kafka_topics.prom(토픽 크기·오프셋·보관 설정, ADR-0034)도 내보낸다(textfile)
        textfile_args=""; textfile_vol=""
        if [[ " ${COMPS[$pc]} " == *" $KAFKA_METRICS_NODE "* ]]; then
          textfile_args=', "--collector.textfile", "--collector.textfile.directory=/textfile"'
          textfile_vol=$'\n      - ./metrics:/textfile:ro'
        fi
        cat <<EOF
  host-disk:
    <<: *common
    image: prom/node-exporter:v1.9.1
    command: ["--collector.disable-defaults", "--collector.filesystem", "--collector.filesystem.mount-points-exclude=^/(dev|proc|sys|etc)(\$\$|/)"$textfile_args]
    ports: ["9100:9100"]
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - /run/desktop/mnt/host/c:/host/windows-c:ro$textfile_vol
    mem_limit: 64m

EOF
        ;;
      es-init-search|es-init-logs)
        # 한 번 돌고 끝난다. 배포 때마다 다시 돌아도 같은 결과. 같은 종류의 Logstash 는 이것 뒤에 뜬다(ADR-0038)
        #   search: 상품 검색 색인·별칭 / logs: 로그 ILM·색인 틀·Kibana 데이터 뷰
        k="${c#es-init-}"
        cat <<EOF
  $c:
    image: curlimages/curl:8.14.1
    restart: "no"
    logging: { driver: json-file, options: { max-size: "1m", max-file: "1" } }
    entrypoint: ["sh", "/init/es-init.sh"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      PMART_ES_INIT: $k
      PMART_ES_URL: http://es-$k:9200
    volumes:
      - ./elastic/es-init.sh:/init/es-init.sh:ro
EOF
        [ "$k" = search ] && echo "      - ./elastic/indices:/init/indices:ro"
        cat <<EOF
    depends_on:
      es-$k: { condition: service_healthy }

EOF
        ;;
      log-janitor)
        # 로그 색인 크기 상한(색인 1GB·전체 4GB, 5분마다)
        cat <<EOF
  log-janitor:
    <<: *common
    image: curlimages/curl:8.14.1
    entrypoint: ["sh", "-c", "cp /init/log-janitor.sh /tmp/j.sh && exec sh /tmp/j.sh"] # 호스트 파일을 잠그지 않게 복사해서 실행(위 connector-registrar 와 같음)
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      PMART_ES_URL: http://es-logs:9200
    volumes:
      - ./elastic/log-janitor.sh:/init/log-janitor.sh:ro
    mem_limit: 32m

EOF
        ;;
      es-exporter-search|es-exporter-logs)
        # Elasticsearch 지표 → Prometheus(클러스터 상태·노드 JVM·디스크). 색인별 지표는 끔(시계열 수). ES 마다 하나(ADR-0038)
        k="${c#es-exporter-}"
        cat <<EOF
  $c:
    <<: *common
    image: quay.io/prometheuscommunity/elasticsearch-exporter:v1.10.0
    command: ["--es.uri=http://es-$k:9200", "--es.timeout=5s"]
    ports: ["9114:9114"]
    networks: { default: { ipv4_address: $ip_addr } }
    mem_limit: 64m
    depends_on: [es-$k]

EOF
        ;;
      kafka-exporter)
        # Kafka 클러스터 지표 → Prometheus(ADR-0037). 브로커에서 메타데이터·오프셋을 읽기만 한다.
        # kafka.version: 기본 2.0.0 이면 옛 프로토콜 판으로 요청한다(Kafka 4 는 2.1 미만 판을 지웠다) — 3.9.0 으로 올린다.
        #   v1.10.0 이 4.x 문자열을 받는지 확인하지 못했다(2026-11-05) — 전환 뒤 /metrics 의 kafka_brokers 로 확인한다
        # 컨트롤러 지표는 없다 — 과반을 잃으면(2노드 정지) 브로커 수·수집 실패로 드러난다(alert-rules.yml)
        echo "  kafka-exporter:"
        echo "    <<: *common"
        echo "    image: danielqsj/kafka-exporter:v1.10.0"
        printf '    command: ['
        for kn in $KAFKA_NODES; do printf '"--kafka.server=%s:9092", ' "$kn"; done
        echo '"--kafka.version=3.9.0", "--web.listen-address=:9308"]'
        cat <<EOF
    ports: ["9308:9308"]
    networks: { default: { ipv4_address: $ip_addr } }
    mem_limit: 128m

EOF
        ;;
      config-relay)
        # GitHub 웹훅 중계(ADR-0033): smee.io 채널에 먼저 연결해 두고(나가는 연결만) 받은 push 웹훅을 config-server /monitor 로 넘긴다.
        # 서명(X-Hub-Signature-256)은 config-server 가 검사한다. 채널 주소는 .env 의 PMART_SMEE_URL(.secrets/smee-url)
        cat <<EOF
  config-relay:
    <<: *common
    image: node:22.23.4-alpine
    user: node
    command: ["sh", "-c", "exec npx -y smee-client@5.0.0 --url \"\$\$PMART_SMEE_URL\" --target http://config-server:$(port_of config-server)/monitor"]
    environment:
      PMART_SMEE_URL: \${PMART_SMEE_URL}
    networks: { default: { ipv4_address: $ip_addr } }
    mem_limit: 128m
    depends_on: [config-server]

EOF
        ;;
      docs-relay)
        # 문서 저장소 웹훅 중계(ADR-0095): smee.io 채널(.secrets/rag-docs-smee-url)에 붙어 GitHub pmart-all-docs 의 push·delete 웹훅을
        # integration-service 로 넘긴다. 서명(X-Hub-Signature-256)은 integration-service 가 검사한다
        cat <<EOF
  docs-relay:
    <<: *common
    image: node:22.23.4-alpine
    user: node
    command: ["sh", "-c", "exec npx -y smee-client@5.0.0 --url \"\$\$PMART_DOCS_SMEE_URL\" --target http://integration-service:$(port_of integration-service)/api/v1/internal/docs-webhook"]
    environment:
      PMART_DOCS_SMEE_URL: \${PMART_DOCS_SMEE_URL}
    networks: { default: { ipv4_address: $ip_addr } }
    mem_limit: 128m
    depends_on: [integration-service]

EOF
        ;;
      zipkin)
        if [ -n "${WHERE[es-logs]:-}" ]; then
          # 추적 저장소 = 로그 ES(es-logs, 2026-11-06 사용자 요청). 색인 zipkin-span-YYYY-MM-DD(하루 하나, 복제본 0).
          # 보존은 log-janitor 가 로그와 같은 3일(로그의 traceId → 추적이 같은 기간 이어진다) + 전체 2GB 상한.
          # ES 가 잠깐 끊겨도 zipkin 은 살아 있다(그동안 받은 span 은 버린다).
          cat <<EOF
  zipkin:
    <<: *common
    image: openzipkin/zipkin:3
    ports: ["9411:9411"]
    networks: { default: { ipv4_address: $ip_addr } }
    mem_limit: 512m
    environment:
      JAVA_OPTS: "-Xms256m -Xmx256m -XX:+ExitOnOutOfMemoryError"
      STORAGE_TYPE: elasticsearch
      ES_HOSTS: http://es-logs:9200
      ES_INDEX_SHARDS: "1"
      ES_INDEX_REPLICAS: "0"

EOF
        else
          cat <<EOF
  zipkin:
    <<: *common
    image: openzipkin/zipkin:3
    ports: ["9411:9411"]
    networks: { default: { ipv4_address: $ip_addr } }
    # 메모리 저장소(es-logs 가 배치되지 않았을 때). 기본(힙 160m·span 50만)은 힙이 차서 OOM 재시작을 반복했다(2026-11-05).
    # span 상한을 줄이고 힙을 조금 늘린다. 대가: 오래된 추적은 먼저 지워진다. mem_limit 으로 다른 컨테이너를 보호
    mem_limit: 512m
    environment:
      JAVA_OPTS: "-Xms256m -Xmx256m -XX:+ExitOnOutOfMemoryError"
      MEM_MAX_SPANS: "50000"

EOF
        fi
        ;;
      api-docs)
        # Swagger UI(정적 nginx). 명세는 ./api-docs/specs(render 가 contracts/openapi 를 복사하며 servers 를 게이트웨이 주소로 바꾼다)
        cat <<EOF
  api-docs:
    <<: *common
    image: swaggerapi/swagger-ui:v5.17.14
    ports: ["8090:8080"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      CONFIG_URL: /specs/swagger-config.json
    volumes:
      - ./api-docs/specs:/usr/share/nginx/html/specs:ro
    mem_limit: 64m

EOF
        ;;
      rag-pgvector)
        # RAG 전용 PostgreSQL + pgvector(ADR-0051): 운영자용·고객용 DB·계정 둘(rag/rag-init-db.sh). 공용 postgres 는 그대로.
        # 같은 PC 의 RAG 서비스만 쓴다 — LAN 포트를 열지 않는다(확인은 docker exec). 다른 PC 로 옮기면 ports 를 연다
        cat <<EOF
  rag-pgvector:
    <<: *common
    image: pgvector/pgvector:0.8.6-pg17
    command: ["postgres", "-c", "max_connections=60"]
    environment:
      # 포트는 PGPORT 로 — 첫 초기화 때 엔트리포인트의 임시 서버도 같은 포트로 떠야 초기화 스크립트가 붙는다(-p 만 주면 5432 로 떠서 실패, 2026-11-09)
      PGPORT: "15433"
      POSTGRES_USER: pmart
      POSTGRES_PASSWORD: \${PMART_DB_PASSWORD}
      POSTGRES_DB: postgres
      PMART_RAG_OPERATOR_DB_PASSWORD: \${PMART_RAG_OPERATOR_DB_PASSWORD}
      PMART_RAG_CUSTOMER_DB_PASSWORD: \${PMART_RAG_CUSTOMER_DB_PASSWORD}
      PMART_RAG_EMBEDDING_DB_PASSWORD: \${PMART_RAG_EMBEDDING_DB_PASSWORD}
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ragpgdata:/var/lib/postgresql/data
      - ./rag/rag-init-db.sh:/docker-entrypoint-initdb.d/10-rag-init-db.sh:ro
    mem_limit: 1g
    cpus: 2
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U pmart"]
      interval: 5s
      timeout: 3s
      retries: 30

EOF
        ;;
      ollama)
        # 모델 서버(ADR-0051 결정 6): 임베딩·대화 모델을 이 PC GPU(RTX 2060 6GB)로. 인증이 없다.
        # 2026-11-16 사용자 "포트도 지금 바로 여는게" — RAG 개발 PC(PC 4)가 같은 모델을 쓰게 LAN 11434 를 연다(rag-pgvector 는 계속 닫음).
        # 동시 1건이라 PC 4 의 대량 임베딩·평가가 LAN RAG 응답을 밀어낸다 — 큰 작업 전에는 PC 1·사용자에게 알린다(docs/rag/HANDOVER.md §3).
        # 다른 업무 서비스(2026-11-15 부터 pc2 주문·결제)와 같은 PC 라 CPU 4·메모리 6GB 상한(모델이 GPU 를 넘치면 CPU 로 넘어간다). 모델 파일은 ollamadata 볼륨
        cat <<EOF
  ollama:
    <<: *common
    image: ollama/ollama:0.40.10
    ports: ["11434:11434"] # LAN — RAG 개발 PC(PC 4)용(2026-11-16)
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      OLLAMA_KEEP_ALIVE: "24h"
      # 기본 문맥 = 서비스가 요청하는 chat-num-ctx(8192). 다르면 첫 질문 때 모델을 다시 올려(약 30초) 호출 시간 제한을 넘는다
      OLLAMA_CONTEXT_LENGTH: "8192"
      OLLAMA_MAX_LOADED_MODELS: "2"
      # 동시 1건 — 2 면 문맥 메모리를 두 배로 잡아(qwen3:4b 8192 문맥 5.4GB) 6GB GPU 에서 임베딩 모델이 밀려나고 일부가 CPU 로 돈다(2026-11-09)
      OLLAMA_NUM_PARALLEL: "1"
    volumes:
      - ollamadata:/root/.ollama
    mem_limit: 6g
    cpus: 4
    deploy:
      resources:
        reservations:
          devices:
            - { driver: nvidia, count: all, capabilities: [gpu] }

EOF
        ;;
      keycloak)
        # 직원 로그인(ADR-0054): Realm pmart-staff 를 keycloak/pmart-staff-realm.json 에서 처음 한 번 가져온다(이미 있으면 건너뜀).
        # 공개 주소(ADR-0056 4단계, 2026-11-12) = 게이트웨이 HTTPS /iam — 브라우저(로그인 화면)와 토큰 iss 가 이 주소. 게이트웨이가 로그인 경로만 중계한다
        # (관리 경로 차단). 관리 화면·Admin API 는 KC_HOSTNAME_ADMIN(이 PC 직접 주소 8180)으로. 서버끼리(employee-auth-service 의 token·jwks·logout,
        # 서비스의 JWKS)는 요청한 주소 그대로(KC_HOSTNAME_BACKCHANNEL_DYNAMIC). 게이트웨이가 넘기는 X-Forwarded-* 를 믿는다(KC_PROXY_HEADERS)
        # DB 는 같은 PC postgres 의 keycloak(init-db.sql 이 계정·DB 를 만든다). LAN 개발이라 HTTP(운영은 TLS 앞단 필요)
        cat <<EOF
  keycloak:
    <<: *common
    image: quay.io/keycloak/keycloak:26.8.1
    command: ["start", "--import-realm"]
    ports: ["8180:8080"]
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres-$pc:15432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: \${PMART_KEYCLOAK_DB_PASSWORD}
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: \${PMART_KEYCLOAK_ADMIN_PASSWORD}
      KC_HOSTNAME: https://${IP[${WHERE[api-gateway]}]}:8443/iam
      KC_HOSTNAME_ADMIN: http://${IP[$pc]}:8180
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"
      KC_PROXY_HEADERS: xforwarded
      KC_HTTP_ENABLED: "true"
      KC_HEALTH_ENABLED: "true"
      JAVA_OPTS_KC_HEAP: "-XX:MaxRAMPercentage=70"
    networks: { default: { ipv4_address: $ip_addr } }
    volumes:
      - ./keycloak:/opt/keycloak/data/import:ro
      - ./keycloak-themes/pmart:/opt/keycloak/themes/pmart:ro # 직원 로그인 화면 테마(아이디만 쳐도 @pmart.com, 2026-11-15)
    mem_limit: 1536m
    cpus: 2

EOF
        ;;
      mailserver)
        # 사내 메일(ADR-0056 결정 9): docker-mailserver(Postfix·Dovecot), 도메인 pmart.com 로컬 전용 — 밖으로 보내지 않는다(mail/postfix-main.cf).
        # LAN 포트를 열지 않는다: Keycloak(SMTP 25)·Roundcube(IMAP 143·SMTP 587)·hr-service 가 같은 PC 에서 이름 mailserver 로 부른다.
        # TLS 는 LAN 사설 CA(ADR-0057) 인증서. 계정 파일은 mail-config 볼륨 — 기본 사서함은 mail-accounts-init 이 없을 때만 넣는다(직원 사서함 보존).
        # 바이러스·스팸·DKIM·SPF 검사는 끈다(내부 전용·메모리, SPF 는 실제 pmart.com 의 DNS 를 보게 된다)
        cat <<EOF
  mail-accounts-init:
    <<: *common
    image: ghcr.io/docker-mailserver/docker-mailserver@sha256:f892bc7479c4cdcc60dc3f50d2d9343a0624ac5e988a987d26c29f42a186fcd6 # 16.0.1
    restart: "no"
    entrypoint: ["sh", "/pmart/accounts-init.sh"]
    environment:
      PMART_MAIL_NOREPLY_HASH: \${PMART_MAIL_NOREPLY_HASH}
      PMART_MAIL_POSTMASTER_HASH: \${PMART_MAIL_POSTMASTER_HASH}
    volumes:
      - mail-config:/tmp/docker-mailserver
      - ./mail/accounts-init.sh:/pmart/accounts-init.sh:ro
    mem_limit: 64m

  mailserver:
    <<: *common
    image: ghcr.io/docker-mailserver/docker-mailserver@sha256:f892bc7479c4cdcc60dc3f50d2d9343a0624ac5e988a987d26c29f42a186fcd6 # 16.0.1
    hostname: mail.pmart.com
    networks: { default: { ipv4_address: $ip_addr } }
    depends_on:
      mail-accounts-init: { condition: service_completed_successfully }
    environment:
      POSTMASTER_ADDRESS: postmaster@pmart.com
      ACCOUNT_PROVISIONER: FILE
      SSL_TYPE: manual
      SSL_CERT_PATH: /etc/pmart-tls/cert.pem
      SSL_KEY_PATH: /etc/pmart-tls/key.pem
      # 같은 PC Docker 네트워크(Keycloak·hr-service·mail-provisioner)를 mynetworks 로 — Keycloak 은 HELO 로 컨테이너 ID(점 없는 이름)를 보내
      # reject_non_fqdn_helo_hostname 에 504 로 거절됐다(2026-11-12). 메일 서버는 LAN 포트가 없고, 밖으로 보내기는 postfix-main.cf 의 transport 가 막는다
      PERMIT_DOCKER: connected-networks
      SPOOF_PROTECTION: "0"
      ENABLE_CLAMAV: "0"
      ENABLE_SPAMASSASSIN: "0"
      ENABLE_AMAVIS: "0"
      ENABLE_RSPAMD: "0"
      ENABLE_OPENDKIM: "0"
      ENABLE_OPENDMARC: "0"
      ENABLE_POLICYD_SPF: "0"
      ENABLE_FAIL2BAN: "0"
      ENABLE_POSTGREY: "0"
      ENABLE_DNSBL: "0"
      ENABLE_POP3: "0"
      LOG_LEVEL: info
    volumes:
      - mail-config:/tmp/docker-mailserver
      - ./mail/postfix-main.cf:/tmp/docker-mailserver/postfix-main.cf:ro
      - mail-data:/var/mail
      - mail-state:/var/mail-state
      - mail-logs:/var/log/mail
      - ./mail/tls:/etc/pmart-tls:ro
    stop_grace_period: 1m
    mem_limit: 512m

EOF
        ;;
      roundcube)
        # 사내 메일 웹메일(ADR-0056): LAN https://<이 PC IP>:8190 (ADR-0057 사설 CA). 메일 서버에는 같은 PC 에서 STARTTLS(IMAP 143·SMTP 587)로, 루트 CA 로 확인.
        # 설정 덧붙임 mail/roundcube-pmart.php, HTTPS 는 mail/roundcube-ssl.conf(이미지 Apache 에 443 추가). DB 는 SQLite(roundcube-data 볼륨)
        cat <<EOF
  roundcube:
    <<: *common
    image: roundcube/roundcubemail@sha256:d23169b8bbb99b5e75adcd502b627d6f29e60833a7053a12e4ff133468c2e5b4 # 1.7.6-apache
    ports: ["8190:443"]
    networks: { default: { ipv4_address: $ip_addr } }
    environment:
      ROUNDCUBEMAIL_DEFAULT_HOST: tls://mailserver
      ROUNDCUBEMAIL_DEFAULT_PORT: "143"
      ROUNDCUBEMAIL_SMTP_SERVER: tls://mailserver
      ROUNDCUBEMAIL_SMTP_PORT: "587"
      ROUNDCUBEMAIL_DB_TYPE: sqlite
      ROUNDCUBEMAIL_SKIN: elastic
    volumes:
      - roundcube-data:/var/roundcube/db
      - ./mail/roundcube-pmart.php:/var/roundcube/config/pmart.php:ro
      - ./mail/roundcube-ssl.conf:/etc/apache2/conf-enabled/pmart-ssl.conf:ro
      - ./mail/tls:/etc/pmart-tls:ro
    mem_limit: 256m

EOF
        ;;
      *)
        port="$(port_of "$c")"; [ -n "$port" ] || { echo "포트 없음: $c" >&2; exit 1; }
        echo "  $c:"
        echo "    <<: *common"
        echo "    image: $REGISTRY/pmart-$c@$(digest_of "$c")"
        if [ "$c" = api-gateway ]; then
          # HTTPS 입구(ADR-0057) 8443 을 8080 옆에 — 클라이언트가 옮길 때까지 둘 다(T3). 인증서는 아래 ./tls(이 PC 서버 인증서)
          echo "    ports: [\"$port:$port\", \"8443:8443\"]"
        elif [ "$c" = mail-provisioner ]; then
          # 사서함 사이드카(ADR-0056 결정 10): LAN 포트 없음 — 같은 PC 의 hr-service 만 이름으로 부른다. 메일 서버 설정 볼륨의 계정 파일을 쓰므로
          # 그 PC 에 mailserver 가 있어야 하고, 파일 주인이 root 라 root 로 돈다(docker.sock 은 붙이지 않는다)
          [[ "$comps" == *" mailserver "* ]] || { echo "mail-provisioner 는 mailserver 와 같은 PC 에 둔다" >&2; exit 1; }
          echo "    user: \"0:0\""
        else
          echo "    ports: [\"$port:$port\"]"
        fi
        echo "    networks: { default: { ipv4_address: $ip_addr } }"
        echo "    mem_limit: 768m"
        if is_rag "$c"; then
          [[ "$comps" == *" rag-pgvector "* && "$comps" == *" ollama "* ]] \
            || { echo "$c 는 같은 PC 에 rag-pgvector·ollama 가 필요하다(ADR-0051)" >&2; exit 1; }
          echo "    depends_on:"
          echo "      rag-pgvector: { condition: service_healthy }"
          echo "      ollama: { condition: service_started }"
        elif needs_db "$c" && [[ "$comps" == *" postgres "* ]]; then
          echo "    depends_on:"
          echo "      postgres: { condition: service_healthy }"
        fi
        # RAG 앱은 환경을 앵커로 — 색인 작업 서비스(<앱 이름 앞부분>-ingest)가 같은 환경을 쓴다
        if is_rag "$c"; then echo "    environment: &env-$c"; else echo "    environment:"; fi
        if [ "$c" = config-server ]; then
          # 서버 자신의 비밀은 configtree 디렉터리(파일 이름 = 속성 이름). 저장소 주소는 application.yml 기본값(GitHub)
          echo "      PMART_ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans"
          # /monitor 가 받은 웹훅을 Bus(Kafka springCloudBus)로 알린다(ADR-0033). 서버는 설정 저장소를 읽지 않아 환경변수로 켠다
          echo "      SPRING_CLOUD_BUS_ENABLED: \"true\""
          echo "      SPRING_KAFKA_BOOTSTRAP_SERVERS: $KAFKA_BOOTSTRAP"
          # GitHub fetch 는 15초에 한 번만(기본 0 = 요청마다). 앱 35개가 한꺼번에 뜨면 요청마다 fetch(약 10초, 직렬)가 밀려
          # 30초 시간 초과 → 재시작 → 다시 요청이 반복됐다(2026-11-04 배포). 대가: push 뒤 최대 15초는 이전 설정을 줄 수 있다
          echo "      SPRING_CLOUD_CONFIG_SERVER_GIT_REFRESH_RATE: \"15\""
          echo "    volumes:"
          echo "      - ./config-server-secrets:/run/secrets/config-server:ro"
          echo
          continue
        fi
        # 설정은 config-server 에서(ADR-0027·0029). 받지 못하면 기동하지 않는다(fail-fast → restart 로 다시 시도)
        echo "      SPRING_CLOUD_CONFIG_ENABLED: \"true\""
        echo "      SPRING_CLOUD_CONFIG_URI: http://config-server:$(port_of config-server)"
        echo "      SPRING_CLOUD_CONFIG_FAIL_FAST: \"true\""
        echo "      SPRING_CLOUD_CONFIG_USERNAME: pmart"
        echo "      SPRING_CLOUD_CONFIG_PASSWORD: \${PMART_CONFIG_PASSWORD}"
        # 서비스 설정은 설정 저장소의 <앱>.yml · <앱>-lan.yml (lan 프로필). 없으면 뜰 수 없다
        migrated "$c" || { echo "설정 저장소에 $c-lan.yml 이 없음($CFG_CLONE)" >&2; exit 1; }
        echo "      SPRING_PROFILES_ACTIVE: lan"
        # Eureka(ADR-0032): 이 PC 의 LAN IP 로 등록한다(컨테이너 IP 는 다른 PC 에서 닿지 않는다). 설정은 application-lan.yml
        echo "      PMART_HOST_IP: ${IP[$pc]}"
        echo "      PMART_EUREKA_PASSWORD: \${PMART_EUREKA_PASSWORD}"
        # Bus 소비 그룹 이름 고정(bus-<앱>-<PC>, application-lan.yml) — 재시작마다 anonymous.* 그룹이 늘지 않게(2026-11-04)
        echo "      PMART_BUS_INSTANCE: $pc"
        vols=()
        if vault_on "$c"; then
          # Vault(ADR-0100 결정 4): import 순서상 Vault 가 뒤라 같은 속성은 Vault 가 이긴다. yml 의 import(optional:configserver:)를 이 값이 대체한다
          rid="$(vault_api GET "auth/approle/role/svc-$c/role-id" | json_field role_id)"
          sid="$(vault_api POST "auth/approle/role/svc-$c/secret-id" | json_field secret_id)"
          [ -n "$rid" ] && [ -n "$sid" ] \
            || { echo "Vault AppRole svc-$c 에서 role-id·secret-id 를 못 받음 — pc2 deploy/vault/service-roles.sh(placement 반영)" >&2; exit 1; }
          skey="PMART_VAULT_SECRET_ID_$(printf '%s' "$c" | tr 'a-z-' 'A-Z_')"
          VAULT_ENV_LINES+="$skey=$sid"$'\n'
          echo "      SPRING_CONFIG_IMPORT: \"optional:configserver:,vault://\""
          echo "      SPRING_CLOUD_VAULT_ENABLED: \"true\""
          echo "      SPRING_CLOUD_VAULT_URI: $VAULT_URI"
          echo "      SPRING_CLOUD_VAULT_FAILFAST: \"true\""
          echo "      SPRING_CLOUD_VAULT_AUTHENTICATION: APPROLE"
          echo "      SPRING_CLOUD_VAULT_APPROLE_ROLEID: $rid"
          echo "      SPRING_CLOUD_VAULT_APPROLE_SECRETID: \${$skey}"
          echo "      SPRING_CLOUD_VAULT_KV_BACKEND: config"
          echo "      SPRING_CLOUD_VAULT_KV_PROFILESEPARATOR: \",\""
          echo "      SPRING_CLOUD_VAULT_KV_DEFAULTCONTEXT: \"\""
          echo "      SPRING_CLOUD_VAULT_SSL_TRUSTSTORE: file:/etc/pmart-vault/ca.pem"
          echo "      SPRING_CLOUD_VAULT_SSL_TRUSTSTORETYPE: PEM"
          cp "$VAULT_CA" "$dir/vault-ca.pem"
          vols+=("./vault-ca.pem:/etc/pmart-vault/ca.pem:ro # Vault TLS 사설 CA(ADR-0057·0100)")
        fi
        if [ "$c" = api-gateway ]; then
          vols+=("./tls:/etc/pmart-tls:ro # 이 PC 서버 인증서·키(pmart-config api-gateway-lan.yml 의 SSL 번들 lan-tls)")
        elif [ "$c" = mail-provisioner ]; then
          vols+=("mail-config:/tmp/docker-mailserver # 계정 파일 postfix-accounts.cf(메일 서버가 변경을 감지해 사서함을 만든다)")
        fi
        if [ ${#vols[@]} -gt 0 ]; then
          echo "    volumes:"
          for v in "${vols[@]}"; do echo "      - $v"; done
        fi
        echo
        # 운영자 RAG 색인 작업(ADR-0036 결정 6 — 기동 때 색인하지 않는다): 같은 이미지·설정, profiles 로 up 에서 제외,
        # 고정 IP 없음(compose run 이 앱의 고정 IP 를 그대로 쓰면 실행 중인 앱과 겹친다). lan.sh rag-ingest 가 run --rm 으로 한 번 돌린다
        # 고객용(customer-assistant-ingest, ADR-0051)도 같은 방식 — 문서는 rag/knowledge-customer(고객 안내 문서만)
        if [ "$c" = ai-assistant-service ] || [ "$c" = customer-assistant-service ]; then
          if [ "$c" = ai-assistant-service ]; then job=ai-assistant-ingest; kn=knowledge-operator; else job=customer-assistant-ingest; kn=knowledge-customer; fi
          cat <<EOF
  $job:
    <<: *common
    image: $REGISTRY/pmart-$c@$(digest_of "$c")
    profiles: ["jobs"]
    restart: "no"
    mem_limit: 768m
    environment: *env-$c
    volumes:
      - ./rag/$kn:/repo:ro$(vault_on "$c" && printf '\n      - ./vault-ca.pem:/etc/pmart-vault/ca.pem:ro # 같은 환경(앵커)이라 Vault 를 켰으면 CA 도')
    command:
      - --spring.main.web-application-type=none
      - --eureka.client.enabled=false
      - --spring.cloud.bus.enabled=false
      - --pmart.assistant.ingest.run=true
      - --pmart.assistant.ingest.repo=/repo

EOF
        fi
        # 배치 대조: DB 를 쓰는 앱의 lan DB 주소가 이 PC 의 postgres 인가(placement.conf 를 바꾸면 저장소도 바꿔야 한다)
        if is_rag "$c"; then
          want="jdbc:postgresql://rag-pgvector:15433/$(db_of "$c")"
          grep -qF "$want" "$CFG_CLONE/$c-lan.yml" \
            || { echo "배치와 설정 저장소가 다름: $c 의 DB 주소가 $want 가 아니다(ADR-0051)" >&2; exit 1; }
        elif needs_db "$c"; then
          [ -n "${WHERE[postgres-$pc]:-}" ] || { echo "$c 는 DB 가 필요한데 $pc 에 postgres 가 없음" >&2; exit 1; }
          want="jdbc:postgresql://postgres-$pc:15432/$(db_of "$c")"
          grep -qF "$want" "$CFG_CLONE/$c-lan.yml" \
            || { echo "배치와 설정 저장소가 다름: $c 는 $pc 인데 $c-lan.yml 의 DB 주소가 $want 가 아니다" >&2; exit 1; }
        fi
        ;;
      esac
    done

    echo "networks:"
    echo "  default:"
    echo "    ipam:"
    echo "      config:"
    echo "        - subnet: $SUBNET_PREFIX.0/24"
    echo "          ip_range: $SUBNET_PREFIX.192/26 # 자동 배정(connector-registrar 등)은 여기서만"
    echo

    vols=""
    [[ "$comps" == *" postgres "* ]] && vols+="  pgdata:"$'\n'
    [[ "$comps" == *" kafka-"[123]" "* ]] && vols+="  kafkacluster:"$'\n'
    [[ "$comps" == *" s3 "* ]] && vols+="  s3data:"$'\n'
    for k in search logs; do
      [[ "$comps" == *" es-$k "* ]] && vols+="  es$k:"$'\n'
      [[ "$comps" == *" logstash-$k "* ]] && vols+="  ls$k:"$'\n'
    done
    [[ " ${COMPS[$pc]} " == *" prometheus "* ]] && vols+="  promdata:"$'\n'
    [[ " ${COMPS[$pc]} " == *" grafana "* ]] && vols+="  grafanadata:"$'\n'
    [[ " ${COMPS[$pc]} " == *" log-shipper "* ]] && vols+="  fbdata:"$'\n'
    [[ " ${COMPS[$pc]} " == *" rag-pgvector "* ]] && vols+="  ragpgdata:"$'\n'
    [[ " ${COMPS[$pc]} " == *" ollama "* ]] && vols+="  ollamadata:"$'\n'
    [[ " ${COMPS[$pc]} " == *" mailserver "* ]] && vols+="  mail-config:"$'\n'"  mail-data:"$'\n'"  mail-state:"$'\n'"  mail-logs:"$'\n'
    [[ " ${COMPS[$pc]} " == *" roundcube "* ]] && vols+="  roundcube-data:"$'\n'
    [ -n "$vols" ] && printf 'volumes:\n%s' "$vols"
  } > "$dir/compose.yml"

  # Elastic 구성 파일(ADR-0031): 이 PC 에 있는 것만. 검색 색인 파이프라인·색인 정의는 search-service 소유(정본) — 복사한다
  allc=" ${COMPS[$pc]} "
  if [[ "$allc" =~ \ (logstash-search|logstash-logs|log-shipper|es-init-search|es-init-logs|log-janitor)\  ]]; then
    mkdir -p "$dir/elastic"
    [[ "$allc" == *" log-shipper "* ]] && cp "$ELASTIC/filebeat.yml" "$dir/elastic/"
    # Logstash(ADR-0038): 종류마다 파이프라인 목록(pipelines-<종류>.yml)과 그 파이프라인 파일만
    if [[ "$allc" == *" logstash-search "* ]]; then
      mkdir -p "$dir/elastic/logstash/pipeline"
      cp "$ELASTIC/logstash/logstash.yml" "$ELASTIC/logstash/pipelines-search.yml" "$dir/elastic/logstash/"
      cp "$SEARCH_SVC/src/main/logstash/search-index.conf" "$dir/elastic/logstash/pipeline/"
    fi
    if [[ "$allc" == *" logstash-logs "* ]]; then
      mkdir -p "$dir/elastic/logstash/pipeline"
      cp "$ELASTIC/logstash/logstash.yml" "$ELASTIC/logstash/pipelines-logs.yml" "$dir/elastic/logstash/"
      cp "$ELASTIC/logstash/logs.conf" "$dir/elastic/logstash/pipeline/"
    fi
    if [[ "$allc" =~ \ es-init-(search|logs)\  ]]; then
      tr -d '\r' < "$ELASTIC/es-init.sh" > "$dir/elastic/es-init.sh"
    fi
    if [[ "$allc" == *" es-init-search "* ]]; then
      mkdir -p "$dir/elastic/indices" && cp "$SEARCH_SVC"/src/main/resources/elasticsearch/*.json "$dir/elastic/indices/"
    fi
    [[ "$allc" == *" log-janitor "* ]] && tr -d '\r' < "$ELASTIC/log-janitor.sh" > "$dir/elastic/log-janitor.sh"
  fi

  # Kafka 토픽 준비(ADR-0034·0037): lan.sh deploy 가 세 노드를 (재생성 없이) 먼저 올린 뒤 한 노드 컨테이너 안에서 실행한다.
  # metrics/ = watch.sh 가 쓰는 토픽 크기 지표(textfile) — 지표 노드(kafka-1) PC 의 host-disk 가 읽는다
  if [[ " ${COMPS[$pc]} " == *" kafka-"[123]" "* ]]; then
    mkdir -p "$dir/kafka"
    tr -d '\r' < "$LAN/kafka/topics-init.sh" > "$dir/kafka/topics-init.sh"
  fi
  if [[ " ${COMPS[$pc]} " == *" $KAFKA_METRICS_NODE "* ]]; then
    mkdir -p "$dir/metrics"
    echo "watch.sh(PC 1)가 kafka_topics.prom 을 쓴다. host-disk(node-exporter textfile)가 읽는다 — ADR-0034" > "$dir/metrics/README.txt"
  fi

  # API 문서(api-docs): 계약 명세 복사 + servers 를 게이트웨이 절대 주소로(상대 주소면 Swagger UI 의 8090 으로 보낸다)
  if [[ " ${COMPS[$pc]} " == *" api-docs "* ]]; then
    mkdir -p "$dir/api-docs/specs"
    gw="http://${IP[${WHERE[api-gateway]}]}:8080"
    urls=""
    for f in contracts/openapi/*.yaml; do
      b="$(basename "$f")"
      sed -E "s#^(  - url: )(/[^ ]*)#\1$gw\2#" "$f" > "$dir/api-docs/specs/$b"
      case "$b" in _*|redocly.yaml) continue ;; esac
      urls+="${urls:+,}{\"url\":\"/specs/$b\",\"name\":\"${b%.yaml}\"}"
    done
    printf '{"urls":[%s],"urls.primaryName":"api-gateway","deepLinking":true,"persistAuthorization":true,"displayRequestDuration":true}
' "$urls" > "$dir/api-docs/specs/swagger-config.json"
  fi

  # 관측 구성 파일(ADR-0030): 이 PC 에 있는 것만
  allc=" ${COMPS[$pc]} "
  if [[ "$allc" =~ \ (prometheus|alertmanager|grafana)\  ]]; then
    mkdir -p "$dir/monitoring"
    [[ "$allc" == *" prometheus "* ]] && cp "$MON/alert-rules.yml" "$dir/monitoring/"
    if [[ "$allc" == *" alertmanager "* ]]; then
      cp "$MON/alertmanager.yml" "$dir/monitoring/"
      mkdir -p "$dir/monitoring/alertmanager-secrets"
      # 경보 받는 곳(Slack): 웹훅 주소 파일이 있을 때만. 없으면 none(어디에도 보내지 않음)
      if [ -s "$SECRETS/slack-alert-webhook" ]; then
        secret slack-alert-webhook > "$dir/monitoring/alertmanager-secrets/slack-alert-webhook"
      else
        sed -i 's/^  receiver: slack$/  receiver: none/' "$dir/monitoring/alertmanager.yml"
      fi
    fi
    [[ "$allc" == *" grafana "* ]] && cp -r "$MON/grafana" "$dir/monitoring/"
    if [[ "$allc" == *" prometheus "* ]]; then
      {
        echo "# 생성 파일(deploy/lan/render.sh). ADR-0030. 앱 수집 대상은 Eureka 등록부에서 찾는다(eureka_sd, 2026-11-05)."
        echo "# 인프라(host-disk·es-exporter)와 Eureka 에 등록하지 않는 eureka-server 만 고정 목록"
        echo "global: { scrape_interval: 15s, evaluation_interval: 15s }"
        echo "rule_files: [/etc/prometheus/alert-rules.yml]"
        if [ -n "${WHERE[alertmanager]:-}" ]; then
          echo "alerting: { alertmanagers: [ { static_configs: [ { targets: [\"${IP[${WHERE[alertmanager]}]}:9093\"] } ] } ] }"
        fi
        echo "scrape_configs:"
        echo "  - job_name: prometheus"
        echo "    static_configs: [ { targets: [\"localhost:9090\"] } ]"
        echo "  - job_name: host-disk"
        echo "    static_configs:"
        for p in "${PCS[@]}"; do echo "      - { targets: [\"${IP[$p]}:9100\"], labels: { pc: $p } }"; done
        # ES 마다 exporter 하나(ADR-0038) — es 레이블(search·logs)로 구분한다(경보·대시보드). exporter 가 붙이는 cluster 레이블(ES cluster.name)과 겹치지 않게 es
        if [ -n "${WHERE[es-exporter-search]:-}${WHERE[es-exporter-logs]:-}" ]; then
          echo "  - job_name: elasticsearch"
          echo "    static_configs:"
          for k in search logs; do
            [ -n "${WHERE[es-exporter-$k]:-}" ] && echo "      - { targets: [\"${IP[${WHERE[es-exporter-$k]}]}:9114\"], labels: { pc: ${WHERE[es-exporter-$k]}, es: $k } }"
          done
        fi
        # Kafka 클러스터 지표(ADR-0037): kafka-exporter(브로커 수·파티션 리더·ISR·소비 그룹 지연)
        if [ -n "${WHERE[kafka-exporter]:-}" ]; then
          echo "  - job_name: kafka"
          echo "    static_configs: [ { targets: [\"${IP[${WHERE[kafka-exporter]}]}:9308\"], labels: { pc: ${WHERE[kafka-exporter]} } } ]"
        fi
        # eureka-server 는 자기 자신을 등록하지 않는다(register-with-eureka: false) — 고정
        if [ -n "${WHERE[eureka-server]:-}" ]; then
          echo "  - job_name: eureka-server"
          echo "    metrics_path: /actuator/prometheus"
          echo "    static_configs: [ { targets: [\"${IP[${WHERE[eureka-server]}]}:$(port_of eureka-server)\"], labels: { pc: ${WHERE[eureka-server]} } } ]"
          # 그 밖의 앱: Eureka 등록부(ADR-0032)에서 찾는다. 앱은 PC 의 LAN IP + 공개 포트로 등록하고(prefer-ip-address),
          # 지표 포트는 메타데이터 management.port. job = 앱 이름 소문자(전처럼 search-service 등 — 대시보드·경보 그대로).
          # config-server 는 Eureka 에 등록하지 않는다(eureka.client.enabled: false) — 지표도 열지 않았으므로 수집 대상 아님.
          # Eureka 를 못 읽으면 Prometheus 는 마지막으로 찾은 대상을 유지한다. 등록부에서 사라진 앱은 AppMissingFromDiscovery 가 잡는다.
          echo "  - job_name: eureka-apps"
          echo "    metrics_path: /actuator/prometheus"
          echo "    eureka_sd_configs:"
          echo "      - server: http://${IP[${WHERE[eureka-server]}]}:$(port_of eureka-server)/eureka"
          echo "        refresh_interval: 30s"
          echo "        basic_auth: { username: pmart, password_file: /etc/prometheus/secrets/eureka-password }"
          echo "    relabel_configs:"
          echo "      - { source_labels: [__meta_eureka_app_instance_ip_addr, __meta_eureka_app_instance_metadata_management_port], regex: '(.+);(.+)', target_label: __address__, replacement: '\$1:\$2' }"
          # instance 는 전처럼 IP:포트(eureka_sd 기본값은 인스턴스 ID — 바꾸면 기존 시계열과 끊긴다)
          echo "      - { source_labels: [__address__], target_label: instance }"
          echo "      - { source_labels: [__meta_eureka_app_name], target_label: job, action: lowercase }"
          # 상태(UP·DOWN 등)는 태그로 붙이지 않는다 — 값이 바뀌면 다른 시계열이 되어 그래프·경보가 끊긴다
          echo "      - { target_label: discovery, replacement: eureka }"
          # IP → PC 이름(PC 목록 표, 서비스 배치와 무관). 정규식의 점은 글자 그대로
          for p in "${PCS[@]}"; do
            ipre="$(printf '%s' "${IP[$p]}" | sed 's/\./\\./g')"
            echo "      - { source_labels: [__meta_eureka_app_instance_ip_addr], regex: '$ipre', target_label: pc, replacement: $p }"
          done
        fi
      } > "$dir/monitoring/prometheus.yml"
      # Eureka 조회 비밀번호(basic_auth password_file). build/ 에만 쓴다(git 제외, 설정 파일에 넣지 않는다)
      secret eureka-password > "$dir/monitoring/eureka-password"
    fi
  fi

  # init-db.sql: 이 PC 서비스 중 DB 가 필요한 것만
  {
    echo "-- 생성 파일(deploy/lan/render.sh). 볼륨이 처음 만들어질 때 실행되고, lan.sh deploy 가 매번 다시 실행한다(없는 DB 만 만든다)."
    for c in ${COMPS[$pc]}; do
      [[ "$INFRA" == *" $c "* ]] && continue
      is_rag "$c" && continue # RAG DB 는 rag-pgvector(rag/rag-init-db.sh)
      needs_db "$c" \
        && echo "SELECT 'CREATE DATABASE $(db_of "$c")' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$(db_of "$c")')\\gexec"
    done
    # Keycloak(ADR-0054): 전용 계정·DB(없는 것만). 비밀번호는 build/ 의 이 파일에만(git 제외)
    if [[ "$comps" == *" keycloak "* ]]; then
      echo "SELECT 'CREATE ROLE keycloak LOGIN PASSWORD ''$(secret keycloak-db-password)''' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak')\\gexec"
      echo "SELECT 'CREATE DATABASE keycloak OWNER keycloak' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak')\\gexec"
    fi
  } > "$dir/init-db.sql"

  # Keycloak Realm 가져오기 파일(ADR-0054)
  if [[ "$comps" == *" keycloak "* ]]; then
    # 클라이언트 비밀값 자리표시자(employee-auth-service·hr-provisioner)를 .secrets 값으로 — build/ 에만(git 제외).
    # 중계 클라이언트 pmart-auth-relay 는 2026-11-13 지웠다(ADR-0056 7단계).
    # 가져오기는 Realm 이 없을 때 한 번뿐이다. 이미 있는 Realm 의 값은 Keycloak 관리 화면·API 로 맞춘다
    mkdir -p "$dir/keycloak"
    tr -d '\r' < "$LAN/keycloak/pmart-staff-realm.json" \
      | sed -e "s|__PMART_KEYCLOAK_EMPLOYEE_AUTH_SECRET__|$(secret keycloak-employee-auth-client-secret)|" -e "s|__PMART_KEYCLOAK_HR_PROVISIONER_SECRET__|$(secret keycloak-hr-provisioner-client-secret)|" > "$dir/keycloak/pmart-staff-realm.json"
    rm -rf "$dir/keycloak-themes"; mkdir -p "$dir/keycloak-themes" && cp -r "$LAN/keycloak/themes/." "$dir/keycloak-themes/"
  fi

  # 사내 메일(ADR-0056 결정 9)·웹메일: 설정 파일과 이 PC 의 LAN 사설 CA 인증서(ADR-0057, lan.sh tls-issue <pc> 가 만든 것). 키는 build/ 에만(git 제외)
  if [[ "$comps" == *" mailserver "* || "$comps" == *" roundcube "* ]]; then
    [ -f "$SECRETS/tls/$pc.crt" ] || { echo "$pc: LAN HTTPS 인증서 없음 — deploy/lan/lan.sh tls-issue $pc 먼저" >&2; exit 1; }
    mkdir -p "$dir/mail/tls"
    for f in postfix-main.cf accounts-init.sh roundcube-pmart.php roundcube-ssl.conf; do tr -d '\r' < "$LAN/mail/$f" > "$dir/mail/$f"; done
    cp "$SECRETS/tls/$pc.crt" "$dir/mail/tls/cert.pem"; cp "$SECRETS/tls/$pc.key" "$dir/mail/tls/key.pem"
    cp "$LAN/tls/pmart-lan-root-ca.crt" "$dir/mail/tls/pmart-lan-root-ca.crt"
  fi

  # 게이트웨이·백오피스 HTTPS(ADR-0057): 이 PC 서버 인증서·키 → build/<pc>/tls(키는 build/ 에만, git 제외)
  if [[ "$comps" == *" api-gateway "* || "$comps" == *" backoffice-web "* || "$comps" == *" hr-backoffice-web "* || "$comps" == *" mfc-backoffice-web "* || "$comps" == *" rider-backoffice-web "* || "$comps" == *" cdc-backoffice-web "* ]]; then
    [ -f "$SECRETS/tls/$pc.crt" ] || { echo "$pc: LAN HTTPS 인증서 없음 — deploy/lan/lan.sh tls-issue $pc 먼저" >&2; exit 1; }
    mkdir -p "$dir/tls"
    cp "$SECRETS/tls/$pc.crt" "$dir/tls/cert.pem"; cp "$SECRETS/tls/$pc.key" "$dir/tls/key.pem"
  fi

  if [[ "$comps" == *" connect "* ]]; then
    cp deploy/local/connector-registrar.sh "$dir/"
    mkdir -p "$dir/connectors" && cp deploy/local/connectors/*.json "$dir/connectors/"
  fi

  # RAG pgvector 초기화 스크립트(ADR-0051)
  if [[ "$comps" == *" rag-pgvector "* ]]; then
    mkdir -p "$dir/rag" && tr -d '\r' < "$LAN/rag/rag-init-db.sh" > "$dir/rag/rag-init-db.sh"
  fi
  # 운영자 RAG 색인 대상 문서(manifest 명시 목록만): lan.sh rag-ingest 가 컨테이너에 /repo 로 붙여 색인한다. 출처 커밋은 REVISION
  # 고객용 RAG(customer-assistant-service)는 rag/knowledge-customer — 고객 안내 문서만(lan.sh rag-ingest-customer)
  for rag in ai-assistant-service:knowledge-operator customer-assistant-service:knowledge-customer; do
    app="${rag%%:*}"; kn="${rag#*:}"
    [[ "$comps" == *" $app "* ]] || continue
    kdir="$dir/rag/$kn"; rm -rf "$kdir"; mkdir -p "$kdir"
    mf="services/$app/src/main/resources/knowledge/manifest.yml"
    sed -n 's/^  - { path: \([^,]*\),.*/\1/p' "$mf" \
      | while read -r f; do mkdir -p "$kdir/$(dirname "$f")"; cp "$f" "$kdir/$f"; done
    # REVISION: 1줄 = 커밋, 2줄 = 복사한 문서·manifest 에 커밋하지 않은 수정(새 파일 포함)이 있으면 dirty 없으면 clean.
    # 색인 컨테이너에는 .git 이 없어 색인기가 이 파일로 출처 커밋을 기록한다(GitSourceRevision, 2026-11-15)
    { git rev-parse HEAD
      if [ -n "$(sed -n 's/^  - { path: \([^,]*\),.*/\1/p' "$mf" | xargs git status --porcelain -- "$mf")" ]; then echo dirty; else echo clean; fi
    } > "$kdir/REVISION"
  done

  # 임시 백오피스 정적 파일: lan.sh publish-backoffice 가 둔 빌드(backoffice-dist, git 제외). 없으면 안내 페이지
  if [[ "$comps" == *" backoffice-web "* ]]; then
    cp "$LAN/backoffice/nginx.conf" "$dir/backoffice-nginx.conf"
    mkdir -p "$dir/backoffice"
    if [ -f "$LAN/backoffice-dist/index.html" ]; then
      cp -r "$LAN/backoffice-dist/." "$dir/backoffice/"
    else
      cp "$LAN/backoffice/placeholder.html" "$dir/backoffice/index.html"
    fi
  fi
  # 인사팀 백오피스 정적 파일: lan.sh publish-hr-backoffice 가 둔 빌드(hr-backoffice-dist, git 제외). 없으면 안내 페이지
  if [[ "$comps" == *" hr-backoffice-web "* ]]; then
    cp "$LAN/backoffice/nginx.conf" "$dir/backoffice-nginx.conf"
    rm -rf "$dir/hr-backoffice"; mkdir -p "$dir/hr-backoffice"
    if [ -f "$LAN/hr-backoffice-dist/index.html" ]; then
      cp -r "$LAN/hr-backoffice-dist/." "$dir/hr-backoffice/"
    else
      cp "$LAN/backoffice/hr-placeholder.html" "$dir/hr-backoffice/index.html"
    fi
  fi
  # MFC 백오피스 정적 파일(2026-11-15): lan.sh publish-mfc-backoffice 가 둔 빌드(mfc-backoffice-dist, git 제외). 없으면 안내 페이지
  if [[ "$comps" == *" mfc-backoffice-web "* ]]; then
    cp "$LAN/backoffice/nginx.conf" "$dir/backoffice-nginx.conf"
    rm -rf "$dir/mfc-backoffice"; mkdir -p "$dir/mfc-backoffice"
    if [ -f "$LAN/mfc-backoffice-dist/index.html" ]; then
      cp -r "$LAN/mfc-backoffice-dist/." "$dir/mfc-backoffice/"
    else
      cp "$LAN/backoffice/mfc-placeholder.html" "$dir/mfc-backoffice/index.html"
    fi
  fi
  # 라이더 백오피스 정적 파일(2026-11-17): lan.sh publish-rider-backoffice 가 둔 빌드(rider-backoffice-dist, git 제외). 없으면 안내 페이지
  if [[ "$comps" == *" rider-backoffice-web "* ]]; then
    cp "$LAN/backoffice/nginx.conf" "$dir/backoffice-nginx.conf"
    rm -rf "$dir/rider-backoffice"; mkdir -p "$dir/rider-backoffice"
    if [ -f "$LAN/rider-backoffice-dist/index.html" ]; then
      cp -r "$LAN/rider-backoffice-dist/." "$dir/rider-backoffice/"
    else
      cp "$LAN/backoffice/rider-placeholder.html" "$dir/rider-backoffice/index.html"
    fi
  fi
  # DC 백오피스 정적 파일(2026-11-25): lan.sh publish-cdc-backoffice 가 둔 빌드(cdc-backoffice-dist, git 제외). 없으면 안내 페이지
  if [[ "$comps" == *" cdc-backoffice-web "* ]]; then
    cp "$LAN/backoffice/nginx.conf" "$dir/backoffice-nginx.conf"
    rm -rf "$dir/cdc-backoffice"; mkdir -p "$dir/cdc-backoffice"
    if [ -f "$LAN/cdc-backoffice-dist/index.html" ]; then
      cp -r "$LAN/cdc-backoffice-dist/." "$dir/cdc-backoffice/"
    else
      cp "$LAN/backoffice/cdc-placeholder.html" "$dir/cdc-backoffice/index.html"
    fi
  fi

  # .env: Spring 이 아닌 컨테이너(postgres·connector-registrar·s3)의 비밀 + config-server·eureka-server 접속 비밀번호만.
  # Spring 앱의 설정·비밀은 config-server 에서 받는다(ADR-0029, 설정 저장소).
  {
    echo "PMART_DB_PASSWORD=$DB_PASSWORD"
    echo "PMART_CONFIG_PASSWORD=$(secret config-password)"
    echo "PMART_EUREKA_PASSWORD=$(secret eureka-password)"
    [[ "$comps" == *" config-relay "* ]] && echo "PMART_SMEE_URL=$(secret smee-url)"
    [[ "$comps" == *" docs-relay "* ]] && echo "PMART_DOCS_SMEE_URL=$(secret rag-docs-smee-url)"
    [[ "$comps" == *" grafana "* ]] && echo "PMART_GRAFANA_ADMIN_PASSWORD=$(secret grafana-admin-password)"
    if [[ "$comps" == *" rag-pgvector "* ]]; then
      echo "PMART_RAG_OPERATOR_DB_PASSWORD=$(secret rag-db-operator-password)"
      echo "PMART_RAG_CUSTOMER_DB_PASSWORD=$(secret rag-db-customer-password)"
      echo "PMART_RAG_EMBEDDING_DB_PASSWORD=$(secret rag-db-embedding-password)"
    fi
    if [[ "$comps" == *" keycloak "* ]]; then
      echo "PMART_KEYCLOAK_ADMIN_PASSWORD=$(secret keycloak-admin-password)"
      echo "PMART_KEYCLOAK_DB_PASSWORD=$(secret keycloak-db-password)"
    fi
    # 사내 메일 기본 사서함 해시(SHA512-CRYPT, $ 가 있어 작은따옴표 — compose 가 변수로 읽지 않게). 원문 비밀번호는 넘기지 않는다
    if [[ "$comps" == *" mailserver "* ]]; then
      echo "PMART_MAIL_NOREPLY_HASH='$(secret mail-noreply-password | MSYS2_ARG_CONV_EXCL='*' openssl passwd -6 -stdin)'"
      echo "PMART_MAIL_POSTMASTER_HASH='$(secret mail-postmaster-password | MSYS2_ARG_CONV_EXCL='*' openssl passwd -6 -stdin)'"
    fi
    if [[ "$comps" == *" s3 "* ]]; then
      echo "PMART_S3_ACCESS_KEY=$(secret s3-access-key)"
      echo "PMART_S3_SECRET_KEY=$(secret s3-secret-key)"
    fi
    # Vault secret-id(ADR-0100) — 그 PC IP 에서만 쓸 수 있고 90일에 만료(배포마다 새로)
    printf '%s' "$VAULT_ENV_LINES"
  } > "$dir/.env"

  # config-server 자신의 비밀(configtree: 파일 이름 = 속성 이름). build/ 에만 쓴다(git 제외)
  if [[ "$comps" == *" config-server "* ]]; then
    d="$dir/config-server-secrets"; mkdir -p "$d"
    secret config-encrypt-key > "$d/encrypt.key"
    secret config-encrypt-salt > "$d/encrypt.salt"
    secret config-password > "$d/spring.security.user.password"
    secret config-webhook-secret > "$d/spring.cloud.config.server.monitor.github.webhook-secret" # GitHub 웹훅 서명 비밀(ADR-0033)
    tr -d '\r' < "$SECRETS/config-deploy-key" > "$d/spring.cloud.config.server.git.private-key"
    hk="$(sed -e 's/#.*//' "$LAN/config-server/github-host-key.conf" | grep -v '^[[:space:]]*$' | head -1)"
    printf '%s' "${hk%% *}" > "$d/spring.cloud.config.server.git.host-key-algorithm"
    printf '%s' "${hk#* }" > "$d/spring.cloud.config.server.git.host-key"
  fi

  echo "$pc ${IP[$pc]} ${SSH[$pc]}" > "$dir/target"
  echo "rendered $dir ($(echo ${COMPS[$pc]} | wc -w) components)"
done
