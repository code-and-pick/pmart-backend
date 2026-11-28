# ADR-0092 운영자 RAG 문서를 백오피스에서 관리 — 문서 정본을 ai-assistant-service DB 로, 고치면 승인 뒤 바로 재색인

- 상태: **승인** (2026-11-28) — 기본값(결정 3·4·9)은 사용자 확인 대상으로 남긴다(아래 "확인 필요")
- 근거: 사용자 2026-11-28 "rag 문서를 관리하는곳이 백오피스쪽에서도 관리하고 수정할수있어야하는것같아. 지금은 아예 로컬에 저장된걸 계속 상품검색의 엘라스틱서치마냥 추가되고 그런구조가 아니잖아" → 추천 순서(이 ADR 먼저 → 상담 도우미 ADR-0093 → 구현)에 "다 해줘".
- 바꾸는 것:
  - ADR-0036·`services/ai-assistant-service/CLAUDE.md` "색인 대상은 `knowledge/manifest.yml` 의 명시 목록뿐" — 운영자 RAG 의 색인 원본이 **저장소 파일 → 서비스 DB 의 문서 판**으로 바뀐다. manifest 는 처음 한 번 가져올 목록으로만 남는다.
  - `docs/rag/HANDOVER.md` §4 "manifest 를 바꾸면 이미지 → 배포 → `rag-ingest`" — 문서 내용 변경은 이미지·배포 없이 승인으로 반영된다(모델·청크 규칙을 바꿀 때만 예전처럼 재색인).
- 바꾸지 않는 것: 고객 RAG(customer-assistant-service, ADR-0051)의 문서·색인(저장소 그대로 — 결정 2), ES-First 계획(2026-11-27, ADR-0088 유지·P3 비교 뒤 새 ADR)의 검색 구조, 스냅숏·인용·권한 재검사 규칙.

## 배경
- 지금 운영자 RAG 는 저장소 `docs/rag/rag-docs/**`(README 제외 75개)를 manifest 에 적어 이미지에 넣고, PC 1 이 `lan.sh rag-ingest pc2` 로 전체 재색인한다. 문구 하나를 고쳐도 커밋 → 이미지 → 배포 → 재색인(운영자 전체 약 60분, 바뀐 조각만 임베딩하면 몇 분)을 거친다.
- 정책 문서는 운영 부서가 고친다. 상품 정보처럼 백오피스에서 고치고 승인되면 바로 검색에 들어가야 한다(상품 → ES 색인, 동의어 → ADR-0073 승인 반영과 같은 모양).

## 결정
1. **정본**: 운영자 RAG 문서의 정본은 `ai-assistant-service` DB 의 문서 판(`knowledge_document` 지금 판 + `knowledge_document_version` 판 이력, V3)이다.
   - 처음 한 번 저장소 manifest 의 문서를 가져온다(`importKnowledge` — 이미 있는 경로는 건너뛴다). 가져온 뒤에는 저장소 사본을 고쳐도 반영되지 않는다(`docs/rag/rag-docs/README.md` 에 적는다).
   - 문서 ID = 저장소 경로 그대로(`docs/rag/rag-docs/<영역>/<파일>.md`) — 인용·평가셋(`answers.json` 의 sources)·답변 기록이 같은 이름을 쓴다. 영역(domain) = 경로의 영역 폴더.
   - 지위·우선순위는 지금 manifest 처럼 모두 CONFIRMED·4(2026-11-20 "결정된 내용만") — 문서마다 고르지 않는다.
2. **범위**: 운영자 RAG 문서 중 `customer_app/` 밖 전부. `customer_app/`(16개)은 고객 RAG 도 같은 파일을 쓰므로 1차에는 **저장소가 정본**(백오피스에서는 읽기만, 고치기 막음 `READ_ONLY_DOCUMENT`) — 두 서비스 문서가 어긋나지 않게. 고객 RAG 문서 관리는 다음 단계(따로 ADR).
3. **고치기 = 초안 → 승인 → 반영**:
   - 초안(`knowledge_draft`): 새 문서(UPSERT, 기준 판 0) · 고치기(UPSERT, 기준 판 = 지금 판) · 내리기(RETIRE). 본문 전체를 담는다(조각 수정 없음). 초안은 만든 사람만 고치고 버린다.
   - 형식 검사(초안 저장 때): 첫 줄 `# 제목`, 128KB 이하, 금지 표현("가정"·"설계안"·"확정 정책" — `RealDocumentsIngestTest` 와 같은 기준, 정해지지 않은 내용을 넣지 않는다), 경로 형식 `docs/rag/rag-docs/<영역>/<영문·숫자·->.md`, 영역은 지금 있는 폴더만.
   - 승인: approval-service 새 종류 **`RAG_DOCUMENT`**(정본 `ai-assistant-service`, 승인 하나 = 문서 하나 = 초안 하나). 화면이 변경 요청을 만든다(동의어와 같은 방식): targetRef = 초안 ID, `targetExpectedVersion` = 기준 판, 행 하나(lineKey = 문서 ID, attributes `op`·`draftId`·`contentSha256`).
   - 반영: `ApprovalDecided`(platform.approval.v1)를 받아 **기준 판 = 지금 판이고 본문 해시 = 초안 해시**일 때만 새 판을 만든다(아니면 REJECTED·`STALE_VERSION`·`DRAFT_CHANGED`). 회신 `ApplicationReceiptRecorded` 를 바로 보낸다(StreamBridge — search-service 동의어와 같은 방식, outbox·Debezium 연결을 rag-pgvector 에 새로 두지 않는다). 멱등은 반영 기록(`knowledge_application`, 승인 ID PK).
4. **역할**(기본값 — 확인 필요): 보기 = 운영자 누구나(이미 RAG 답으로 보는 내용). 초안·승인 요청 = `OPERATOR_ADMIN`(approval `requester-roles.RAG_DOCUMENT`). 승인 = `OPERATOR_ADMIN`, 자기 승인 금지(요청한 관리자와 다른 관리자).
5. **재색인**: 반영하면 "색인 필요" 표시를 남기고, 1분 주기 작업이 **DB 의 지금 판 전체로 새 스냅숏**을 만든다(색인 잠금 `AdvisoryLocks.INGEST` 그대로 — 다른 색인 중이면 다음 주기). 바뀌지 않은 조각은 임베딩 캐시를 그대로 써서 바뀐 조각만 Ollama 로 임베딩한다(문서 하나면 수십 조각, 1분 안쪽). 활성 전환 규칙(BUILDING → ACTIVE, 이전 RETIRED)은 그대로.
   - 스냅숏 출처 `source_revision` = `db:<가장 최근 반영 승인 ID>`, `source_dirty` = false. `index_source_file` 에는 문서 ID·판 본문의 sha256 을 남긴다(어느 판으로 답했는지 추적).
   - 문서 목록에 판·색인된 판·색인 상태(최신·대기·실패)를 보여 준다.
6. **이력·되돌리기**: 판은 고치거나 지우지 않는다. 되돌리기 = 옛 판 본문으로 새 초안을 만들어 다시 승인(바로 되돌리는 길 없음).
7. **저장소와의 관계**: 저장소 `docs/rag/rag-docs/**` 는 가져오기 원본이자 개발·평가용 사본이다. 평가(`RetrievalEvalIT`·`GenerationEvalIT`)는 지금처럼 파일로 돈다. 운영 문서를 저장소로 내보내는 기능(`GET /assistant/knowledge-documents/{id}` 의 본문을 그대로 저장)은 1차에 따로 만들지 않는다 — 필요하면 사람이 내려받아 커밋한다.
8. **LAN 색인 명령**: `lan.sh rag-ingest pc2` 는 "가져오기(없는 문서만) + DB 로 색인"이 된다. 모델·청크 규칙·임베딩 버전을 바꿀 때 전체 재색인은 예전처럼 이 명령으로.
9. **알림 없음**(기본값): 반영·색인 실패를 슬랙으로 보내지 않는다 — 문서 목록의 상태와 Prometheus 지표(`pmart.assistant.knowledge.reindex{result}`)만.

## 결과
- 운영 관리자가 백오피스에서 문서를 고치고 다른 관리자가 승인하면, 배포 없이 1~2분 안에 운영자 RAG 답에 들어간다.
- ai-assistant-service 가 처음으로 Kafka 를 쓴다(구독 `approvalEvents-in-0` platform.approval.v1, 보내기 platform.approval-receipt.v1). 설정 저장소에 바인딩이 없으면 구독 빈을 만들지 않는다(설정을 올리기 전 배포도 뜬다).
- 계약: ai-assistant-service 1.4.0(문서·초안 API), approval-service 0.7.0(`RAG_DOCUMENT`).
- 화면: 백오피스 RAG 문서 관리(목록·보기·초안·승인 요청·판 이력), 승인함 상세에 초안과 지금 판 비교 — 요청서 `docs/handoff/clients/backoffice/2026-11-28-request-rag-document-admin.md`.

## 확인 필요(사용자)
1. 결정 4 역할 — 고객센터 팀장 같은 다른 역할도 초안을 쓰게 할지.
2. 결정 2 — `customer_app/` 을 언제 DB 로 옮길지(고객 RAG 문서 관리와 함께).
3. 결정 9 — 색인 실패를 슬랙으로 알릴지.
