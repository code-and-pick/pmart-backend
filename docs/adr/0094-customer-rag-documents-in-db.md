# ADR-0094 고객 RAG 문서도 DB 에서 관리 — 기본 Git, 같은 문서는 원본 하나로 같이 관리

- 상태: **승인** (2026-11-28)
- 근거: 사용자 2026-11-28
  - "디폴트는 Git 하고 운영자랑 고객 둘다 DB에 넣을래."
  - "문서 동일한거는 같이 관리해."
  - 고객 문서 초안·승인 역할 (가) — 운영자 문서와 같게 "응"
  - 구현 "1번"
  - 기록: `docs/rag/planning/2026-11-28-es-first-plan-supplement.md` §S1·§S8(사용자 보관 파일).
- 대체: **ADR-0092 결정 2**("`customer_app/` 은 저장소가 정본, 고객 RAG 문서 관리는 다음 단계").
  - ADR-0092 의 나머지(운영자 문서 정본 DB, 초안 → 승인 → 반영 → 1분 재색인, 판 이력·되돌리기)는 그대로이고, 고객 RAG 에도 같게 적용한다.

## 결정
1. **기본은 Git, 운영 중 원본은 각 RAG 서비스 DB.**
   - 처음 한 번 Git 에서 없는 문서만 가져오고(`importKnowledge` = `lan.sh rag-ingest-customer`), 그 뒤로는 초안 → 승인 → 반영 → 재색인으로만 바뀐다.
   - Git 사본은 처음 원본이자 되돌릴 기준·평가용으로 남는다.
2. **고객 RAG(customer-assistant-service) 문서 정본 = 그 서비스 DB** — ai-assistant-service V3 와 같은 표를 둔다(V4: 지금 판·판 이력·초안·반영 기록·재색인 요청).
   - 대상(색인 목록 manifest 그대로):
     - `docs/rag/rag-docs/customer_app/*`(16개, AI 답변 근거)
     - `docs/business/customer-faq/*`(13개, FAQ 목록 화면·D2 승인 FAQ의 원본)
   - FAQ 문서는 지위 태그(`[확정 정책]`)를 쓰는 형식이라 금지 표현 검사에서 뺀다(FaqExtractor 가 태그를 뗀다).
3. **같은 문서는 원본 하나로 같이 관리한다.**
   - `customer_app/` 16개는 운영자 RAG 도 근거로 쓴다(2026-11-20 "운영자용은 rag-docs 전부"). 원본은 **고객 RAG DB** 하나다. 한 번 고치면 두 RAG 에 같이 반영된다.
   - 운영자 RAG 는 고객 RAG 의 내부 API `GET /internal/knowledge-documents?domain=customer_app`(토큰 없음, LAN 서비스 사이 — 게이트웨이에 없음)로 지금 판을 받는다. 자기 DB 의 읽기 전용 사본(`read_only`)을 그 판으로 맞추고 재색인한다.
   - 1분 주기 재색인 작업이 고객 RAG 판 지문(문서 ID·본문 해시)을 비교해, 다르면 맞추고 재색인한다.
   - 고객 RAG 가 응답하지 않으면 지난 사본으로 색인한다(답은 계속 된다).
   - 운영자 화면에서 이 문서들은 읽기 전용("고객 RAG 문서에서 고침")이다.
4. **승인**: approval-service 종류 `RAG_DOCUMENT` 를 그대로 쓰고, 정본 서비스(`targetService`)에 `customer-assistant-service` 를 더한다.
   - 문서 ID 형식에 `docs/business/customer-faq/<이름>.md` 를 더한다.
   - 초안·승인 역할은 운영자 문서와 같다(OPERATOR_ADMIN 초안, 다른 OPERATOR_ADMIN 승인 — 자기 승인 금지). 고객센터에도 초안을 열려면 설정만 바꾼다.
5. **API**: 고객 RAG 에 ai-assistant 와 같은 문서 관리 API(`/customer-assistant/knowledge-documents`·`/knowledge-drafts`·`/knowledge-applications`)를 둔다.
   - **운영자(OPERATOR) 토큰만** 쓴다 — 고객 토큰은 403. 게이트웨이 경로는 기존 `/api/v1/customer-assistant/**` 그대로, 허용 목록에 OPERATOR 줄을 더한다.
   - 화면은 백오피스 문서 관리의 "고객용" 탭(같은 화면, 주소만 다름).
6. **반영 뒤 FAQ 목록**: FAQ 문서를 고치면 다음 재색인 스냅숏에 `faq_entry` 가 새로 들어간다. FAQ 목록·D2 FAQ 답이 같은 판을 본다.

## 결과
- 고객 안내 문구(취소·반품·주류 등)를 백오피스에서 고치고 다른 관리자가 승인하면 1~2분 안에 고객 앱 AI 답·FAQ 목록에, `customer_app` 이면 운영 도우미에도 들어간다.
- 계약: customer-assistant-service 다음 판(문서 관리 + 내부 판 조회), ai-assistant-service 는 설정 하나(`pmart.assistant.knowledge.shared-source.base-url`)만, approval-service 0.8.0(`RAG_DOCUMENT` 정본 둘).
- customer-assistant-service 도 Kafka 를 쓴다(ApprovalDecided 구독·회신 — ai-assistant 와 같은 방식, 바인딩은 -lan 에만).
- 화면: 요청서 `docs/handoff/clients/backoffice/2026-11-28-request-customer-rag-document-tab.md`.
