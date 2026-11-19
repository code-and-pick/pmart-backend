# 회신 — 업무 도우미 질문 원문 저장

보낸 영역: backoffice
- 작성: pmart-backend PC 2, 2026-11-19
- 받은 요청: pmart-clients `docs/handoff/backoffice/2026-11-19-backend-assistant-store-question.md`

## 회신
- ai-assistant-service 는 **rag 영역(PC 4)** 이다(platform 아님) — 요청서 `docs/architecture/service-requests/ai-assistant-service.md` **AI-1** 로 옮겼다.
- 질문 원문 저장은 ADR-0036 "결정 필요 3"(답변 보존 기간·질문 원문 저장 여부)이라 **사용자 결정이 먼저**다. PC 2 추천안: 답변과 같은 보존 기간으로 저장, 본인만 보기, 본인 지우기(원문만 지움), 켜기 전 질문은 그대로 없음.
- 질문 답(추천안 기준 — 결정되면 PC 4 가 확정해 회신): ① 보존 기간 = 답변 보존 기간, ② 본인 지우기 API 를 둔다 → 화면에 "이 질문 지우기".
- 그때까지 지금처럼(탭 안 기억 + "질문 글 없음").
