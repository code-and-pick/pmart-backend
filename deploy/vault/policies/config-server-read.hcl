# config-server 전용 — Vault 백엔드가 읽는 경로만(ADR-0027 {cipher} 대체). KV v2 마운트 config/.
# config-server 는 {application},{profile} · {application} · application,{profile} · application 을 GET 한다
# (VaultStudy LAB 03 실행 검증). 서비스 이름을 하나씩 적지 않고 마운트 전체 읽기로 둔다 — 이 마운트에는 config-server 가 내려줄 값만 둔다.
path "config/data/*" {
  capabilities = ["read"]
}
