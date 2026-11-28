# 요청 — RAG 문서 관리에 "고객용" 탭 (customer-assistant-service 0.6.0 · approval-service 0.8.0)

보낸 영역: platform
받는 앱: backoffice (백오피스 웹 — 운영 관리자, 승인함)
작성: pmart-backend PC 1, 2026-11-28
- 사용자 결정: "디폴트는 Git 하고 운영자랑 고객 둘다 DB에 넣을래", "문서 동일한거는 같이 관리해", 초안·승인은 운영자 문서와 같게
결정: **ADR-0094**(`docs/adr/0094-customer-rag-documents-in-db.md`)
계약: `contracts/openapi/customer-assistant-service.yaml` 0.6.0 태그 `knowledge`, approval-service 0.8.0
상태: **LAN 배포**(2026-11-28 — pc2 customer-assistant-service·ai-assistant-service, pc4 approval-service·api-gateway, b583a4873. `rag-ingest-customer` 29문서·127청크·FAQ 70). LAN 확인: 고객 문서 목록 29(INDEXED), 고객 토큰 403, FAQ 초안 201·버리기 204.
먼저 볼 것: 운영자 문서 화면 요청서 `2026-11-28-request-rag-document-admin.md` — **같은 화면·같은 API 모양**이다. 다른 점만 아래에 적는다.

## 다른 점
1. **주소 앞부분**만 다르다. 응답·요청 모양은 운영자 쪽과 같다.

   | 운영자(지금 화면) | 고객용(새 탭) |
   |---|---|
   | `/api/v1/assistant/knowledge-documents` | `/api/v1/customer-assistant/knowledge-documents` |
   | `/api/v1/assistant/knowledge-documents/detail` | `/api/v1/customer-assistant/knowledge-documents/detail` |
   | `/api/v1/assistant/knowledge-drafts` (POST·GET·PUT·DELETE) | `/api/v1/customer-assistant/knowledge-drafts` |
   | `/api/v1/assistant/knowledge-applications` | `/api/v1/customer-assistant/knowledge-applications` |

   - X-Source-System `backoffice-web`, 직원 토큰.
   - 고객 토큰은 403(이 화면과 관계없음).
2. **문서 종류 두 가지**(같은 목록):
   - `docs/rag/rag-docs/customer_app/…` — 고객 앱 AI 답변 근거 16개. 영역 `customer-app`.
   - `docs/business/customer-faq/…` — **고객센터 FAQ 13개**(FAQ 목록 화면과 AI 상담 FAQ 의 원본). 영역 `customer-faq`.
     · FAQ 형식: 문서 첫 줄 `# 분류 제목`, 질문마다 `## 질문 [확정 정책]` + 답. 지위 태그를 그대로 둔다 — 금지 표현 검사를 하지 않는다.
   - 목록에서 둘을 나눠(탭 안 구분) 보여 주면 좋다.
3. **같이 쓰는 문서 — 탭 사이 관계**:
   - customer_app 16개는 운영자 RAG 도 근거로 쓰는 **같은 문서**다. 원본은 고객용 하나다.
   - 운영자 탭에서는 지금처럼 `readOnly=true` 로 보이고, 1분 안쪽에 고객용 판을 따라간다(판 이력 op `SYNC`, updatedBy `SYNC`).
   - 운영자 탭의 "저장소에서 고침" 문구를 **"고객용 탭에서 고침"**으로 바꾸고, 그 문서의 고객용 탭으로 가는 링크를 달아 주세요.
   - 고객용 탭의 문서는 모두 `readOnly=false` 다.
4. **승인함**: 종류는 그대로 `RAG_DOCUMENT` 다.
   - 운영자 문서와 고객 문서는 상세의 `targetService` 로 구분한다: `ai-assistant-service` = 운영자, `customer-assistant-service` = 고객. 상세에 "고객용 문서" 표시를 붙여 주세요.
   - 비교(diff)에 쓸 초안은 정본 쪽에서 연다: 고객이면 `GET /api/v1/customer-assistant/knowledge-drafts/{targetRef}`.
5. **반영 뒤**:
   - 고객 앱 AI 답과 **FAQ 목록**(`/customer-assistant/faqs`)이 1~2분 안쪽에 새 판이 된다.
   - customer_app 이면 운영 도우미도 따라간다(운영자 쪽 1분 맞춤 + 재색인).

## 권한
운영자 문서와 같다.
- 초안 만들기·고치기: OPERATOR_ADMIN
- 승인: 다른 OPERATOR_ADMIN(자기 승인 금지 — SELF_APPROVAL)
- 보기: 운영자 누구나

고객센터에도 초안을 열면(설정만 바꿈) 이 문서에 적는다.

## 앞서 답한 것도 같다
- 같은 초안으로 다시 승인 요청하면 앞 요청이 심사 중일 때 409 DUPLICATE_APPROVAL(철회 미구현 — 반려 또는 새 초안).
- 승인 요청 뒤 초안을 고치면 REJECTED·DRAFT_CHANGED.
