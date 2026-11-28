# ADR-0096 chunking-service — 추출 문서 청킹 → Elasticsearch RAG 색인

- 상태: **승인** (2026-11-28)
- 근거: 사용자 2026-11-28 "청킹 서비스를 담당할 서비스를 신규로 작성하고 이 청킹 서비스 결과물을 es에 전달할 수 있는 시스템을 확인 구현해봐".
  - ES-First 하이브리드 RAG 계획(`docs/rag/planning/2026-11-27-es-first-hybrid-plan.md`) §1-1(고객·운영자 색인 나눔)·§1-2 단계 2(한 번 청킹 → 1 청크 = 1 ES 문서)·§1-3(폐기는 상태로 내림).
  - 나누기는 Spring AI 리더(사용자 2026-11-28 — 직접 짠 청커 폐기, 기성 도구).
  - 앞 단계: ADR-0095 integration-service(문서 저장소 웹훅 → 추출 → 이 서비스로 전달).

## 결정
1. **새 서비스 `chunking-service`**(services/, 포트 8515, LAN PC 2 — integration-service 옆, PC 2 안에서만 불림). DB·Kafka 없음.
2. **받기**: `POST /api/v1/internal/documents`(본문 = integration-service `ExtractedDocument`, 계약 `contracts/openapi/chunking-service.yaml`). integration-service 설정 `chunking-url` 이 이 주소.
3. **청킹 = Spring AI 부품만**: `MarkdownDocumentReader`(제목마다 하나, 코드 블록·인용 포함, 가로줄 나눔) → 한 절이 `chunk-tokens`(800)를 넘으면 `TokenTextSplitter` 로 더 나눔(조각도 절의 제목·경로를 그대로).
   - 제목 경로 = 문서 제목(첫 `# 제목` — 받은 `title`) > 절 제목.
   - `chunkId` = (문서 ID, 문서 해시, 순번)의 SHA-256 앞 32자 — 같은 판은 같은 ID. 다음 단계(ES 청크 → 임베딩 → pgvector, 계획 §1-2 단계 3·4)가 같은 ID 를 쓴다.
4. **ES 색인**: 검색 전용 ES(`es-search`, PC 3, ADR-0038)에 접근 범위마다 별칭 둘 — `rag-operator`·`rag-customer` → 실제 색인 `<별칭>-v1`(정의 `elasticsearch/rag-chunks-v1.json`, nori 분석기, `dynamic: strict`). 서비스가 뜰 때·문서가 올 때 없으면 만든다. ES 는 REST 로(클라이언트 라이브러리 없음, ADR-0031). 벡터는 넣지 않는다(계획 §8 D1 — 벡터는 pgvector).
   - 경로 → 범위: `operator/**` → 운영자, `customer/customer_app/**` → 고객+운영자(같은 문서 원본 하나 — ADR-0094 결정 3의 뜻), `customer/faq/**` → 고객, 그 밖 → 색인 안 함.
5. **문서 단위 반영(멱등)**:
   - 추가·수정: 그 색인의 ACTIVE 청크 문서 해시가 같으면 UNCHANGED. 다르면 새 판 청크를 bulk 로 넣고(`refresh=wait_for`) 같은 문서의 다른 판 청크를 지운다(빈 틈 없이).
   - 삭제: 지우지 않고 `status=RETIRED`·`retiredAt`(계획 §1-3). 검색은 `status=ACTIVE` 만 본다.
   - 실패는 5xx — integration-service 가 다시 시도한다.

## 아직 아닌 것
- 스냅숏 단위 활성화(DB ACTIVE 와 별칭 동시 전환, 계획 §1-2 단계 5)와 임베딩·pgvector 적재 — 다음 단계. 지금은 문서 단위로 바로 반영한다.
- 두 RAG 서비스의 검색을 이 색인으로 바꾸는 것(계획 §2-1).
- 놓친 웹훅을 위한 전체 다시 맞추기.

## 확인한 것 (2026-11-28)
- 실제 문서(`docs/rag/rag-docs` 운영자 75개): 청크 650개, 가장 긴 청크 935자. 처음에는 `TokenTextSplitter` 로 나눈 조각(backoffice/05 제15조)에서 제목 경로가 빠졌다 — 절마다 나누고 절의 메타를 쓰도록 고침.
- **리더가 표·목록 줄바꿈을 한 줄로 붙인다**(Spring AI `MarkdownDocumentReader` 의 동작 — 예: 제9조 표가 `| 접수 건 종류 | 담당 팀 | … |---|…` 한 줄). 표가 든 청크 35개. BM25 낱말 검색에는 지장이 없지만, 표 행 단위로 근거를 보이는 쪽(인용 검사의 표 행 판단)은 원문을 써야 한다.
- 시험: 단위 4(제목별 청크·경로·순번, chunkId 안정성, 긴 절 나눔, 범위) + 통합 2(ES 8.18 + nori Testcontainers: 색인·별칭 생성, 운영자·고객 나눔, customer_app 두 색인, FAQ 고객만, UNCHANGED, 새 판이 옛 판 지움, 삭제 RETIRED, nori 검색).
