#!/bin/sh
# RAG 전용 pgvector(ADR-0051): 서비스마다 DB·계정 하나 — 운영자용 ai_assistant_service, 고객용 customer_assistant_service,
# 새 RAG 벡터 embedding_service(ADR-0098, 2026-11-28 — 이미 있는 볼륨이면 이 스크립트를 docker exec 로 한 번 다시 돌린다).
# 볼륨이 처음 만들어질 때 postgres 엔트리포인트가 실행한다. 다시 돌려도 된다(없는 것만 만든다, 비밀번호는 그때마다 맞춘다).
# 서로의 DB 에는 접속 권한이 없다(PUBLIC CONNECT 회수). 관리 계정 pmart 는 확장·계정 관리용.
set -eu
# 포트는 컨테이너 환경 PGPORT(15433)
P="psql -v ON_ERROR_STOP=1 -U pmart -d postgres -q"
for pair in "ai_assistant_service:$PMART_RAG_OPERATOR_DB_PASSWORD" "customer_assistant_service:$PMART_RAG_CUSTOMER_DB_PASSWORD" "embedding_service:$PMART_RAG_EMBEDDING_DB_PASSWORD"; do
  name=${pair%%:*}; pw=${pair#*:}
  [ -n "$pw" ] || { echo "비밀번호 없음: $name" >&2; exit 1; }
  $P -v name="$name" -v pw="$pw" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'name') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'name')\gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'name', :'pw')\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'name', :'name') WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'name')\gexec
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'name')\gexec
SQL
  psql -v ON_ERROR_STOP=1 -U pmart -d "$name" -q -c "CREATE EXTENSION IF NOT EXISTS vector" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm"
done
echo "rag-init-db: ai_assistant_service, customer_assistant_service, embedding_service"
