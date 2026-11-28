#!/usr/bin/env bash
# =============================================================================
# verify-cipher.sh [pmart-config 폴더] — {cipher} 제거 1단계: pmart-config 의 {cipher} 값마다
#   Vault 에 같은 평문이 들어 있는지 확인한다. 값·해시는 출력하지 않는다(이름·일치 여부만).
#
#   1) Vault pmart/ops/config-server 의 config-encrypt-key·salt 로 암호문을 푼다
#      (config-server 와 같은 EncryptorFactory — CipherFingerprint.java). 평문은 Java 프로세스 밖으로 안 나온다(sha256 만)
#   2) Vault 필드(seed-map*.conf 의 경로·필드)마다 sha256 을 만든다
#   3) 끝 공백을 뗀 값끼리 비교해 암호문마다 같은 값을 가진 Vault 필드를 적는다
#
# 관리자 토큰은 숨김 입력(또는 VAULT_TOKEN 환경변수). 키·값·지문은 셸 변수에만 두고 파일로 쓰지 않는다.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."            # deploy/vault
CFG="${1:-$(cd ../../.. && pwd)/pmart-config}"
[ -d "$CFG" ] || { echo "[verify] pmart-config 폴더 없음: $CFG" >&2; exit 1; }

if [ -f tls/vault.crt ] && [ -f tls/vault.key ]; then export VAULT_LISTENER=tls VAULT_LOCAL_ADDR=https://127.0.0.1:8200 VAULT_SKIP_VERIFY=true
else export VAULT_LISTENER=local VAULT_LOCAL_ADDR=http://127.0.0.1:8200 VAULT_SKIP_VERIFY=false; fi
dc() { docker compose -f compose.yaml "$@"; }

# Spring jar(Gradle 캐시) — config-server 와 같은 판(Boot 4.0.9 / Cloud 2025.1.4)
G="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$G/$1" -name "$2" ! -name '*-sources.jar' 2>/dev/null | sort | tail -1; }
CP=""
for j in "$(jar org.springframework.cloud/spring-cloud-context 'spring-cloud-context-5.0.*.jar')" \
         "$(jar org.springframework.security/spring-security-crypto 'spring-security-crypto-7.0.*.jar')" \
         "$(jar org.springframework/spring-core 'spring-core-7.0.*.jar')" \
         "$(jar commons-logging/commons-logging 'commons-logging-1.3.*.jar')"; do
  [ -n "$j" ] || { echo "[verify] Gradle 캐시에 jar 가 없다 — ./gradlew :platform-services:config-server:compileJava 한 번" >&2; exit 1; }
  CP="$CP${CP:+;}$(cygpath -w "$j")"
done

if [ -z "${VAULT_TOKEN:-}" ]; then
  read -rsp "관리자 토큰 (입력은 보이지 않음): " VAULT_TOKEN || [ -n "${VAULT_TOKEN:-}" ]; echo
fi
export VAULT_TOKEN
dc exec -T -e VAULT_TOKEN vault vault token lookup >/dev/null 2>&1 </dev/null || { echo "[verify] 토큰이 안 맞거나 Vault 가 sealed" >&2; exit 1; }

# 1) 암호문 지문
PMART_ENC_KEY="$(dc exec -T -e VAULT_TOKEN vault vault kv get -field=config-encrypt-key pmart/ops/config-server </dev/null)"
PMART_ENC_SALT="$(dc exec -T -e VAULT_TOKEN vault vault kv get -field=config-encrypt-salt pmart/ops/config-server </dev/null)"
export PMART_ENC_KEY PMART_ENC_SALT
CIPHER_FP="$(python -I verify/list-ciphers.py "$CFG" | java -cp "$CP" verify/CipherFingerprint.java fp | tr -d "\r")"   # Windows 의 Java 는 줄 끝이 CRLF
unset PMART_ENC_KEY PMART_ENC_SALT

# 2) Vault 필드 지문(끝 공백 제거) — 값은 컨테이너에서 base64 로 받아 이 셸 안에서만 푼다
PAIRS="$(cat seed-map.conf seed-map-lan.conf | awk '!/^[[:space:]]*(#|$)/ && $3!="env" {print $1" "$2}' | sort -u)"
declare -A VFP=()   # sha256(끝 공백 뗀 값) → "경로:필드 …"
while read -r p f b64; do
  [ -n "$b64" ] || continue
  v="$(printf '%s' "$b64" | base64 -d)"; v="${v%"${v##*[![:space:]]}"}"
  h="$(printf '%s' "$v" | sha256sum | cut -c1-64)"
  VFP[$h]="${VFP[$h]:-}${VFP[$h]:+, }$p:$f"
done < <(printf '%s\n' "$PAIRS" | dc exec -T -e VAULT_TOKEN vault sh -c '
  while read -r p f; do
    v="$(vault kv get -field="$f" "pmart/$p" </dev/null 2>/dev/null; echo x)" || continue   # </dev/null: 목록(표준입력)을 먹지 않게
    v="${v%x}"; [ -n "$v" ] || continue
    printf "%s %s %s\n" "$p" "$f" "$(printf "%s" "$v" | base64 | tr -d "\n")"
  done' | tr -d "\r")
unset VAULT_TOKEN

# 3) 비교
total=0; ok=0; none=0; err=0
declare -A SEEN=()
while IFS=$'\t' read -r id len hfull hstrip; do
  total=$((total+1))
  if [ "$len" = "ERR" ]; then printf '  복호화 실패  %s\n' "$id"; err=$((err+1)); continue; fi
  m="${VFP[$hstrip]:-}"
  if [ -n "$m" ]; then ok=$((ok+1)); SEEN[$m]=$(( ${SEEN[$m]:-0} + 1 ))
  else printf '  Vault 에 없음 %-70s (%s자)\n' "$id" "$len"; none=$((none+1)); fi
done <<<"$CIPHER_FP"
echo "[verify] 일치한 값 → Vault 필드 (암호문 개수):"
for m in "${!SEEN[@]}"; do printf '  %3d  %s\n' "${SEEN[$m]}" "$m"; done | sort -k2
echo "[verify] {cipher} $total 개: Vault 와 일치 $ok, Vault 에 없음 $none, 복호화 실패 $err"
