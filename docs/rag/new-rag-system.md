# 새 RAG 시스템 구조 — ES-First 하이브리드 (2026-11-28)

새 RAG 는 **새 서비스로만** 짓는다(ADR-0097). 기존 `ai-assistant-service`·`customer-assistant-service` 는 새 RAG 에서 제외 — 지우지 않고 그대로 둔다.
방향은 ES-First 하이브리드(`docs/rag/planning/2026-11-27-es-first-hybrid-plan.md` §8 D1 — ES BM25 먼저, 부족하면 pgvector)로 **확정**이다.

## 1. 한눈에

```
GitHub code-and-pick/pmart-all-docs (private, 원본)
   │ push · delete 웹훅 (id 717905801, 서명 X-Hub-Signature-256)
   ▼
smee.io 채널 ──▶ docs-relay (PC 2, smee-client)
   │ POST /api/v1/internal/docs-webhook
   ▼
integration-service (8514)  ── Spring Integration ──  ADR-0095
   │  서명·이벤트·main 확인 → 바뀐 .md 파일 → GitHub API 로 그 커밋 판 읽기 → 내용 추출
   │ POST /api/v1/internal/documents  (ExtractedDocument)
   ▼
chunking-service (8515)  ── Spring AI MarkdownDocumentReader + TokenTextSplitter ──  ADR-0096
   │  청킹 → ES 에 문서 단위 반영 (새 판 넣고 옛 판 지움, 삭제는 RETIRED)
   ▼
Elasticsearch es-search (PC 3)   rag-operator → rag-operator-v1 / rag-customer → rag-customer-v1 (nori, 벡터 없음)
   │  ▲
   │  │ ① (알림) chunking-service → POST /api/v1/internal/embedding-jobs   ※ 아래 §4 확인 필요
   │  │ ② (맞춰 보기) embedding-service 가 10분마다 ES 와 pgvector 를 비교
   ▼  │
embedding-service (8516)  ── Spring AI Ollama ──  ADR-0098
   │  ES 의 ACTIVE 청크(말뭉치) → Ollama qwen3-embedding:0.6b (PC 2, CPU) → 1024차원 고정 벡터
   ▼
PostgreSQL + pgvector  rag-pgvector (PC 2) DB embedding_service — chunk_vector(같은 chunkId) · embedding_cache

(다음) 새 질문·답 서비스: ES BM25 먼저 → 부족하면 질문 임베딩 → pgvector → 합치기 → 생성
```

## 2. 서비스별 역할과 범위

| 서비스 | 하는 일 | 쓰는 기술 | 갖는 것 | 하지 않는 것 |
|---|---|---|---|---|
| **integration-service** (ADR-0095) | 문서 저장소 웹훅을 받아 바뀐 파일(추가·수정·삭제)을 확인하고, `.md` 를 GitHub API 로 그 커밋 판에서 읽어 내용 추출 → chunking-service 로 전달 | **Spring Integration**(이 서비스에만) — HTTP inbound gateway·filter·split·ExecutorChannel·retry advice | 없음(DB·Kafka·로컬 파일 없음) | 청킹·ES·임베딩 |
| **chunking-service** (ADR-0096) | 추출 내용을 청킹해 ES 에 전달(문서 단위로 바꿔 넣기) | **Spring AI** `MarkdownDocumentReader`(제목별)·`TokenTextSplitter`(긴 절) | ES 색인 rag-operator·rag-customer | 임베딩·pgvector |
| **embedding-service** (ADR-0098) | ES 에 저장된 청크를 Ollama qwen3-embedding 의 말뭉치로 넘겨 고정 벡터로 바꾸고 pgvector 에 저장 | **Spring AI Ollama**(`OllamaApi` `/api/embed`, 새 RAG 에서 이 서비스에만) | pgvector `chunk_vector`·`embedding_cache`(DB embedding_service) | ES 쓰기(읽기만), 질문·답 |
| (다음) 질문·답 서비스 | ES 먼저 → pgvector → 생성 | — | — | — |

## 3. 데이터

- **원본**: GitHub `code-and-pick/pmart-all-docs`(처음 이름 pmart-rag-docs). 폴더 `operator/<영역>/`(직원), `customer/customer_app/`(고객 앱 안내 — 직원 RAG 도 씀), `customer/faq/`. 폴더 README 는 색인하지 않는다.
- **범위 → 색인**: `operator/**` → rag-operator, `customer/customer_app/**` → 두 색인, `customer/faq/**` → rag-customer.
- **ES 청크**(1 청크 = 1 문서): `chunkId`(문서 ID·문서 해시·순번), `documentId`(경로), `commit`, `accessScope`, `title`, `heading`·`headingLevel`, `sectionPath`(문서 제목 > 절 제목), `ordinal`, `content`, `chunkSha256`, `contentSha256`(문서 판), `status`(ACTIVE·RETIRED).
- **pgvector**: `chunk_vector` 키 (범위, chunkId) — ES 와 **같은 chunkId**, vector(1024, HNSW cosine), 문서 판·제목 경로·본문 사본, 모델·판, `is_active`. `embedding_cache` 키 (임베딩한 글 해시, 모델, 판). 임베딩한 글 = 제목 경로 + 빈 줄 + 본문(문서 쪽 지시문 없음).
- **모델**: `qwen3-embedding:0.6b`, 판 `ac6da0dfba84`, CPU(`num_gpu 0`), `num_ctx 2048` — 기존 RAG 와 같은 모델.

## 4. 확인이 필요한 것 (2026-11-28 사용자 질문)

- 사용자: "embedding-service는 청킹을 es에게 전달만 하는 역할 범위만 정의했는데???"
- 정의와 다른 곳: **chunking-service 가 ES 반영 뒤 embedding-service 를 부르는 알림(`EmbeddingNotifier`, 설정 `embedding-url`)을 더했다.** 정의는 "청킹해서 ES 에 전달"까지.
  - 알림을 빼면 chunking-service 는 ES 전달까지만 하고, embedding-service 가 ES 를 직접 보고(맞춰 보기 주기를 짧게) 새 청크를 가져온다.
- 사용자 답을 받으면 이 절과 ADR-0096·0098 을 고친다.

## 5. LAN 배치 (PC 2, 아직 배포 안 함)

| 구성요소 | 포트 | 비고 |
|---|---|---|
| docs-relay | — | smee 채널 `.secrets/rag-docs-smee-url` → integration-service |
| integration-service | 8514 | 설정 `integration-service*.yml` — 웹훅 비밀·GitHub 토큰 `{cipher}`, `chunking-url` |
| chunking-service | 8515 | `chunking-service*.yml` — `elasticsearch-url: http://es-search:9200`, `embedding-url` |
| embedding-service | 8516 | `embedding-service*.yml` — DB `rag-pgvector:15433/embedding_service`(비밀번호 `{cipher}`), Ollama `http://ollama:11434` |

- placement pc2 줄 끝(render.sh `LATE` — 고정 IP 순서), PC 2 안에서만 불림(`NO_EXTRA_HOST`).
- 비밀(PC 1 `deploy/lan/.secrets`, 목록 `PC1-only-data.md`): `rag-docs-smee-url`·`rag-docs-webhook-secret`·`rag-docs-github-token`(PAT `pmart-rag-docs-read`, Contents 읽기 전용)·`rag-db-embedding-password`.
- 배포 때: 이미지 3개 → `lan.sh render` → PC 2 `rag-pgvector` 의 `rag-init-db.sh` 다시 돌림(새 DB) → 세 서비스·docs-relay 올림 → pmart-all-docs 에 push 해 ES·pgvector 확인.

## 6. 확인한 것 (PC 1, 2026-11-28)

- 시험: integration check 단위 5·통합 2, chunking check 단위 5·통합 2(ES 8.18 + nori), embedding check 단위 3·통합 2(pgvector + ES).
- 실제 사슬: ES·pgvector 컨테이너 + 세 jar + LAN PC 2 Ollama, 실제 커밋 1725acb 로 서명한 push → 추출 3 → ES 31 청크 → 임베딩 31(약 38초) → pgvector 1024차원. 질문 "고객이 전화했을 때 본인인지 어떻게 확인해요?" 벡터 검색 1·2위 backoffice/05 제15조.
- 찾아 고친 것: 리더가 인라인 코드 있는 절의 `category` 를 덮어씀(제목 단계는 원문 `#` 개수로), `TokenTextSplitter` 가 5자 미만 조각을 버림(1자로), 나눈 조각의 제목 경로.
- 남은 제약: 리더가 표·목록 줄바꿈을 한 줄로 붙인다(표 청크 35/650) — BM25 에는 지장 없음, 표 행 근거 표시는 원문 필요.

- **ES-First ① E2E(BM25 만)**: 실제 main 전체(.md 88) push → rag-operator 650·rag-customer 197 청크 → 평가셋 88문항. 운영자 기대 문서 1위 69%·3위 안 100%, 고객(안내 또는 같은 주제 FAQ) 1위 90%·3위 안 97%. 점수만으로는 근거 없는 질문을 못 가르고, 1·2위 점수 차(중앙 4.7 vs 0.8)가 더 가른다 — `docs/rag/verification/2026-11-28/es-first-bm25/`.

- **ES-First 2차 검증**(`docs/rag/verification/2026-11-28/es-first-v2/`): 색인 v2(documentType), 운영자 POLICY ×1.5, ES 종료 = 핵심어 비율 ≥0.7·상대 점수 차 ≥0.1 → 상위 3 카드, 넘긴 질문은 BM25+벡터 RRF·벡터 코사인 <0.58 답변 불가. 회귀·dev·final 모두 잘못 끝냄 0(final 종료 운영자 4/12·고객 1/10). 새 문항에서는 벡터가 BM25 보다 강함.

## 7. 커밋 (로컬, push 전)

- pmart-backend(wt-rag-p1, 브랜치 rag/p1-exit-metrics): 8e8164279 integration · 091287aa2 chunking · 933b0c70b chunking 고침 · 3ee098098 ADR-0097 · e7e80d76b embedding
- pmart-config: 5241135 · 535b396 · 2b5d4c7
