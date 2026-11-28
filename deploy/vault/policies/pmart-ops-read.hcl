# 운영 비밀(config-server 암호화 키·사설 CA 개인키·deploy key 등) 읽기 — 관리자·배포 스크립트만.
path "pmart/data/ops/*" {
  capabilities = ["read"]
}
path "pmart/metadata/ops/*" {
  capabilities = ["list", "read"]
}
