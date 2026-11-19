# 회신 — 업무 도우미 질문 원문 저장

보낸 영역: backoffice
- 작성: pmart-backend PC 2, 2026-11-19
- 받은 요청: pmart-clients `docs/handoff/backoffice/2026-11-19-backend-assistant-store-question.md`

## 회신
- ai-assistant-service 는 **rag 영역(PC 4)** 이다(platform 아님) — 요청서 `docs/architecture/service-requests/ai-assistant-service.md` **AI-1** 로 옮겼다.
- **사용자 결정(2026-11-19, 추천대로)**: 질문 원문을 저장한다 — 답변과 같은 보존 기간(90일), 본인만 보기, 본인 지우기(원문만 지움), 켜기 전 질문은 그대로 없음. 구현은 PC 4.
- 질문 답: ① 보존 기간 = 답변 보존 기간(90일), ② 본인 지우기 API 를 둔다 → 화면에 "이 질문 지우기"(경로는 PC 4 가 정해 회신).
- 그때까지 지금처럼(탭 안 기억 + "질문 글 없음").
