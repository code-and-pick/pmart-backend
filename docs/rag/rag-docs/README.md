# 운영자 RAG 문서 (rag-docs)

운영 도우미(운영자 RAG, `ai-assistant-service`)가 답할 때 근거로 쓰는 직원용 문서입니다. 영역마다 폴더가 있고, 작성 규칙·근거는 각 폴더의 README 에 있습니다. README 는 색인하지 않습니다.

## 정본 (2026-11-28, ADR-0092)
- LAN 에서 `pmart.assistant.knowledge.source=db` 로 켠 뒤에는 **정본이 ai-assistant-service DB** 입니다. 첫 `lan.sh rag-ingest pc2` 가 이 폴더의 문서 중 DB 에 없는 것만 가져오고, 그 뒤로는 **백오피스 문서 관리 화면에서 초안 → 다른 운영 관리자 승인 → 1분 안쪽 재색인**으로만 바뀝니다.
- 가져온 뒤 이 폴더의 파일을 고쳐도 LAN 답에는 반영되지 않습니다. 이 폴더는 처음 가져올 원본이자 개발·평가(`RetrievalEvalIT`·`GenerationEvalIT`)용 사본입니다. 운영 문서를 저장소에 남기려면 백오피스에서 본문을 받아 커밋합니다.
- **`customer_app/`(2026-11-28 ADR-0094)** — 운영자·고객 RAG 가 같이 쓰는 문서라 원본을 하나로 같이 관리합니다. 원본은 **고객 RAG DB**(백오피스 문서 관리 "고객용" 탭에서 고침)이고, 운영자 RAG 는 1분마다 그 판을 받아 맞춥니다(운영자 쪽에서는 읽기만). 고객 FAQ(`docs/business/customer-faq/`)도 고객 RAG DB 가 원본입니다. 이 폴더·FAQ 폴더는 처음 가져올 원본(기본)으로 남습니다.
- 새 문서를 이 폴더에 더하면 `services/ai-assistant-service/src/main/resources/knowledge/manifest.yml` 에도 한 줄 넣습니다(가져오기 목록, `RealDocumentsIngestTest`).
