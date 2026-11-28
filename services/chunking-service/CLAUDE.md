# chunking-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `chunking-service` 절이다. 결정은 ADR-0096(앞 단계 ADR-0095).

## 하는 일
integration-service 가 보낸 문서 하나(추출 내용) → Spring AI 로 청킹 → Elasticsearch RAG 색인(`rag-operator`·`rag-customer`, es-search)에 문서 단위로 반영. ES-First 하이브리드 RAG 의 "청킹 → ES 선 적재".

## 규칙
- 나누기는 Spring AI 부품(`MarkdownDocumentReader`·`TokenTextSplitter`)만 — 정규식 등으로 직접 나누지 않는다(사용자 2026-11-28).
- 색인 정의는 `src/main/resources/elasticsearch/rag-chunks-v1.json`(`dynamic: strict`). 필드를 바꾸면 새 버전 파일(`-v2`)과 별칭 전환으로.
- ES 는 REST(클라이언트 라이브러리 없음, ADR-0031). 벡터는 넣지 않는다(pgvector — 다음 단계).
- 문서 본문은 로그 금지(경로·커밋·청크 수만). DB·Kafka 없음.
- 계약 `contracts/openapi/chunking-service.yaml`(컨트롤러 직접, `pmartOpenApi.enabled=false`).

## 패키지
`com.pmart.chunking.{api, application, domain, infrastructure}` — 받기 `api/DocumentController`, 반영 `application/DocumentIndexing`, 청킹 `application/MarkdownChunker`, 범위 `domain/AccessScope`, ES `infrastructure/RagIndex`.

## 실행
```bash
./gradlew :services:chunking-service:check     # 단위 + 통합(ES 8.18 + nori Testcontainers) + 계약 검증
```
로컬 포트 8515(ES `localhost:9200`). LAN PC 2 → es-search(PC 3).
