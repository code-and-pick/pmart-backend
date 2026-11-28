# ADR-0098 embedding-service — ES 청크 → Ollama qwen3-embedding → 자기 pgvector

- 상태: **승인** (2026-11-28)
- 근거: 사용자 2026-11-28
  - "spring-ai-ollama 디펜던시가 필요한데...필요한 이유는 es에 저장된 청킹을 ollama의 qwen-embedding의 말뭉치로 전달하고 이 말뭉치를 이 qwen 모델이 고정 벡터로 변환한다음 이를 postgresql의 pgvector에 전달해서 이 벡터값이 저장되도록 해야 해. 그래서 이 spring-ai-ollama 디펜던시를 가지는 서비스가 따로 필요하지 않을까?" → "embedding-service 만들어줘"
  - ES-First 계획 §1-2 단계 3·4(ES → 임베딩 → pgvector 후 적재), §1-3(내림은 임베딩 없이), §8 D1(벡터는 pgvector).
  - 새 RAG 는 새 서비스로(ADR-0097) — 기존 두 RAG 서비스의 pgvector 를 쓰지 않는다.

## 결정
1. **새 서비스 `embedding-service`**(services/, 포트 8516, LAN PC 2 — Ollama·rag-pgvector 와 같은 PC). `spring-ai-ollama`·`spring-ai-model` 은 새 RAG 에서 이 서비스에만.
2. **DB**: RAG 전용 pgvector(`rag-pgvector`)에 새 DB·계정 `embedding_service`(rag-init-db.sh, 비밀 `.secrets/rag-db-embedding-password`, 설정 `{cipher}`). 표(V1):
   - `chunk_vector` — 키 (범위, chunkId). ES 청크와 같은 chunkId, vector(1024), 문서 ID·문서 판·제목 경로·본문 사본, 모델·판, `is_active`·`retired_at`. HNSW(cosine).
   - `embedding_cache` — 키 (임베딩한 글의 해시, 모델, 판).
3. **흐름**: chunking-service 가 문서 하나를 ES 에 반영하면 `POST /api/v1/internal/embedding-jobs {documentId, scopes}`(바로 202) → 일 줄(스레드 하나 — PC 2 Ollama CPU 를 다른 업무와 나눠 씀):
   1. ES(rag-operator·rag-customer)에서 그 문서의 ACTIVE 청크를 읽는다 — **ES 청크가 임베딩의 말뭉치**.
   2. 이미 같은 chunkId·같은 모델 판으로 다 들어 있으면 끝.
   3. 임베딩할 글 = 제목 경로 + 빈 줄 + 본문(조 제목의 뜻을 함께). 그 해시가 캐시에 없을 때만 Ollama 로(8개씩 묶어). 문서 쪽에는 지시문을 붙이지 않는다(질문 쪽만 — Qwen3-Embedding 권장).
   4. 같은 chunkId 로 pgvector 에 넣고, ES 에 없는 청크(옛 판·RETIRED)는 `is_active=false`(임베딩 없이).
   - 멱등. 알림 실패·서비스가 꺼져 있던 동안은 **맞춰 보기**(기동 때·10분마다 ES 와 pgvector 의 문서 판 비교)가 채운다.
4. **모델**: Spring AI `OllamaApi` 로 `/api/embed` 를 직접(options `num_gpu 0`·`num_ctx 2048`·`keep_alive` 를 싣기 위해 — `OllamaEmbeddingModel` 2.0.2 는 싣지 않음). LAN `qwen3-embedding:0.6b`, 판 `ac6da0dfba84`, 1024차원(지금 RAG 와 같은 모델). 시험·로컬은 `provider: fake`.
5. 질문 임베딩·벡터 검색(②)은 다음 단계 질문·답 서비스와 함께 정한다(이 서비스 API 로 낼지 포함).

## 확인 (2026-11-28, PC 1)
- 시험: 단위 3 + 통합 2(pgvector + ES 8.18 nori Testcontainers, chunking-service 의 실제 색인 정의로 색인 생성, 임베딩 fake): 알림 → ACTIVE 만 1024차원 저장, 캐시, 새 판에 옛 청크 내림, RETIRED → is_active=false, 맞춰 보기, 범위마다 따로(같은 chunkId).
- **실제 사슬**: ES·pgvector 컨테이너 + 세 서비스 jar + LAN PC 2 의 실제 Ollama. 실제 pmart-all-docs 커밋 1725acb 로 서명한 push 웹훅 → 추출 3 → ES 31 청크 → 임베딩 31(약 38초, customer_app 의 운영자 범위 7개는 캐시) → pgvector OPERATOR 24·CUSTOMER 14, 모두 1024차원·`ollama:qwen3-embedding:0.6b`·`ac6da0dfba84`.
  - 질문 "고객이 전화했을 때 본인인지 어떻게 확인해요?"(지시문 붙여 임베딩) → 코사인 1·2위 backoffice/05 제15조(상담 본인 확인과 주문 확인) 0.666·0.626, 3위 제5조(상담 접수).
