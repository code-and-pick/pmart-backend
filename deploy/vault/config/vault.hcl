# =============================================================================
# pmart-vault 서버 설정 — 단일 노드 Raft
# [공식 문서] https://developer.hashicorp.com/vault/docs/configuration/storage/raft
# 바탕: VaultStudy/vault-config-study vault/config/config.hcl (LAB 08 실행 검증)
# listener 는 모드별 파일(listener-local.hcl / listener-tls.hcl)을 vault.sh 가 골라 넣는다.
# api_addr 는 compose 의 VAULT_API_ADDR 환경변수로 준다.
# =============================================================================
ui            = true
disable_mlock = true   # 컨테이너 환경. 메모리 스왑에 비밀이 남지 않게 호스트 스왑 정책을 따로 확인한다

storage "raft" {
  path    = "/vault/file"     # compose named volume pmart-vault_data
  node_id = "pmart-vault-1"
}

cluster_addr = "http://127.0.0.1:8201"
