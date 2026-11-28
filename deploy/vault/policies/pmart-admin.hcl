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
# config-server 용 KV 마운트(config/) 만들기·확인
path "sys/mounts/config" {
  capabilities = ["create", "read", "update"]
}
# config-server 가 읽는 값(서비스 비밀의 정본)
path "config/*" {
  capabilities = ["create", "read", "update", "patch", "delete", "list"]
}
# config-server AppRole secret-id 발급(배포 때)
path "auth/approle/role/config-server/secret-id" {
  capabilities = ["update"]
}
path "sys/audit" {
  capabilities = ["read", "sudo"]
}
# 감사 로그 켜기(setup — root 없이 관리자만으로 다시 세울 수 있게)
path "sys/audit/*" {
  capabilities = ["create", "read", "update", "sudo"]
}
path "sys/storage/raft/snapshot" {
  capabilities = ["read"]
}
