# Vault 관리자 — 비밀 쓰기·교체, 정책·인증 방식 관리. root 토큰 대신 평소에 쓰는 권한.
path "pmart/*" {
  capabilities = ["create", "read", "update", "patch", "delete", "list"]
}
path "sys/policies/acl" {
  capabilities = ["list"]
}
path "sys/policies/acl/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}
path "sys/auth" {
  capabilities = ["read"]
}
path "sys/auth/*" {
  capabilities = ["create", "read", "update", "delete", "sudo"]
}
path "auth/*" {
  capabilities = ["create", "read", "update", "delete", "list", "sudo"]
}
path "sys/mounts" {
  capabilities = ["read"]
}
path "sys/audit" {
  capabilities = ["read", "sudo"]
}
path "sys/storage/raft/snapshot" {
  capabilities = ["read"]
}
