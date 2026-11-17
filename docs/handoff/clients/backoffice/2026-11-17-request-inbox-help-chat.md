# 요청 — 사건함의 도움요청 "새 메시지" 표시 (backoffice-bff 0.6.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(지원센터 사건함)
상태: bff PR 대기(병합·배포는 PC 1). execution DE-2(0.8.0)는 LAN 배포됨.

## 바뀐 것
- `GET /api/v1/backoffice/views/case-inbox` 의 라이더 도움요청 건(kind RIDER_HELP)에 `linked` 한 줄이 더 붙는다:
  `{ "kind": "HELP_REQUEST", "id": "<requestId>", "state": "OPEN|IN_PROGRESS|RESOLVED", "lastRiderMessageAt": "…", "lastSupportMessageAt": "…", "source": {…} }`
- 도움요청을 못 읽으면(장애·403) 그 칸만 state null·confidence UNKNOWN, 404 면 state null(없음).

## 화면 제안
- 사건함 목록에서 `lastRiderMessageAt` 이 있고 `lastSupportMessageAt` 보다 늦으면(또는 지원센터 답이 없으면) "새 메시지" 점. 직원별 읽음은 화면이 기억(마지막으로 연 시각과 비교) — 백엔드에 읽음 기록은 없다.
- 도움요청 대화 화면의 답장 권한·가림은 execution DE-2 회신(`2026-11-17-reply-help-chat-de2.md`) 그대로.
