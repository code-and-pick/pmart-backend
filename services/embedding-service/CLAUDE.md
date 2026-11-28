# embedding-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `embedding-service` 절이다. 결정은 ADR-0098(앞 단계 ADR-0095·0096, 범위 ADR-0097).

## 하는 일
chunking-service 가 ES(rag-operator·rag-customer)에 넣은 ACTIVE 청크를 읽어 Ollama `qwen3-embedding:0.6b` 로 1024차원 벡터를 만들고, 같은 chunkId 로 자기 pgvector(`embedding_service`)에 넣는다. ES 에서 내린 청크는 `is_active=false`. ES-First 하이브리드 RAG 의 "ES → 임베딩 → pgvector".

## 규칙
- ES 는 **읽기만**(색인 주인은 chunking-service). 다른 서비스 DB 에 붙지 않는다(기존 ai-assistant·customer-assistant 의 pgvector 도 — ADR-0097).
- Ollama 는 PC 2 CPU 를 다른 업무와 나눠 쓴다 — 일 줄은 스레드 하나, 같은 글은 캐시(`embedding_cache`). 대량 재임베딩 전에는 사용자에게 알린다.
- 모델·판을 바꾸면 설정 `pmart.embedding.ollama.version` 을 바꾼다(캐시 키가 바뀌어 다시 채워진다). 차원을 바꾸려면 스키마(vector(1024))도 새 마이그레이션으로.
- 문서 본문·벡터는 로그 금지(경로·건수만).
- 계약 `contracts/openapi/embedding-service.yaml`(컨트롤러 직접, `pmartOpenApi.enabled=false`).

## 패키지
`com.pmart.embedding.{api, application, domain, infrastructure}` — 받기 `api/EmbeddingJobController`, 일 줄 `application/EmbeddingQueue`, 맞춤 `application/DocumentEmbedding`, 맞춰 보기 `application/Reconciler`, ES 읽기 `infrastructure/EsChunks`, 저장 `infrastructure/VectorStore`, 모델 `infrastructure/OllamaEmbedding`·`FakeEmbedding`(시험).

## 실행
```bash
./gradlew :services:embedding-service:check     # 단위 + 통합(pgvector + ES nori Testcontainers, 임베딩 fake) + 계약 검증
```
로컬 포트 8516. LAN PC 2(rag-pgvector `embedding_service`, Ollama, es-search 읽기).
