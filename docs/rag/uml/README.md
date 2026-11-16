# RAG UML 다이어그램 (2026-11-16, PC 4)

지금 LAN 에 돌고 있는 RAG 를 표준 UML 로 나눠 그렸다. 값은 코드·설정 저장소(`ai-assistant-service{,-lan}.yml`)·`deploy/lan/render.sh`·PC 2 Ollama `/api/ps`·`/api/show`(2026-11-16)에서 확인한 것이다. 값이 바뀌면 `make_uml.py` 를 고쳐 다시 만든다.

| 파일 | 다이어그램 | 보여 주는 것 |
|---|---|---|
| `pmart-rag-uml-1-deployment.svg` | 배치 | PC 1·2·4 노드, Docker·컨테이너·llama-server 프로세스, 이미지·GGUF·DB 아티팩트, GPU·CPU 장치, HTTP·JDBC·SSH 통신 경로 |
| `pmart-rag-uml-2-component.svg` | 컴포넌트 | Ollama 제공 인터페이스(EmbedAPI·ChatAPI·AdminAPI) ↔ RAG 서비스 요구 인터페이스, Ollama 내부(REST API·Scheduler·Model Manager·llama-server ×2·Model Store), pgvector SQL |
| `pmart-rag-uml-3-sequence.svg` | 시퀀스 | 운영자 질문 하나 — 임베딩 → 검색 → 생성(최대 2회) → 생성·발췌·근거 없음, Ollama 동시 1건 대기 |
| `pmart-rag-uml-4-state.svg` | 상태 머신 | Ollama 안의 모델 하나 — 미적재 → 적재 중(~30초) → 적재됨(유휴·처리 중) → 내려감(24h 만료·최대 2개 밀어냄) |
| `pmart-rag-uml-5-activity.svg` | 활동 | 질문 처리 단계(서비스·Ollama·pgvector 칸) — 같은 키·의도 분류·임계·인용 검사·재생성·발췌 분기 |

다시 만들기: `python -I docs/rag/uml/make_uml.py docs/rag/uml`

- 흐름·모델 개요 한 장짜리는 `../rag-architecture-2026-11-16.svg`.
- Ollama 는 모델이 아니라 실행기다 — 서버(Go)가 모델마다 llama.cpp `llama-server` 를 띄운다(같은 이미지 0.40.10 로컬 실행 로그 "using llama-server for model", 2026-11-16 확인 — 임베딩 모델로만 확인).
