# 요청 — 운영자 RAG 문서 관리 화면 (ai-assistant-service 1.4.0 · approval-service 0.7.0)

보낸 영역: platform
받는 앱: backoffice (백오피스 웹 — 운영 관리자, 승인함)
작성: pmart-backend PC 1, 2026-11-28 — 사용자 "rag 문서를 관리하는곳이 백오피스쪽에서도 관리하고 수정할수있어야하는것같아" → "다 해줘"
결정: **ADR-0092**(`docs/adr/0092-rag-documents-managed-in-backoffice.md`)
계약: `contracts/openapi/ai-assistant-service.yaml` 1.4.0 태그 `knowledge`, `contracts/openapi/approval-service.yaml` 0.7.0 종류 `RAG_DOCUMENT`
상태: **백엔드 구현·시험 전**(PC 1 로컬 — push·배포 안 함). 배포되면 이 파일에 적는다.

## 무엇이 바뀌나
- 운영 도우미(운영자 RAG)가 답할 때 쓰는 문서를 **백오피스에서 보고, 고치고, 승인받아 바로 반영**한다. 지금은 저장소 파일을 고쳐 배포해야 했다.
- 고치면 → 다른 운영 관리자가 승인 → 1~2분 안에 운영 도우미 답에 들어간다(배포 없음).

## 화면
1. **문서 목록**(메뉴 예: 운영 도우미 › 문서 관리) — `GET /api/v1/assistant/knowledge-documents`
   - 영역(domain)별로 묶어 제목·판·마지막 반영(누가·언제)·**색인 상태**(`indexState`: INDEXED 반영됨 / PENDING 반영 대기 / FAILED 반영 실패(1분 뒤 다시) / NOT_INDEXED 아직 색인 안 함).
   - `readOnly=true`(고객 앱 안내 `customer_app/`)는 "저장소에서 고침" 표시, 고치기 버튼 없음.
   - "내린 문서 보기" 켜면 `includeRetired=true`.
   - 위에 마지막 재색인(`reindex.finishedAt`·`lastResult`). `source=files` 면 "문서 관리가 아직 켜지지 않았습니다"(설정 전) 안내.
2. **문서 보기** — `GET /api/v1/assistant/knowledge-documents/detail?documentId=<ID>[&version=<판>]`
   - 마크다운 본문 미리 보기 + 판 이력(`versions` — 판·종류 IMPORT/UPSERT/RETIRE·누가·언제·승인 ID). 옛 판을 골라 볼 수 있다.
   - "이 판으로 되돌리기" = 옛 판 본문으로 새 초안 만들기(바로 되돌리지 않는다 — 다시 승인).
3. **고치기(초안)** — 운영 관리자(OPERATOR_ADMIN)만 버튼이 보인다. 그 밖은 403.
   - 새로: `POST /api/v1/assistant/knowledge-drafts` `{documentId, op: "UPSERT", baseVersion: <본 판>, content: <본문 전체>}` (Idempotency-Key·X-Source-System). 새 문서는 `baseVersion: 0`, ID 는 `docs/rag/rag-docs/<영역>/<영문·숫자·_- 이름>.md`.
   - 내리기: `{documentId, op: "RETIRE", baseVersion}`(본문 없음).
   - 저장: `PUT /api/v1/assistant/knowledge-drafts/{draftId}` `{content}`. 버리기: `DELETE …/{draftId}`(204).
   - 내 초안 목록: `GET /api/v1/assistant/knowledge-drafts`.
   - 형식 오류 400(`details` 의 field·message 를 그대로 보여 준다): 첫 줄 `# 제목`, 128KB 이하, **"가정"·"설계안"·"확정 정책" 표현 금지**(정해진 내용만), `customer_app/` 은 막힘.
   - 409 `STALE_VERSION` = 그사이 다른 판이 반영됨 → "문서가 바뀌었습니다. 다시 열어 주세요".
   - 편집기: 마크다운 원문 편집 + 미리 보기면 충분하다. 절은 `## 제N조 (제목)`, 표·목록 그대로(작성 규칙 `docs/rag/rag-docs/backoffice/README.md`).
4. **승인 요청** — 초안 응답의 `approvalRequest` 를 **그대로** approval-service `POST /api/v1/change-requests` 에 보내고 이어서 `POST /api/v1/change-requests/{id}/submissions`(동의어 승인과 같은 흐름).
   - 승인 요청 뒤 초안을 고치면 승인돼도 반영되지 않는다(`DRAFT_CHANGED`) → 고친 뒤에는 새 `approvalRequest` 로 다시 요청. 화면에서 "승인 요청 중 — 고치면 다시 요청해야 합니다" 경고.
5. **승인함 상세(종류 RAG_DOCUMENT)** — 승인자는 요청한 관리자가 아닌 다른 OPERATOR_ADMIN(자기 승인 금지).
   - targetRef(초안 ID)로 `GET /api/v1/assistant/knowledge-drafts/{draftId}` → `content`(초안)와 `current.content`(지금 판)를 **나란히 비교(diff)** 해 보여 준다. 새 문서는 `current` 가 null, 내리기는 `op=RETIRE`.
6. **반영 결과** — `GET /api/v1/assistant/knowledge-applications`(최근 순): APPLIED(판 n) 또는 REJECTED + 사유
   - `STALE_VERSION` 문서가 그사이 바뀜 / `DRAFT_CHANGED` 승인 요청 뒤 초안이 바뀜 / `DRAFT_MISMATCH`·`DRAFT_NOT_FOUND`·`DRAFT_CLOSED` 요청 본문이 초안과 다름 / `READ_ONLY_DOCUMENT`.
   - 초안 상세의 `state`(OPEN·APPLIED·REJECTED·DISCARDED)·`resultReason` 으로도 보인다.

## 권한 정리
| 할 일 | 역할 |
|---|---|
| 목록·보기·초안 보기·반영 결과 | 운영자 누구나 |
| 초안 만들기·고치기 | OPERATOR_ADMIN (설정 `pmart.assistant.knowledge.editor-roles`) |
| 승인 요청 | OPERATOR_ADMIN (approval `requester-roles.RAG_DOCUMENT`) |
| 승인 | 다른 OPERATOR_ADMIN (approval `approver-roles.RAG_DOCUMENT`, 자기 승인 금지) |

## 확인 필요(사용자, ADR-0092)
- 고객센터 팀장 등 다른 역할도 초안을 쓰게 할지 — 정해지면 설정만 바꾼다(화면은 403 이면 버튼을 숨기면 된다).
