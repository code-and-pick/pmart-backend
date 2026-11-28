#!/usr/bin/env bash
# =============================================================================
# pmart-vault 실행기 — deploy/vault/vault.sh <명령>
#
#   up       Vault 서버 기동(처음엔 sealed·미초기화 상태로 뜬다)
#   status   상태(초기화·봉인 여부)
#   init     최초 1회: unseal 키·root 토큰 생성 → .init/ 파일로만 저장(화면 출력 없음)
#   unseal   봉인 해제(재시작할 때마다). 키를 숨김 입력으로 THRESHOLD 번 받는다
#   setup    KV v2 마운트(pmart/)·정책·감사 로그 등록. root 또는 관리자 토큰을 숨김 입력으로 받는다
#   snapshot 백업(Raft 스냅샷) — 관리자 토큰 숨김 입력, 기본 저장 위치 <pmart-project>/pmart-vault-backups/
#   renew    관리자·배포 토큰 갱신(32일 안에 한 번) — 숨김 입력
#   deployer-token  PC 1 render.sh 용 배포 토큰(pmart-deployer) — .init/ 파일로만
#   seed     비밀 파일 묶음(pmart-keys-pc6 구조)을 Vault 로 옮긴다 — seed.sh <폴더>
#   logs     서버 로그
#   down     중지(데이터 유지). 데이터까지 지우는 명령은 일부러 두지 않는다
#
# 비밀(토큰·unseal 키)은 명령줄 인자·파일·.env 에 넣지 않는다. 숨김 입력 → 환경변수 → exec -e 로만 넘긴다.
# Windows: Git Bash(Windows Terminal 권장 — mintty 는 숨김 입력이 안 될 수 있다) 또는 PowerShell 에서 "bash vault.sh <명령>".
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

# 모드: tls/ 에 인증서가 있으면 TLS(LAN), 없으면 로컬(127.0.0.1, TLS 없음)
if [ -f tls/vault.crt ] && [ -f tls/vault.key ]; then
  export VAULT_LISTENER=tls VAULT_LOCAL_ADDR=https://127.0.0.1:8200 VAULT_SKIP_VERIFY=true
  export VAULT_BIND="${VAULT_BIND:-0.0.0.0}"
else
  export VAULT_LISTENER=local VAULT_LOCAL_ADDR=http://127.0.0.1:8200 VAULT_SKIP_VERIFY=false
  export VAULT_BIND="${VAULT_BIND:-127.0.0.1}"
  if [ "$VAULT_BIND" != "127.0.0.1" ]; then
    echo "[vault] TLS 인증서(tls/vault.crt·vault.key) 없이 $VAULT_BIND 에 열 수 없다 — 중단" >&2; exit 1
  fi
fi
# 밖에 알리는 주소: TLS 모드면 인증서 SAN 의 첫 IP(예 https://192.168.0.34:8200), 아니면 127.0.0.1
if [ -z "${VAULT_API_ADDR:-}" ] && [ "$VAULT_LISTENER" = tls ]; then
  san_ip="$(MSYS2_ARG_CONV_EXCL='*' openssl x509 -in tls/vault.crt -noout -ext subjectAltName 2>/dev/null | grep -oE 'IP Address:[0-9.]+' | head -1 | cut -d: -f2)"
  [ -n "$san_ip" ] && VAULT_API_ADDR="https://$san_ip:${VAULT_PORT:-8200}"
fi
export VAULT_API_ADDR="${VAULT_API_ADDR:-$VAULT_LOCAL_ADDR}"

KEY_SHARES="${VAULT_KEY_SHARES:-3}"
KEY_THRESHOLD="${VAULT_KEY_THRESHOLD:-2}"
dc() { docker compose -f compose.yaml "$@"; }
vexec() { dc exec -T "$@" </dev/null; }   # 표준입력을 막아 숨김 입력과 섞이지 않게

# vault operator init -status: 0 = 초기화됨, 2 = 안 됨, 1 = 오류
init_state() { set +e; vexec vault vault operator init -status >/dev/null 2>&1; local rc=$?; set -e; echo "$rc"; }

case "${1:-}" in
  up)
    dc up -d --wait
    echo "[vault] 기동(모드 $VAULT_LISTENER, $VAULT_BIND:${VAULT_PORT:-8200}). 다음: ./vault.sh status"
    ;;

  status)
    set +e; vexec vault vault status; rc=$?; set -e
    case "$rc" in
      0) echo "[vault] unsealed — 사용 가능" ;;
      2) echo "[vault] sealed 또는 미초기화 — init(최초 1회) 또는 unseal 필요" ;;
      *) echo "[vault] 응답 없음 — ./vault.sh up / logs 확인"; exit 1 ;;
    esac
    ;;

  init)
    st="$(init_state)"
    [ "$st" = "2" ] || { echo "[vault] 이미 초기화됐거나 응답 없음(init -status=$st) — 중단"; exit 1; }
    umask 077; mkdir -p .init
    out=".init/init-$(date +%Y%m%d-%H%M%S).json"
    vexec vault vault operator init -key-shares="$KEY_SHARES" -key-threshold="$KEY_THRESHOLD" -format=json > "$out"
    echo "[vault] 초기화 완료. unseal 키 ${KEY_SHARES}개(해제에 ${KEY_THRESHOLD}개)·root 토큰을 아래 파일에만 저장했다:"
    echo "        $(pwd)/$out"
    echo "  1) 키 조각을 관리자마다 하나씩 구글 드라이브(본인만 접근) 등 안전한 곳에 옮긴다"
    echo "  2) ./vault.sh unseal → ./vault.sh setup"
    echo "  3) 관리자 토큰을 만든 뒤 root 토큰을 폐기하고(vault token revoke), 이 파일을 지운다"
    ;;

  unseal)
    for ((i = 1; i <= KEY_THRESHOLD; i++)); do
      set +e; vexec vault vault status >/dev/null 2>&1; rc=$?; set -e
      [ "$rc" = "0" ] && break
      read -rsp "unseal 키 $i/$KEY_THRESHOLD (입력은 보이지 않음): " VAULT_UNSEAL_KEY || [ -n "${VAULT_UNSEAL_KEY:-}" ]; echo
      export VAULT_UNSEAL_KEY
      vexec -e VAULT_UNSEAL_KEY vault sh -c 'vault operator unseal "$VAULT_UNSEAL_KEY" >/dev/null'
      unset VAULT_UNSEAL_KEY
    done
    "$0" status
    ;;

  setup)
    # 토큰: 환경변수 VAULT_TOKEN 이 있으면 그것(migrate-cipher.sh 가 넘긴다), 없으면 숨김 입력
    if [ -z "${VAULT_TOKEN:-}" ]; then
      read -rsp "root 또는 관리자 토큰 (입력은 보이지 않음): " VAULT_TOKEN || [ -n "${VAULT_TOKEN:-}" ]; echo
    fi
    export VAULT_TOKEN
    # 정책을 먼저 쓴다 — 관리자 토큰으로 돌릴 때 새 권한(config/ 마운트 등)이 바로 적용되게
    vexec -e VAULT_TOKEN vault sh -eu -c '
      for f in /vault/policies/*.hcl; do
        n="$(basename "$f" .hcl)"
        vault policy write "$n" "$f" >/dev/null
        echo "[setup] 정책 등록: $n"
      done
      for m in pmart config; do   # pmart/ = 팀·운영 비밀, config/ = config-server 가 읽는 서비스 비밀({cipher} 대체)
        if vault secrets list -format=json | grep -q "\"$m/\""; then
          echo "[setup] $m/ 마운트 이미 있음"
        else
          vault secrets enable -path="$m" -version=2 kv >/dev/null
          echo "[setup] $m/ (KV v2) 마운트"
        fi
      done
      # config-server 로그인(AppRole). secret-id 는 배포 때 관리자가 발급해 config-server 에만 준다
      if vault auth list -format=json | grep -q "\"approle/\""; then
        echo "[setup] approle 인증 이미 켜짐"
      else
        vault auth enable approle >/dev/null
        echo "[setup] approle 인증 켬"
      fi
      # config-server(ADR-0101 — Git + Vault 복합 백엔드). render 가 배포마다 secret-id 를 새로 발급하므로 90일 만료로
      # 옛 것이 저절로 사라지게(2026-11-28 PC 1 제안). 대가: 90일 동안 config-server PC 재배포가 없으면 재시작 때 로그인 실패.
      # IP 묶음은 두지 않는다 — Docker Desktop 포트 공개가 출발지를 게이트웨이(172.x)로 바꿔 의미가 없다(2026-11-28 장애)
      vault write auth/approle/role/config-server token_policies=config-server-read \
        token_ttl=1h token_max_ttl=24h secret_id_ttl=2160h secret_id_num_uses=0 >/dev/null
      echo "[setup] AppRole config-server (정책 config-server-read, 토큰 1h·최대 24h, secret-id 90일)"
      if vault audit list 2>/dev/null | grep -q "^file/"; then
        echo "[setup] 감사 로그 이미 켜짐"
      else
        vault audit enable file file_path=/vault/logs/audit.log >/dev/null
        echo "[setup] 감사 로그 켬: /vault/logs/audit.log"
      fi
    '
    unset VAULT_TOKEN
    echo "[vault] setup 완료. 비밀 넣기·팀원 로그인 방식은 README 참고"
    ;;

  snapshot)
    # Raft 스냅샷(암호화됨 — 복원하려면 unseal 키가 필요). 저장소 밖에 둔다. unseal 키와 다른 곳에 보관한다
    dir="${2:-$(cd ../../.. && pwd)/pmart-vault-backups}"
    mkdir -p "$dir"
    out="$dir/pmart-vault-$(date +%Y%m%d-%H%M%S).snap"
    read -rsp "관리자 토큰 (입력은 보이지 않음): " VAULT_TOKEN || [ -n "${VAULT_TOKEN:-}" ]; echo
    export VAULT_TOKEN
    vexec -e VAULT_TOKEN vault sh -c 'vault operator raft snapshot save /tmp/pmart.snap >/dev/null'
    unset VAULT_TOKEN
    dc cp vault:/tmp/pmart.snap "$out" >/dev/null
    vexec vault rm -f /tmp/pmart.snap
    echo "[vault] 스냅샷: $out ($(wc -c < "$out") bytes)"
    ;;

  renew)
    # 관리자·배포 토큰 갱신(주기 토큰 — 32일 안에 한 번). 숨김 입력, 남은 기간만 보여 준다
    read -rsp "갱신할 토큰 (입력은 보이지 않음): " VAULT_TOKEN || [ -n "${VAULT_TOKEN:-}" ]; echo
    export VAULT_TOKEN
    vexec -e VAULT_TOKEN vault sh -c 'vault token renew >/dev/null && t=$(vault token lookup -format=json | grep -o "\"ttl\": *[0-9]*" | grep -o "[0-9]*$") && n=$(vault token lookup -format=json | grep -o "\"display_name\": *\"[^\"]*" | sed "s/.*\"//") && echo "[vault] 갱신: $n — 남은 기간 $((t/86400))일 $(((t%86400)/3600))시간"'
    unset VAULT_TOKEN
    ;;

  deployer-token)
    # PC 1 render.sh 용 배포 토큰(정책 pmart-deployer — role-id 읽기·secret-id 발급만, 비밀은 못 읽음).
    # root 와 독립(-orphan), 32일 안에 renew. 화면에 내지 않고 .init/deployer-token.txt 에만 쓴다 → 옮긴 뒤 지운다
    read -rsp "관리자 토큰 (입력은 보이지 않음): " VAULT_TOKEN || [ -n "${VAULT_TOKEN:-}" ]; echo
    export VAULT_TOKEN
    umask 077; mkdir -p .init
    vexec -e VAULT_TOKEN vault vault token create -orphan -policy=pmart-deployer -period=768h       -display-name=pc1-render -field=token > .init/deployer-token.txt
    unset VAULT_TOKEN
    echo "[vault] 배포 토큰: $(pwd)/.init/deployer-token.txt ($(wc -c < .init/deployer-token.txt) bytes) — 구글 드라이브(관리자 폴더)로 옮겨 PC 1 에서만 쓰고, 이 파일은 지운다"
    ;;

  seed) shift; exec ./seed.sh "$@" ;;
  logs) dc logs --tail=200 vault ;;
  down) dc down; echo "[vault] 중지(데이터 볼륨 유지)" ;;
  *)
    sed -n '3,12p' "$0"; exit 1 ;;
esac
