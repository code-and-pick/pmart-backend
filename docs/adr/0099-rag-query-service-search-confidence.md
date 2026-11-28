# ADR-0099 rag-query-service — ES-First 질문 검색과 SearchConfidenceEvaluator

- 상태: **승인** (2026-11-28)
- 근거: 사용자 2026-11-28 "SearchConfidenceEvaluator 만들었어?" → (질문·답 서비스를 새로 만들어 그 안에 두자는 제안에) "진행해줘."
  - ES-First 계획 §2-1(ES 키워드 → 충족 판정 → 벡터 → 생성), 2차 검증 검토(CPU 규칙 판정기, 원래 점수와 가중치 점수 구분, '실제로 돌려준 근거' 기준).
  - 새 RAG 는 새 서비스로(ADR-0097). 이름은 PC 1 이 정함(`rag-query-service`) — 사용자가 바꾸라면 바꾼다.

## 결정
1. **새 서비스 `rag-query-service`**(8517, LAN PC 2). DB·Kafka 없음. 지금은 서비스 간 경로 `POST /api/v1/internal/rag-searches {scope, question}` — 화면 공개(게이트웨이·권한: 운영자/고객 범위)는 화면 작업과 함께 따로 정한다.
2. **흐름**: ES BM25(범위 색인, 운영자는 POLICY ×1.5 로 다시 정렬 — 정렬에만) → `SearchConfidenceEvaluator` → 안전하면 **ES 에서 끝(상위 3 카드, 모델 호출 없음)** → 아니면 embedding-service 벡터 검색 → BM25 와 RRF(k 60) → 벡터 1위 코사인 < 0.58 이면 **답변 불가**, 아니면 상위 3 근거(HYBRID). ③ 생성(LLM)은 아직 없다.
3. **SearchConfidenceEvaluator**(domain, 순수 코드): 지표 = BM25 **원래 점수** 1위·1·2위 차·상대 차, 핵심어 비율(질문의 rag_nori 낱말 중 1위 청크 제목 경로+본문에 있는 비율), 제목 일치, 어구 일치(이웃 낱말 쌍), FAQ 질문 일치(1위가 FAQ 면 Jaccard).
   - **규칙에 쓰는 것**: 핵심어 비율 ≥ 0.7 그리고 상대 점수 차 ≥ 0.1(설정). 제목·어구·FAQ 질문 일치는 **기록만** — 효과는 다음 검증(새 dev 질문군)에서 잰다.
   - 기준값은 ES-First 2차 검증에서 dev+회귀로 고정한 값(`docs/rag/verification/2026-11-28/es-first-v2/config.json`)과 같다. 바꾸면 새 dev·final 질문군으로 다시 잰다.
4. **벡터 검색은 embedding-service 가 한다**(0.2.0 `POST /internal/vector-searches`): 모델·pgvector 의 주인이 그쪽이고(다른 서비스 DB 직접 접근 금지), 질문에 범위별 지시문을 붙여 문서와 같은 모델·판으로 임베딩한다(지시문은 설정 `pmart.embedding.query-instructions`).

## 확인 (2026-11-28, PC 1)
- 단위: 판정이 2차 검증 기록 149문항(회귀 88 + dev 38 + final 23)의 Python 판정과 모두 같음, 지표 계산·RRF·가중치 정렬(원래 점수 유지). embedding-service 통합 시험에 벡터 검색 API(범위 밖 청크 안 나옴).
- **실제 사슬**(ES·pgvector 컨테이너 + 네 서비스 jar + LAN PC 2 Ollama, 문서 88 다시 적재): 문서 흐름 141문항을 API 로 → **지표·끝난 곳이 2차 검증(Python)과 140/141 같음**. Java 끝난 곳 ES 38 · HYBRID 75 · NO_ANSWER 28, 질문 하나 HTTP p50 512 ms · p95 584 ms(순차, PC 2 CPU 임베딩 포함).
  - 다른 1건(N01 "연차는 1년에 며칠"): 벡터 1위 코사인 Java 0.5845 / 2차 기록 0.5197 → Java HYBRID, 기록 NO_ANSWER. 지금 색인에서 HNSW 와 정확 검색 결과가 같아(0.5845) **2차 때 근사 검색이 진짜 1위를 놓친 것** — 기준(0.58) 근처의 답변 불가 판정은 색인 빌드에 따라 흔들릴 수 있다. 2차 회귀 운영자 근거 없음 판정 12/15 는 정확 검색 기준 11/15.
  - CN04-1: 상대 점수 차 0.0078 / 0.0077(반올림 경계) — 끝난 곳은 같음.
