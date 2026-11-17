# [pmart-backend → pmart-clients] 회신: 라이더 처리 결과 알림 (§11-5) (2026-11-17)

- 보낸 영역: delivery(PC 5 작성 — notification 은 platform, case 는 backoffice 검토)
- 받는 앱: rider_app
- 근거: 라이더 앱 요청 §11-5, 사용자 결정 2026-11-16 "라이더 알림함 먼저" + 알릴 일 4가지 — ADR-0066

## 된 것 — 라이더 알림함(휴대폰 푸시는 아직 없음)
- 고객과 같은 경로를 라이더 토큰으로: `GET /api/v1/me/notifications`(`unreadCount`, `unreadOnly`, `category`), 상세, 읽음 `POST …/{id}/reads`.
- 쌓이는 알림:
| 일 | title | category | destination |
|---|---|---|---|
| 도움요청 대화에 지원센터 답장 | 지원센터 답장이 왔어요 | CASE | HELP_REQUEST, ref = `<deliveryId>:<requestId>` |
| 도움요청 처리 끝 | 도움요청 처리가 끝났어요 | CASE | HELP_REQUEST(같은 ref) |
| 배송 밖 문의 답변 | 문의에 답변이 등록되었어요 | CASE | INQUIRY, ref = inquiryId(`GET /inquiries/{id}`) |
| 계정 제한·해제 | 새 배차가 제한되었어요 / 계정이 정지되었어요 / 계정 이용이 종료되었어요 / 계정 제한이 풀렸어요 | SYSTEM | NONE |
- 문구에 메시지·답변 내용은 없다 — 알림을 누르면 destination 화면에서 읽는다. HELP_REQUEST ref 는 `:` 로 나눠 `GET /deliveries/{deliveryId}/help-requests/{requestId}`(대화는 `…/messages`).

## 앱이 할 것
- 푸시가 없으므로 앱이 켜져 있을 때 알림함을 주기적으로 확인(예: 30초, 또는 화면 전환 때) → `unreadCount` 배지·새 알림 안내.
- 휴대폰 푸시(FCM)는 Firebase 프로젝트·키가 생기면 백엔드가 같은 알림에 채널을 더한다(기기 토큰 등록 API 는 그때).
