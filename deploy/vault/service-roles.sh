#!/usr/bin/env bash
# =============================================================================
# service-roles.sh [placement.conf] — 서비스마다 Vault 정책·AppRole 을 만든다({cipher} 제거 3-3).
#   정책 svc-<앱>  : config/data/<앱> · config/data/<앱>,* 읽기만(자기 비밀만)
#   AppRole svc-<앱>: 토큰 1h·최대 24h, secret-id 90일(VAULT_SECRET_ID_TTL), 로그인은 그 앱이 도는 PC IP 에서만
#                    (secret_id_bound_cidrs·token_bound_cidrs — secret-id 가 새도 다른 PC 에서는 못 쓴다)
#   Vault 가 도는 PC(인증서 SAN 의 IP)의 앱은 Docker 를 거쳐 들어오므로 Docker 내부 대역도 허용한다
#   (VAULT_LOCAL_CIDRS, 기본 172.16.0.0/12,192.168.65.0/24).
#   placement.conf 에서 빠진 앱의 역할은 지우지 않는다(목록만 알린다). 관리자 토큰은 숨김 입력(또는 VAULT_TOKEN).
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
PLACEMENT="${1:-$ROOT/deploy/lan/placement.conf}"
# 앱 목록(services/·platform-services/)은 placement.conf 가 든 저장소 기준 — 다른 작업 폴더(worktree)의 placement 를 넘기면 그 쪽 앱으로
APPS_ROOT="$(cd "$(dirname "$PLACEMENT")/../.." && pwd)"
SECRET_ID_TTL="${VAULT_SECRET_ID_TTL:-2160h}"   # 90일 — 배포마다 새로 발급, 오래된 것은 저절로 만료(그동안 재배포 없으면 재시작 때 로그인 실패)
LOCAL_CIDRS="${VAULT_LOCAL_CIDRS:-172.16.0.0/12,192.168.65.0/24}"
[ -f "$PLACEMENT" ] || { echo "[roles] placement.conf 없음: $PLACEMENT" >&2; exit 1; }

if [ -f tls/vault.crt ] && [ -f tls/vault.key ]; then export VAULT_LISTENER=tls VAULT_LOCAL_ADDR=https://127.0.0.1:8200 VAULT_SKIP_VERIFY=true
else export VAULT_LISTENER=local VAULT_LOCAL_ADDR=http://127.0.0.1:8200 VAULT_SKIP_VERIFY=false; fi
dc() { docker compose -f compose.yaml "$@"; }
HOST_IP="${VAULT_HOST_IP:-}"
if [ -z "$HOST_IP" ] && [ -f tls/vault.crt ]; then
  HOST_IP="$(MSYS2_ARG_CONV_EXCL='*' openssl x509 -in tls/vault.crt -noout -ext subjectAltName 2>/dev/null | grep -oE 'IP Address:[0-9.]+' | head -1 | cut -d: -f2 || true)"
fi

if [ -z "${VAULT_TOKEN:-}" ]; then
  read -rsp "관리자 토큰 (입력은 보이지 않음): " VAULT_TOKEN || [ -n "${VAULT_TOKEN:-}" ]; echo
fi
export VAULT_TOKEN
dc exec -T -e VAULT_TOKEN vault vault token lookup >/dev/null 2>&1 </dev/null || { echo "[roles] 토큰이 안 맞거나 Vault 가 sealed" >&2; exit 1; }
./vault.sh setup >/dev/null   # 정책 파일(pmart-deployer 등)·approle 을 먼저 맞춘다

ROWS="$(python -I placement-apps.py "$PLACEMENT" "$APPS_ROOT" | tr -d '\r' | while read -r app ip; do
  c="$ip/32"; [ -n "$HOST_IP" ] && [ "$ip" = "$HOST_IP" ] && c="$c,$LOCAL_CIDRS"
  echo "$app $c"
done)"
out="$(printf '%s\n' "$ROWS" | dc exec -T -e VAULT_TOKEN -e ttl="$SECRET_ID_TTL" vault sh -c '
  n=0
  while read -r app cidrs; do
    [ -n "$app" ] || continue
    printf "path \"config/data/%s\" {\n  capabilities = [\"read\"]\n}\npath \"config/data/%s,*\" {\n  capabilities = [\"read\"]\n}\n" "$app" "$app" \
      | vault policy write "svc-$app" - >/dev/null || { echo "FAIL policy $app"; continue; }
    vault write "auth/approle/role/svc-$app" token_policies="svc-$app" token_ttl=1h token_max_ttl=24h \
      secret_id_ttl="$ttl" secret_id_num_uses=0 secret_id_bound_cidrs="$cidrs" token_bound_cidrs="$cidrs" >/dev/null </dev/null \
      || { echo "FAIL role $app"; continue; }
    n=$((n+1))
  done
  echo "OK $n"
  echo "ROLES $(vault list -format=json auth/approle/role </dev/null | tr -d "[]\" \n" | tr , " ")"' | tr -d '\r')"
# Vault PC 의 앱이 Docker 를 거쳐 LAN 주소로 로그인되는지 실제로 확인(임시 secret-id → 로그인 → 폐기)
if [ -n "$HOST_IP" ] && [ "$VAULT_LISTENER" = tls ]; then
  app="$(awk -v h="$HOST_IP" '$2 ~ "^"h"/" {print $1; exit}' <<<"$ROWS")"
  if [ -n "$app" ]; then
    RID="$(dc exec -T -e VAULT_TOKEN vault vault read -field=role_id "auth/approle/role/svc-$app/role-id" </dev/null | tr -d "\r")"
    SID="$(dc exec -T -e VAULT_TOKEN vault vault write -f -field=secret_id "auth/approle/role/svc-$app/secret-id" </dev/null | tr -d "\r")"
    ACC="$(dc exec -T -e VAULT_TOKEN -e SID="$SID" vault sh -c 'vault write -field=secret_id_accessor "auth/approle/role/$1/secret-id/lookup" secret_id="$SID"' _ "svc-$app" </dev/null | tr -d "\r")"
    CA="$ROOT/deploy/lan/tls/pmart-lan-root-ca.crt"
    if MSYS_NO_PATHCONV=1 docker run --rm -e VAULT_ADDR="https://$HOST_IP:${VAULT_PORT:-8200}" -e VAULT_CACERT=/ca.pem \
         -e RID="$RID" -e SID="$SID" -v "$(cygpath -w "$CA"):/ca.pem:ro" \
         hashicorp/vault:1.21.4 sh -c 'vault write -field=token auth/approle/login role_id="$RID" secret_id="$SID" >/dev/null' </dev/null 2>/dev/null; then
      echo "[roles] 점검: 이 PC 의 Docker 컨테이너 → https://$HOST_IP:${VAULT_PORT:-8200} svc-$app 로그인 OK(TLS 사설 CA 검증)"
    else
      echo "[roles] 점검 실패: 이 PC 컨테이너에서 svc-$app 로그인 안 됨 — VAULT_LOCAL_CIDRS(Docker 대역)를 확인" >&2
    fi
    dc exec -T -e VAULT_TOKEN vault vault write "auth/approle/role/svc-$app/secret-id-accessor/destroy" secret_id_accessor="$ACC" >/dev/null </dev/null || true
    unset RID SID ACC
  fi
fi
unset VAULT_TOKEN
grep '^FAIL' <<<"$out" && exit 1 || true
ok="$(awk '/^OK/{print $2}' <<<"$out")"
echo "[roles] 서비스 정책·AppRole $ok 개 (PC 별: $(awk '{split($2,a,"/"); print a[1]}' <<<"$ROWS" | sort | uniq -c | awk '{printf "%s %s개  ", $2, $1}'))"
[ -n "$HOST_IP" ] && echo "[roles] Vault PC($HOST_IP) 의 앱은 Docker 대역도 허용: $LOCAL_CIDRS"
extra="$(comm -23 <(awk '/^ROLES/{for(i=2;i<=NF;i++) print $i}' <<<"$out" | grep '^svc-' | sed 's/^svc-//' | sort) <(awk '{print $1}' <<<"$ROWS" | sort))"
[ -z "$extra" ] || echo "[roles] placement 에 없는 역할(지우지 않음): $extra"
