#!/usr/bin/env bash
# =============================================================================
# migrate-cipher.sh [pmart-config 폴더] — {cipher} 제거 2단계: pmart-config *-lan.yml 의 {cipher} 를 풀어
#   Vault config/<서비스>,lan 에 Spring 속성 이름 그대로 넣는다(config-server Vault 백엔드가 읽는 경로).
#
#   - 값은 config-server 가 지금 내려주는 값과 바이트 단위로 같다(같은 EncryptorFactory 로 푼 값). 넣은 뒤 sha256 으로 하나씩 확인
#   - {cipher} 가 든 목록 항목(signing.keys[0])은 같은 항목의 일반 값(kid)도 넣는다 — Spring 은 목록을 우선순위 높은 출처 하나에서
#     통째로 받으므로 Vault 에 private-key 만 두면 Git 의 kid 가 사라진다
#   - 먼저 ./vault.sh setup 을 같은 토큰으로 돌린다(config/ 마운트·config-server-read 정책·AppRole — 이미 있으면 그대로)
#   - 경로마다 첫 필드는 kv put(경로를 새로), 나머지는 patch — 다시 돌리면 같은 결과(새 버전)
#   - 값·지문은 화면·파일에 내지 않는다. 관리자 토큰은 숨김 입력(또는 VAULT_TOKEN 환경변수)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."            # deploy/vault
CFG="${1:-$(cd ../../.. && pwd)/pmart-config}"
PROFILE="${PMART_CONFIG_PROFILE:-lan}"
[ -d "$CFG" ] || { echo "[migrate] pmart-config 폴더 없음: $CFG" >&2; exit 1; }

if [ -f tls/vault.crt ] && [ -f tls/vault.key ]; then export VAULT_LISTENER=tls VAULT_LOCAL_ADDR=https://127.0.0.1:8200 VAULT_SKIP_VERIFY=true
else export VAULT_LISTENER=local VAULT_LOCAL_ADDR=http://127.0.0.1:8200 VAULT_SKIP_VERIFY=false; fi
dc() { docker compose -f compose.yaml "$@"; }

G="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$G/$1" -name "$2" ! -name '*-sources.jar' 2>/dev/null | sort | tail -1; }
CP=""
for j in "$(jar org.springframework.cloud/spring-cloud-context 'spring-cloud-context-5.0.*.jar')" \
         "$(jar org.springframework.security/spring-security-crypto 'spring-security-crypto-7.0.*.jar')" \
         "$(jar org.springframework/spring-core 'spring-core-7.0.*.jar')" \
         "$(jar commons-logging/commons-logging 'commons-logging-1.3.*.jar')"; do
  [ -n "$j" ] || { echo "[migrate] Gradle 캐시에 jar 가 없다 — ./gradlew :platform-services:config-server:compileJava 한 번" >&2; exit 1; }
  CP="$CP${CP:+;}$(cygpath -w "$j")"
done

if [ -z "${VAULT_TOKEN:-}" ]; then
  read -rsp "관리자 토큰 (입력은 보이지 않음): " VAULT_TOKEN || [ -n "${VAULT_TOKEN:-}" ]; echo
fi
export VAULT_TOKEN
dc exec -T -e VAULT_TOKEN vault vault token lookup >/dev/null 2>&1 </dev/null || { echo "[migrate] 토큰이 안 맞거나 Vault 가 sealed" >&2; exit 1; }

./vault.sh setup

# 1) {cipher} 풀기 → "id TAB base64(값)" (셸 변수에만)
LIST="$(python -I verify/list-ciphers.py "$CFG" --with-list-siblings | tr -d '\r')"
PMART_ENC_KEY="$(dc exec -T -e VAULT_TOKEN vault vault kv get -field=config-encrypt-key pmart/ops/config-server </dev/null)"
PMART_ENC_SALT="$(dc exec -T -e VAULT_TOKEN vault vault kv get -field=config-encrypt-salt pmart/ops/config-server </dev/null)"
export PMART_ENC_KEY PMART_ENC_SALT
DEC="$(awk -F'\t' '$2=="cipher"{print $1"\t"$3}' <<<"$LIST" | PMART_B64_OUT=pipe java -cp "$CP" verify/CipherFingerprint.java b64 | tr -d '\r')"
unset PMART_ENC_KEY PMART_ENC_SALT
if grep -q $'\tERR$' <<<"$DEC"; then
  echo "[migrate] 복호화 실패 — 중단(아무것도 쓰지 않음):" >&2; awk -F'\t' '$2=="ERR"{print "  "$1}' <<<"$DEC" >&2; exit 1
fi
# plain(kid 등)은 그대로 base64
PLAIN="$(awk -F'\t' '$2=="plain"{print $1"\t"$3}' <<<"$LIST" | while IFS=$'\t' read -r id v; do printf '%s\t%s\n' "$id" "$(printf '%s' "$v" | base64 -w0)"; done)"
ALL="$(printf '%s\n%s\n' "$DEC" "$PLAIN" | awk 'NF' | sort)"
# "경로 필드 base64" — 경로 = config/<서비스>,<프로필>
ROWS="$(awk -F'\t' -v prof="$PROFILE" '{split($1,a,"|"); print "config/" a[1] "," prof " " a[2] " " $2}' <<<"$ALL")"
n_rows=$(grep -c . <<<"$ROWS"); n_paths=$(awk '{print $1}' <<<"$ROWS" | sort -u | wc -l)

# 2) 쓰기 — 컨테이너 안에서 base64 를 풀어 표준입력으로만 넘긴다
fails="$(printf '%s\n' "$ROWS" | dc exec -T -e VAULT_TOKEN vault sh -c '
  started=" "
  while read -r p f b; do
    case "$started" in *" $p "*) op=patch ;; *) op=put; started="$started$p " ;; esac
    printf "%s" "$b" | base64 -d | vault kv "$op" "$p" "$f=-" >/dev/null 2>&1 || echo "$p $f"
  done' | tr -d '\r')"
[ -z "$fails" ] || { echo "[migrate] 쓰기 실패:" >&2; sed 's/^/  /' <<<"$fails" >&2; exit 1; }

# 3) 확인 — 경로·필드마다 sha256 비교(정확히 같은 바이트)
declare -A WANT=()
while read -r p f b; do WANT["$p $f"]="$(printf '%s' "$b" | base64 -d | sha256sum | cut -c1-64)"; done <<<"$ROWS"
bad=0; ok=0
while read -r p f h; do
  [ -n "$p" ] || continue
  if [ "${WANT["$p $f"]:-}" = "$h" ]; then ok=$((ok+1)); else bad=$((bad+1)); echo "  다름 $p $f"; fi
done < <(awk '{print $1" "$2}' <<<"$ROWS" | dc exec -T -e VAULT_TOKEN vault sh -c '
  while read -r p f; do
    v="$(vault kv get -field="$f" "$p" </dev/null 2>/dev/null; echo x)"; v="${v%x}"
    printf "%s %s %s\n" "$p" "$f" "$(printf "%s" "$v" | sha256sum | cut -c1-64)"
  done' | tr -d '\r')
unset VAULT_TOKEN
echo "[migrate] config/*,$PROFILE: 경로 $n_paths 개, 필드 $n_rows 개({cipher} $(grep -c $'\tcipher\t' <<<"$LIST") + 목록 형제 $(grep -c $'\tplain\t' <<<"$LIST")) — 일치 $ok, 다름 $bad"
[ "$bad" = 0 ] && [ "$ok" = "$n_rows" ] || exit 1
