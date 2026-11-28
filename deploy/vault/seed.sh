#!/usr/bin/env bash
# =============================================================================
# seed.sh <묶음 폴더> — 비밀 파일 묶음을 seed-map.conf 대로 Vault(pmart/)에 넣는다.
#   예: ./vault.sh seed /c/development/pmart-project/pmart-keys-pc6/pmart-keys-pc6
#
# - 값은 화면·명령줄 인자·임시 파일에 나오지 않는다: 파일 → 표준입력 → vault kv ... FIELD=- 로만 넘긴다.
# - 같은 경로의 첫 필드는 kv put(경로를 새로 씀), 나머지는 kv patch — 다시 돌리면 같은 결과(새 버전으로 남는다).
# - 끝나면 필드마다 원본 길이와 Vault 값 길이를 맞춰 본다(값은 보지 않는다).
# - 토큰(관리자 또는 root)은 숨김 입력으로 받는다.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"
SRC="${1:?묶음 폴더를 준다 — 예: ./vault.sh seed <pmart-keys-pc6 폴더> [이관 표, 기본 seed-map.conf]}"
MAP="${2:-seed-map.conf}"
[ -f "$MAP" ] || { echo "[seed] 이관 표 없음: $MAP" >&2; exit 1; }
[ -d "$SRC" ] || { echo "[seed] 폴더 없음: $SRC" >&2; exit 1; }

# vault.sh 와 같은 모드 변수(컨테이너는 이미 떠 있어야 한다)
if [ -f tls/vault.crt ] && [ -f tls/vault.key ]; then export VAULT_LISTENER=tls VAULT_LOCAL_ADDR=https://127.0.0.1:8200 VAULT_SKIP_VERIFY=true
else export VAULT_LISTENER=local VAULT_LOCAL_ADDR=http://127.0.0.1:8200 VAULT_SKIP_VERIFY=false; fi
dc() { docker compose -f compose.yaml "$@"; }

if [ -z "${VAULT_TOKEN:-}" ]; then
  read -rsp "관리자 또는 root 토큰 (입력은 보이지 않음): " VAULT_TOKEN || [ -n "${VAULT_TOKEN:-}" ]; echo
fi
export VAULT_TOKEN
dc exec -T -e VAULT_TOKEN vault vault token lookup >/dev/null 2>&1 </dev/null || { echo "[seed] 토큰이 안 맞거나 Vault 가 sealed — 중단" >&2; exit 1; }

# 값 하나를 표준입력으로 넣는다. $1 경로 $2 필드 — 값은 이 함수의 표준입력.
# 파이프(emit | kv_write)로 부르면 서브셸이라 STARTED 가 안 남아 필드마다 put 으로 덮어쓴다 → < <(…) 로 부른다(2026-11-28 시험에서 확인)
declare -A STARTED=()
kv_write() {
  local op=patch
  [ -z "${STARTED[$1]:-}" ] && op=put && STARTED[$1]=1
  dc exec -T -e VAULT_TOKEN vault vault kv "$op" "pmart/$1" "$2=-" >/dev/null
}
# 원본에서 넣을 값을 표준출력으로(검증 때도 같은 함수로 길이를 잰다)
emit() { # $1 종류 $2 파일
  case "$1" in
    line)   tr -d '\r\n' < "$2" ;;
    raw)    cat "$2" ;;
    base64) base64 -w0 < "$2" ;;
  esac
}
env_value() { # $1 파일 $2 키 — 첫 '=' 뒤, CR 제거, 감싼 따옴표 제거
  local v; v="$(grep -m1 "^$2=" "$1" | cut -d= -f2- | tr -d '\r')"
  v="${v#\"}"; v="${v%\"}"; v="${v#\'}"; v="${v%\'}"
  printf '%s' "$v"
}

declare -a CHECK=()   # "경로|필드|원본 길이|원본 sha256" (해시는 화면에 내지 않는다)
fp() { local t; t="$(mktemp)"; cat > "$t"; printf '%s|%s' "$(wc -c < "$t" | tr -d ' ')" "$(sha256sum < "$t" | cut -c1-64)"; rm -f "$t"; }
missing=0
while read -r path field kind pattern; do
  case "$path" in ''|'#'*) continue ;; esac
  # glob 은 하나만 맞아야 한다
  shopt -s nullglob; files=( "$SRC"/$pattern ); shopt -u nullglob
  if [ "${#files[@]}" -ne 1 ]; then echo "[seed] 건너뜀(파일 ${#files[@]}개): $pattern" >&2; missing=$((missing+1)); continue; fi
  f="${files[0]}"
  if [ "$kind" = env ]; then
    while IFS= read -r key; do
      kv_write "$path" "$key" < <(env_value "$f" "$key")
      CHECK+=( "$path|$key|$(env_value "$f" "$key" | fp)" )
    done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$f" | cut -d= -f1)
  else
    kv_write "$path" "$field" < <(emit "$kind" "$f")
    CHECK+=( "$path|$field|$(emit "$kind" "$f" | fp)" )
  fi
done < "$MAP"

echo "[seed] 넣은 필드 ${#CHECK[@]}개, 경로 ${#STARTED[@]}개 — 원본과 비교(길이·sha256):"
bad=0
for c in "${CHECK[@]}"; do
  IFS='|' read -r path field wlen wsum <<<"$c"
  # Vault 값의 길이|sha256 — 값 자체는 컨테이너 밖으로 나오지 않는다. 끝 줄바꿈 보존을 위해 x 를 붙였다 뗀다
  got="$(dc exec -T -e VAULT_TOKEN vault sh -c 'v="$(vault kv get -field="$1" "pmart/$2"; echo x)"; v="${v%x}"; printf "%s|%s" "$(printf "%s" "$v" | wc -c | tr -d " ")" "$(printf "%s" "$v" | sha256sum | cut -c1-64)"' _ "$field" "$path" </dev/null | tr -d '\r')"
  if [ "$got" = "$wlen|$wsum" ]; then printf '  OK   %-46s %-34s %s자\n' "pmart/$path" "$field" "$wlen"
  else printf '  다름 %-46s %-34s 원본 %s자 / Vault %s자\n' "pmart/$path" "$field" "$wlen" "${got%%|*}"; bad=$((bad+1)); fi
done
unset VAULT_TOKEN
[ "$bad" = 0 ] && [ "$missing" = 0 ] && echo "[seed] 완료 — 모두 일치" || { echo "[seed] 확인 필요: 다름 $bad, 건너뜀 $missing" >&2; exit 1; }
