# LAN 모드 — deploy/vault/tls/vault.crt·vault.key 가 있으면 vault.sh 가 이 파일을 쓴다.
# 인증서: PC 1 에서 deploy/lan/lan.sh tls-issue <pc> → .secrets/tls/<pc>.crt·<pc>.key 를 이 PC 의 deploy/vault/tls/ 로
#   (사설 CA — docs/architecture/lan-https-private-ca.md). 팀원 PC 는 pmart-lan-root-ca.crt 를 신뢰해야 한다.
listener "tcp" {
  address         = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:8201"
  tls_disable     = false
  tls_cert_file   = "/vault/tls/vault.crt"
  tls_key_file    = "/vault/tls/vault.key"
  tls_min_version = "tls12"
}
