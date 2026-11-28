# integration-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `integration-service` 절이다. 결정은 ADR-0095.

## 하는 일
문서 저장소 GitHub `code-and-pick/pmart-all-docs`(private)와 웹훅으로 연결된 흐름 — ES-First 하이브리드 RAG 의 적재 입구.
push·delete 웹훅 → 바뀐 파일 확인 → `.md` 파일을 GitHub API 로 그 커밋 판에서 가져와 내용 추출 → 청킹 서비스로 전달(`ExtractedDocument`). 로컬에 파일을 두지 않는다.

## 규칙
- Spring Integration 은 **이 서비스에만**(두 RAG 서비스에는 넣지 않는다 — 사용자 2026-11-28). 흐름은 `application/DocsWebhookFlow`(DSL).
- 계약: `contracts/openapi/integration-service.yaml`. 입구는 Spring Integration HTTP 게이트웨이라 생성 인터페이스를 쓰지 않는다(`pmartOpenApi.enabled=false`, 계약 검증은 함).
- 웹훅은 서명(`X-Hub-Signature-256`)으로만 받는다. 비밀·토큰은 설정 저장소 `{cipher}` — 로그·응답에 싣지 않는다. 문서 본문도 로그 금지(경로·커밋·해시·글자 수만).
- DB·Kafka 없음.

## 패키지
`com.pmart.integration.{application, domain, infrastructure}` — 판정 `application/WebhookReceiver`, 추출 `application/ContentExtractor`·`domain/ContentExtraction`, push 파싱 `domain/PushEvents`, 서명 `domain/WebhookSignature`, GitHub 읽기 `infrastructure/GitHubContents`, 전달 `infrastructure/ChunkingSender`.

## 실행
```bash
./gradlew :services:integration-service:check     # 단위 + 통합(WireMock: GitHub·청킹 흉내) + 계약 검증
```
로컬 포트 8514. LAN PC 2(+ 웹훅 중계 `docs-relay`, render.sh 가 붙임).
