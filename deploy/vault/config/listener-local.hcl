# 로컬 모드 — TLS 없음. vault.sh 가 이 모드에서는 127.0.0.1 에만 연다(LAN 에 열지 못하게 막는다).
listener "tcp" {
  address         = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_disable     = true
}
