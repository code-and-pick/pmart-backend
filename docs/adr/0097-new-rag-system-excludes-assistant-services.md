# ADR-0097 새 RAG 시스템은 새 서비스로 — ai-assistant-service·customer-assistant-service 제외(삭제 안 함)

- 상태: **승인** (2026-11-28)
- 근거: 사용자 2026-11-28 "일단 새 술은 새 부대에!!! ai-assistant-service와 customer-assistant-service. 이 두 서비스는 rag 시스템에서 제외시키자...삭제는 하지말고"
  - 앞선 말: "지금 구조를 다 뜯어고칠거야", ES-First 하이브리드 확정(`docs/rag/planning/2026-11-27-es-first-hybrid-plan.md` §8 D1).

## 결정
1. **ES-First 하이브리드 RAG 는 새 서비스로만 짓는다.**
   - 적재: `integration-service`(ADR-0095, 문서 저장소 웹훅 → 추출) → `chunking-service`(ADR-0096, 청킹 → ES) → 다음 단계 임베딩 서비스(ES 청크 → Ollama 임베딩 → 자기 pgvector).
   - 질문·답(ES 먼저 → pgvector → 생성)도 새 서비스가 맡는다(다음에 정함).
   - 원본은 GitHub `code-and-pick/pmart-all-docs`.
2. **`ai-assistant-service`(운영자 RAG)·`customer-assistant-service`(고객 RAG)는 새 RAG 시스템에서 제외한다.**
   - 새 서비스들은 이 둘을 부르지도, 이 둘의 DB·색인을 쓰지도 않는다. 이 둘을 새 구조에 맞춰 고치지 않는다.
   - **지우지 않는다** — 코드·LAN 배포·DB 는 그대로 둔다(멈추거나 내리는 것은 따로 정한다).
3. 계획 문서의 "두 RAG 서비스 분리 그대로·서비스 경계 변경 없음"(계획 §8 D1 의 '바뀌지 않는 것')은 이 결정으로 바뀐다 — 새 RAG 는 새 서비스 경계를 갖는다.

## 바뀌는 것
- ADR-0092·0094(두 서비스 DB 문서 관리)·ADR-0093(상담 도우미)은 그 두 서비스 안의 일로 남는다. 새 RAG 의 문서 원본은 pmart-all-docs.
- 백오피스·고객 앱이 새 RAG 를 쓰려면 새 질문·답 서비스의 API 로 옮겨야 한다(그때 화면 요청서).
