# ADR-0095 integration-service — 문서 저장소(pmart-all-docs) 웹훅 흐름, Spring Integration

- 상태: **승인** (2026-11-28)
- 근거: 사용자 2026-11-28
  - ES-First 하이브리드 RAG 확정(`docs/rag/planning/2026-11-27-es-first-hybrid-plan.md` §8 D1) — "그걸로 결정났고 … 더이상 토달지마."
  - "너 자바코드는 폐기시키고 Spring Intergration으로 대체하자." — 직접 짠 색인 코드 대신 기성 도구.
  - "문서를 로컬스토리지를 쓰지말고 지정한 문서가 푸시되거나 딜리트되면 웹훅을 사용해서 그런시스템으로하자."
  - 문서 저장소: GitHub `code-and-pick/pmart-all-docs`(private, 사용자가 만들고 이름 바꿈 — 처음 이름 pmart-rag-docs).
  - "spring intergration 디펜던시가 적용된 새 서비스 이름은 intergrationService라고 하자. 이 서비스는 pmart-all-docs 리포지토리와 webhook으로 연결되어 있고, … 특정 파일이 이 리포지토리로 푸시되면 이 특정 파일을 가지고 와서 이 파일의 내용을 추출해서 추출한 내용을 청킹할 수있는 어떤 서비스에게 추출 내용을 전달하는 것이 첫번째 잡이야."
  - ai-assistant-service·customer-assistant-service 에는 Spring Integration 을 넣지 않는다("ai-assistant-service, customer-assistant-service spring intergration은 적용하지마").
  - 이름은 저장소 규칙(kebab-case `-service`)에 맞춰 `integration-service`.

## 결정
1. **새 서비스 `integration-service`**(services/, 포트 8514, LAN PC 2 — RAG 묶음 옆). ES-First 하이브리드 RAG 의 **적재 입구**다. Spring Integration 은 이 서비스에만 둔다.
2. **원본 = GitHub `pmart-all-docs`, 로컬 사본 없음.** 웹훅(push·delete)으로 바뀐 것을 알고, 파일 내용은 GitHub Contents API 로 **그 push 의 마지막 커밋 판**에서 읽는다(읽기 전용 fine-grained 토큰, Contents 읽기만).
3. **첫 번째 일(이번 구현)**: push → 바뀐 파일(커밋들에 걸친 마지막 상태: 추가·수정·삭제) → `.md`(폴더 README 제외)만 내용 추출(BOM 제거·줄바꿈 LF·첫 줄 제목·SHA-256, 마크다운 구조는 그대로) → **청킹 서비스로 전달**(`POST pmart.integration.docs.chunking-url`, 본문 `ExtractedDocument` — 계약 `contracts/openapi/integration-service.yaml`). 삭제는 본문 없이 `REMOVED` 로 전달.
   - 청킹 서비스 = chunking-service(ADR-0096, 같은 날) — LAN `chunking-url: http://chunking-service:8515/api/v1/internal/documents`. 주소가 없으면 추출만 하고 기록(경로·커밋·해시·글자 수, 본문은 로그 금지)을 남긴다. 보낼 때 `Idempotency-Key`(커밋·경로·상태에서 정함)·`X-Source-System`.
   - 다음 단계(계획 §1-2): 청킹 → ES 선 적재 → ES 청크로 임베딩 → pgvector → 함께 활성화. 받는 쪽은 (path, contentSha256) 로 멱등이어야 한다.
4. **흐름(Spring Integration DSL)**: HTTP inbound gateway `POST /api/v1/internal/docs-webhook` → 서명·이벤트·저장소·브랜치 판정(GitHub 에 바로 202) → wireTap 으로 파일 목록 split → 단일 스레드 ExecutorChannel(push 순서대로) → filter(추출 대상) → 추출(GitHub API, 재시도) → 전달(재시도, 지수 대기) → 끝내 실패는 errorChannel 기록.
5. **웹훅 전달**: GitHub → smee.io 채널(`.secrets/rag-docs-smee-url`) → `docs-relay`(같은 PC 2, smee-client — config-relay 와 같은 방식, 채널은 따로) → integration-service. GitHub 웹훅 id 717905801, 이벤트 push·delete, JSON, Secret = `.secrets/rag-docs-webhook-secret`.
   - 서비스가 `X-Hub-Signature-256`(HMAC-SHA256)을 확인한다(없거나 틀리면 401, 비밀이 설정되지 않았으면 모두 거절). 공통 헤더(Idempotency-Key·X-Source-System)는 GitHub 가 보내지 않아 이 경로만 뺀다(`pmart.web.excluded-paths`). 같은 전달 ID 는 한 번만 처리.
   - `delete` 이벤트는 브랜치·태그 삭제다 — 문서 변경으로 보지 않는다(파일 삭제는 push 의 removed). 따라가는 브랜치가 지워지면 경고만.
   - 경로는 `/internal` — 게이트웨이가 막는다(사용자 토큰을 받지 않는 서비스 사이 경로, ADR-0026).
6. **비밀**: 웹훅 비밀·GitHub 토큰은 설정 저장소 `integration-service-lan.yml` 에 `{cipher}`. 원문은 PC 1 `.secrets`(+ PC 6 키 묶음)에만.
7. **DB·Kafka 없음**. 놓친 웹훅(중계·서비스가 꺼져 있던 동안)은 이번에 다루지 않는다 — 전체 다시 맞추기(GitHub 트리와 비교)는 청킹 서비스와 함께 정한다.

## 바뀌는 것 · 그대로인 것
- 지금 두 RAG 서비스의 색인 경로(`lan.sh rag-ingest`·저장소 사본·`MarkdownChunker`·DB 문서 판, ADR-0092·0094)는 **이번에 바꾸지 않는다**. 청킹 서비스와 ES 적재가 생기면 그 단계의 ADR 에서 대체 범위를 정한다.
- 두 RAG 서비스에 Spring Integration 을 넣지 않는다.

## 결과
- 의존성: `spring-boot-starter-integration`·`spring-integration-http`(Boot BOM, 7.0.x), 시험 `spring-integration-test`·WireMock.
- 시험: 단위(서명·push 파싱·추출·설정) + 통합 `DocsWebhookFlowIT`(서명한 push → GitHub 흉내에서 그 커밋 판 읽기 → 청킹 흉내가 추출 내용 받음, README·다른 확장자 건너뜀, 삭제 본문 없음, 서명 틀림 401, ping·delete·다른 저장소).
