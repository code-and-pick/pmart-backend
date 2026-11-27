# customer-assistant-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `customer-assistant-service` 절과 ADR-0051 이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

고객앱 고객센터 채팅의 AI 상담(RAG). 승인된 고객 안내 문서(2026-11-20 부터 `docs/rag/rag-docs/customer_app/`, README 제외)만 근거로 답하고, 내 주문 상태는 order-service 에서 읽어 정해진 문장으로 답하며, 답하지 못하면 고객센터 문의(case-service `POST /inquiries`)로 연결한다.

## 사용자 결정(2026-11-09)
- C1 고객 안내 문서 = Claude 초안·사용자 승인(승인됨). C2 회원만(비회원·운영자 403). C3 고객별 1분 5개·하루 50개(서울 자정). C4 근거 없음 → 고객센터 문의 연결. C5 질문 원문 저장 안 함(해시만 — 설정으로도 켤 수 없다). C6 order `GET /orders`·`GET /orders/{id}` 를 고객 토큰으로. C8 운영자와 같은 Ollama 모델로 시작.

## 계약
- REST: `contracts/openapi/customer-assistant-service.yaml`(0.4.0) → `com.pmart.customerassistant.api.generated`. `api/CustomerAssistantController` 가 구현, 429 는 `api/RateLimitAdvice`. FAQ 는 `api/FaqsController`(0.2.0).
- 이벤트: 발행·구독 없음.

## 운영자용과의 관계
- `ai-assistant-service` 의 복사본에서 출발했다(2026-11-09). 색인·스냅숏·선점(lease)·인용 검사·Ollama 연결은 같은 코드다. 공용 라이브러리로 빼지 않은 이유: Spring AI 의존성을 `libraries/` 에 두게 되고(gradle-build 규칙), 운영 중인 운영자 RAG 를 함께 바꿔야 해서. 한쪽의 기술 결함을 고치면 **다른 쪽도 같은지 본다**.
- 고객용으로 바꾼 것: 접근(회원 고객·`customer-app`), 답변 문구(고객 말투, 지위·경로 표시 없음 — `ExtractiveComposer.stripTags`), 지위·우선순위 가중 0(설정), 분류에 고객 말투 실시간 질문(`QuestionClassifier.CUSTOMER_STATE`), 주문 상태 문장(`domain/OrderStatusText` — 모델에 주문 정보를 넘기지 않는다), 요청 제한(`domain/RateLimit`), 질문 목록 API 없음.

## 반드시 지킬 것
- **모델·임베딩 호출은 트랜잭션 밖에서 한다.** 선점 뒤의 쓰기는 모두 `attempt`·`state='PROCESSING'` 조건부(운영자용과 같음).
- 저장 답변을 돌려주는 경로는 모두 `CustomerQuestionService.stored(...)` 를 지난다(현재 권한 재검사).
- 같은 Idempotency-Key 재전송은 요청 제한·주문 확인을 다시 하지 않는다. 남의 주문(422)은 기록을 만들지 않는다.
- 주문 정보는 응답할 때 order-service 에서 새로 읽는다(저장은 `order_id` 만). 주소·결제 정보는 옮기지 않는다(`OrderClient.OrderView`).
- 질문·문서 본문·토큰·주문 내용을 로그에 남기지 않는다.
- 색인 대상은 `src/main/resources/knowledge/manifest.yml` 의 명시 목록(고객 안내 문서)뿐. 업무 문서·상담 대본·ADR 은 내부 문서라 넣지 않는다. 고객 안내 문서를 고치면 사용자 승인 뒤 재색인.
  - **2026-11-20 사용자**: AI 답변 근거는 `docs/rag/rag-docs/customer_app/*`(README 제외)만. 옛 `docs/business/customer-faq/*`(domain `customer-faq`)는 manifest 에 남지만 **FAQ 목록 화면 전용** — `KnowledgeIngestor.buildChunks` 가 청크로 만들지 않고 `FaqExtractor` 만 읽는다(사용자 "FAQ 는 옛 FAQ 문서에서" — customer_app 은 약관 형식이라 `##` 절이 "제N조"). 시험 `CustomerScopeIngestTest`.

## 데이터
- `V1__init.sql`: `index_snapshot`·`index_source_file`·`doc_chunk`·`embedding_cache`·`customer_answer`(질문 원문 컬럼 없음, `order_id`, 요청 제한 색인).
- `V2__faq_entry.sql`(2026-11-18): `faq_entry` — 스냅숏마다 FAQ 항목(스냅숏 지우면 같이).
- LAN: pc5 `rag-pgvector`(2026-11-10 pc2 → pc5) 의 DB·계정 `customer_assistant_service`(ADR-0051). 로컬: `deploy/local/ai-assistant/docker-compose.yml`(15433, 새 볼륨이면 DB 자동 생성).

## 실행
```bash
./gradlew :services:customer-assistant-service:check
docker compose -f deploy/local/ai-assistant/docker-compose.yml up -d
./gradlew :platform-services:config-server:bootRun --args='--spring.profiles.active=local'
./gradlew :services:customer-assistant-service:ingestKnowledge -Prepo=$(pwd)
./gradlew :services:customer-assistant-service:bootRun --args='--spring.profiles.active=local'
```
로컬 포트 8509. LAN 색인 `deploy/lan/lan.sh rag-ingest-customer pc5`.

## 상태
- **상품 질문(2026-11-20, ADR-0083, 계약 0.3.0)**: 운영자용과 같은 `domain/ProductQuestion` 규칙·`ProductSearchClient`(토큰 없이 — 최근 검색어 기록에 안 남음). **센터 없이 전체 기준**(질문 요청에 주소·센터가 없음, 사용자 "취급 여부는 답하지 않음") — `ProductAnswerText.customer` 정해진 문장, `PRODUCT_INFO`·`PRODUCT_TEMPLATE`, handoff 없음. 0건·실패면 안내 문서로. `product-search.base-url` 이 없으면 꺼짐. 시험 `ProductQuestionTest`·`ProductQuestionIT`.
- 1차 구현 2026-11-09. 남은 결정: 답변 보존 기간(`answers.retention`, 운영자용과 같이 미설정 — 지우지 않음).
- 평가(2026-11-10, `docs/rag/verification/2026-11-10/customer-eval/`): 37문항 37/37. 확인: `python -I deploy/lan/checks/customer_rag_eval.py`(고객 토큰 필요).
- **FAQ 목록·검색(2026-11-18, 계약 0.2.0, V2 — 사용자 "A해줘", 고객앱 빠진 기능)**: `GET /customer-assistant/faqs?q=&category=`·`/faqs/{faqId}`(`FaqService`·`FaqStore`·`FaqsController`). 색인할 때 manifest 의 domain `customer-faq` 문서를 `infrastructure/ingest/FaqExtractor` 가 `##` 절마다 항목으로(질문 = 절 제목, 답 = 절 본문 마크다운 — 지위 태그 뺌, 다른 안내 문서 링크는 ‘제목’), 분류 = 파일 이름(앞 번호 뺌). 청크와 **같은 바이트·같은 스냅숏**에 넣어(`SnapshotStore.addFaqEntries`) AI 답변 근거와 어긋나지 않는다. 읽기는 FAQ 가 든 ACTIVE 스냅숏 하나를 먼저 정해 그 ID 로만. 검색 = `search_text`(소문자 질문 + 굵게·코드 뺀 답) `strpos` 부분 문자열 — 모델·임베딩 호출 없음, 요청 제한에 안 셈, 검색어 저장·로그 안 함. 회원 고객만(C2, 다른 고객 API 와 같이). FAQ 가 든 색인이 없으면 422 CONFIG_REQUIRED — **이 기능 전 색인(LAN)은 재색인해야 찬다**(`lan.sh rag-ingest-customer pc2`, 새 이미지 배포 뒤). 게이트웨이 경로는 기존 `/api/v1/customer-assistant/**` 그대로.
- 고객용 답변 검사 `domain/CustomerAnswerChecks`(숫자 온전 일치·표 모든 행·질문 대상·물음표)와 `TableText`(표 → 줄)는 운영자용에 없다. 4B 모델은 프롬프트 규칙을 잘 따르지 않으므로 막아야 할 것은 이 검사에 넣는다.
- **FAQ 답 의견(2026-11-23, 계약 0.4.0·V3 — 고객앱 BR-91, 사용자 "2번으로"(PC 1 이 넣음, PC 4 확인)·Q1 "추천대로" 글 없음)**: `POST /customer-assistant/faqs/{faqId}/feedback` {helpful}(회원, 지금 색인에 있는 faqId — `FaqService.sendFeedback`) → 표 `faq_feedback`(account_id·faq_id PK, 마지막 값 upsert라 재전송 안전 — request_record 없음). 목록·단건 `myFeedback`. 운영 집계 `GET /customer-assistant/faq-feedback`(CS_AGENT·OPERATOR_ADMIN, `FaqFeedbackStore.stats` + 지금 색인의 질문·분류). 게이트웨이 경로는 기존 `/api/v1/customer-assistant/**`. 시험 `FaqIT.feedbackKeepsLastValuePerCustomerAndCountsForOperators`.
- **집계 분류 이름(2026-11-23 CA-3, 계약 0.4.1 — PC 2 요청)**: `GET /customer-assistant/faq-feedback` 줄에 `categoryTitle`(지금 색인의 FaqRow.categoryTitle, 없으면 null).
- **종료 지점 지표(2026-11-27, ES-First 계획 P1 ①)**: `AnswerGenerator.Generated` 의 `exit`·`attempts`·`topSimilarity` → `application/AnswerMetrics`(Prometheus — 종료·시도 결과·검사 규칙·1위 유사도 분포, 질문 원문 없음). 두 RAG 서비스가 같은 규칙 — 한쪽을 고치면 다른 쪽도. 시험 `AnswerExitTest`. 계획 `docs/rag/planning/2026-11-27-es-first-hybrid-plan.md` §8·§9.
