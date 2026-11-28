# 팀원·실행 스크립트(up.sh)용 — pmart/shared/** 읽기만.
# KV v2 정책은 CLI 경로(pmart/shared/x)가 아니라 API 경로(pmart/data/shared/x)로 쓴다.
path "pmart/data/shared/*" {
  capabilities = ["read"]
}
path "pmart/metadata/shared/*" {
  capabilities = ["list", "read"]
}
