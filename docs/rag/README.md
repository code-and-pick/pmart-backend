# RAG — ai-assistant-service 문서 모음

> **새 RAG 시스템(2026-11-28 — ES-First 하이브리드, 새 서비스로만)의 구조는 [`new-rag-system.md`](new-rag-system.md)** — integration-service → chunking-service → embedding-service(ADR-0095~0098). 기존 ai-assistant-service·customer-assistant-service 는 새 RAG 에서 제외(ADR-0097, 지우지 않음). 아래는 기존 RAG 기록이다.

> **인수인계·최신 상태는 [`HANDOVER.md`](HANDOVER.md)**(2026-11-13 — 두 RAG 서비스, 색인 문서 경로, LAN PC 5 배치·모델, 명령, 평가, 남은 일). 아래 "현재 상태"는 2026-11-05 기준이다.

- **"RAG"** 는 이 저장소에서 `ai-assistant-service` 의 내부 운영자용 정책·업무 절차 질의응답(검색 증강 답변)을 가리킨다(2026-11-05 사용자 지정).
- 서비스 이름·코드 패키지(`com.pmart.assistant`)·계약 이름은 그대로 `ai-assistant-service` 다.
- 이 폴더에는 RAG 에 관한 작업 일지·검토 원문·검증 자료를 모은다. 설계 결정·계약·코드는 원래 자리에 둔다.

## 이 폴더

| 경로 | 내용 |
|---|---|
| [`worklog.md`](worklog.md) | **RAG 작업 일지**(날짜별). 앞으로 RAG 작업은 여기에 쓴다. 일반 일지 `docs/worklog/` 에는 한 줄 안내만 둔다 |
| [`codex-opinion/`](codex-opinion/README.md) | 붙여 넣은 설계·검토 원문(1~6차)과 1차 계획, 반영 여부 표. 원문은 수정하지 않는다 |
| [`references/`](references/README.md) | 사용자가 넣은 외부 자료(검토 글·모델 추천·그림)의 **원본**, 날짜별. 수정하지 않는다 |
| [`planning/`](planning/README.md) | 원본과 현재 구현을 대조한 정리·계획·새 그림. 결정 대기 목록 |
| [`verification/2026-11-05/`](verification/2026-11-05/README.md) | 2026-11-05 검토 자료: 빌드·시험 로그, diff, 검색 평가 전후, 시험 결과 |

## 원래 자리에 있는 RAG 문서·코드

| 무엇 | 경로 |
|---|---|
| 결정 | `docs/adr/0036-ai-assistant-phase1-policy-rag.md`(제안) |
| 서비스 규칙 | `services/ai-assistant-service/CLAUDE.md` |
| 계약 | `contracts/openapi/ai-assistant-service.yaml`(1.1.0) |
| 색인 대상 목록 | `services/ai-assistant-service/src/main/resources/knowledge/manifest.yml` |
| 검색 평가 질문·정답 | `services/ai-assistant-service/src/integrationTest/resources/eval/questions.yml` |
| 클라이언트 안내 | `docs/handoff/clients/backoffice/2026-11-04-clients-request-ai-assistant.md`, `docs/handoff/clients/archive/2026-11-04-clients-reply-br44-br48.md`(BR-46) |
| 서비스 지도 표기 | `docs/architecture/service-map.md`, `architecture-design.md`("1차 앞당김(ADR-0036)") |
| 로컬 실행 | `deploy/local/ai-assistant/docker-compose.yml`(pgvector, 15433) |
| 설정 | 설정 저장소 `ai-assistant-service{,-local,-lan}.yml`, 게이트웨이 `/api/v1/assistant/**` |

## 현재 상태 (2026-11-05)

- 1차 구현 완료: 외부 모델 없는 기준선(해시 임베딩 + 발췌).
- LAN 배포 안 함. 외부 모델 연결 안 함.
- 결정 대기(ADR-0036):
  1. 외부 모델 제공자·외부 전송 범위
  2. LAN pgvector 위치
  3. 답변 보존 기간·질문 원문 저장
  4. 운영자 역할 세분
- 검색 기준선:
  - 문서 경로 기준 recall@5 0.76 · MRR 0.628
  - 절 기준 0.60 · 0.387
  - 의미 검색·생성 품질은 미검증
