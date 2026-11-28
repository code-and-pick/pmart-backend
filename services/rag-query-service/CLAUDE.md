# rag-query-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `rag-query-service` 절이다. 결정은 ADR-0099(새 RAG 범위 ADR-0097).

## 하는 일
질문 하나를 ES-First 로 검색: ES BM25 먼저 → `domain/SearchConfidenceEvaluator`(CPU 규칙)가 안전하면 ES 에서 끝(상위 3 카드) → 아니면 embedding-service 벡터 검색과 RRF → 벡터 코사인으로 답변 불가 판정. ③ 생성(LLM)은 아직 없음.

## 규칙
- 판정은 **원래 BM25 점수**로 한다. 가중치(POLICY ×1.5)는 정렬에만 — 가중치로 커진 점수 차이를 답변 가능성으로 읽지 않는다.
- 지표·규칙은 평가 스크립트 `deploy/lan/checks/eval/es-first-v2/es_first_eval2.py`·`analyze2.py` 와 같아야 한다. 한쪽을 바꾸면 다른 쪽도 바꾸고 `parity_rag_query.py` 와 단위 시험(`parity/python-decisions.json`)으로 대조한다.
- 기준값(설정 `pmart.rag-query.*`)을 바꾸려면 새 dev 질문군으로 고르고 새 final 로 한 번 검증한다(이미 쓴 final 로 고르지 않는다).
- ES 는 읽기만(색인 주인 chunking-service), 벡터는 embedding-service API 로만(DB 직접 접근 금지). 질문 원문은 로그 금지(경로·판정·건수·시간만).
- 계약 `contracts/openapi/rag-query-service.yaml`(컨트롤러 직접, `pmartOpenApi.enabled=false`).

## 실행
```bash
./gradlew :services:rag-query-service:check
```
로컬 포트 8517. LAN PC 2(ES es-search, embedding-service).
