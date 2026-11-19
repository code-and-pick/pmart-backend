# ai-assistant-service 요청 (받는 영역 rag — PC 4)

## AI-1. 업무 도우미 질문 원문 저장 — "내 질문 목록"에 무엇을 물었는지 (2026-11-19, 보낸 영역 backoffice — PC 2, **2026-11-19 사용자 결정 — 추천대로**)

- **요청한 곳**: 백오피스 웹(pmart-clients `docs/handoff/backoffice/2026-11-19-backend-assistant-store-question.md` — 사용자 2026-11-19 "업무 도우미에서 내 질문 목록을 봤을 때 전에 한 질문이 뭔지 안 보여"). 웹은 받는 곳을 platform 으로 적었지만 ai-assistant-service 는 rag(PC 4) — PC 2 가 옮겨 적음.
- **지금**: `GET /assistant/question-history` 의 `question` 이 기본 null(`pmart.assistant.answers.store-question=false` — ADR-0036 9 "질문 원문은 기본 저장하지 않는다(request_hash 만)"). 목록을 답 앞 120자(`answerPreview`)로만 구분하는데 답 앞부분이 비슷해 구분이 안 된다. 웹 임시: 그 탭에서 물은 질문만 sessionStorage 로 기억(탭 닫으면 사라짐).
- **필요한 것**: ADR-0036 "결정 필요 3"(답변 보존 기간·질문 원문 저장 여부)을 정해 `store-question` 을 켤지. 계약 변경 없음(`question` 필드는 이미 있음).
- **결정(2026-11-19 사용자 "추천대로 해") — ADR-0036 결정 필요 3 중 질문 원문 저장**:
  1. 질문 원문을 저장하고 **답변과 같은 보존 기간** — 답변 보존 기간이 아직 미설정이면 **90일**로 같이 정한다(따로 두면 질문 없는 답만 남거나 반대가 된다).
  2. **본인만** 원문을 본다(이미 question-history 는 본인 것만) — 관리자 조회·로그·색인에 넣지 않는다(ADR-0036 9 "질문 본문은 로그에 남기지 않는다" 그대로).
  3. **본인 지우기** `DELETE /assistant/question-history/{answerId}`(또는 원문만 지우는 POST) — 개인정보가 섞인 질문을 사용자가 지운다. 지우면 원문만 NULL(답변·감사 기록은 남김, ADR-0062 와 같은 방식).
  4. 켜기 전 질문은 그대로 null(소급 없음).
- **웹의 질문**: ① 질문 원문 보존 기간 = 답변 보존 기간인가, ② 개인정보가 섞인 질문을 지우는 방법(본인 삭제 API)을 둘 계획이 있는가 — 있으면 화면에 "이 질문 지우기".
- **받으면 웹은**: 탭 안 기억(`web/backoffice/src/features/assistant/askedQuestions.ts`)을 지우고 서버 `question` 만 쓴다.
- **상태**: 결정됨(2026-11-19) — **PC 4 구현 차례**(`store-question` 켬, 보존 90일·답변과 같이, 본인 지우기 API). ADR-0036 "결정 필요 3"을 이 결정으로 고치는 것은 PC 4(서비스 주인)가 같은 PR 에서.
