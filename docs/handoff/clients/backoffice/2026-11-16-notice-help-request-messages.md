# [pmart-backend → pmart-clients] 알림: 도움요청 대화 — 사건함에서 라이더에게 답장 (2026-11-16)

- 보낸 영역: delivery
- 받는 앱: backoffice (지원센터 사건함 — 사건 종류 RIDER_HELP 상세)
- 근거: 라이더 앱 요청 §11-3("백오피스 사건함에서 상담원이 같은 줄기에 답장"), 계약 `delivery-execution-service.yaml` 0.6.0

## 쓰는 법
사건(RIDER_HELP)의 `refs` 에 `deliveryId`·`requestId` 가 있다(case 0.4.1). 그 둘로:
- 대화 보기: `GET /api/v1/deliveries/{deliveryId}/help-requests/{requestId}/messages?after=<seq>` → `items[]`(senderType RIDER·SUPPORT, 직원에게는 senderId = 라이더 ID 또는 상담원 운영자 ID), `helpState`.
- 답장: `POST …/messages` `{body}`(1~1000자, 사진 없음) + `Idempotency-Key`·`X-Source-System`.
- 권한: 보기 = 도움요청 조회와 같다(그 센터 운영자·OPERATOR_ADMIN·CS_AGENT·RIDER_OPS). 답장 = 처리 권한(그 센터 MFC_SUPERVISOR, OPERATOR_ADMIN, CS_AGENT, RIDER_OPS) — 아니면 403.
- 사건을 닫으면 도움요청이 RESOLVED 가 되어 대화도 닫힌다(409 HELP_REQUEST_CLOSED). 다시 열면 이어서 쓸 수 있다.
- 라이더 사진은 `GET /deliveries/{id}/evidence-photos/{photoRef}` 로 본다(직원 가능).
- 새 메시지 알림(사건 이력·푸시)은 아직 없다 — 사건 상세를 열어 둔 동안 `after` 로 몇 초마다 확인.
