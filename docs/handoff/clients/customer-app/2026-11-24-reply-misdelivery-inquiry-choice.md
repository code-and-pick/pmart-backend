# 회신 — 오배송 문의 채팅 선택(R-16, ADR-0086) · 고객앱 질문 Q1~Q7

보낸 영역: backoffice
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-24-backend-misdelivery-screen-design.md`(Q1~Q7, PC 1 백엔드가 전달)
작성: pmart-backend PC 2 백엔드, 2026-11-24 — 사용자 "PC 1 문서(ADR-0086, 요청서 R-16) … 바로 진행해"
상태: **PR 대기**(case-service 0.39.0) — 병합·LAN 배포는 PC 1. 계약 `contracts/openapi/case-service.yaml` 0.39.0

## 흐름
1. 주문 상세 "상품을 받지 못했어요" → `POST /api/v1/inquiries` `{ category: DELIVERY, orderId, deliveryIssue: NOT_RECEIVED, body }` (+ `deliveryId` 있으면).
2. 그 배송이 **전달 완료된 고객 배송(FORWARD·RESHIPMENT)** 이면 응답 `misdelivery`(state AWAITING_CHOICE)가 붙고, 대화에 선택 안내가 들어간다. 아니면(전달 전 등) `misdelivery` 없음 — 지금처럼 상담원 문의.
3. 앱은 대화(`GET /inquiries/{id}/messages`)를 보여 주고, `misdelivery.state` 가 AWAITING_CHOICE 면 버튼 두 개(다시 보내기·취소 환불)와 기한을 보인다.
4. 고르기 `POST /api/v1/inquiries/{id}/misdelivery-choices { resolution: RESHIP | REFUND }`.
5. 5시간 안에 안 고르면 서버가 자동 REFUND, 대화에 안내.

## Q1 — 선택 메시지 모양
**둘 다**: 대화에는 시스템 메시지(글), 문의 상세에는 구조 값 — 버튼은 구조 값으로 그린다.
- 대화 `GET /inquiries/{id}/messages` items: `{ messageId, seq, senderType: SYSTEM, senderId: null, systemKind: MISDELIVERY_CHOICE_REQUEST, body, createdAt }` — body 예 "상품을 받지 못하셨군요. 다시 보내 드릴까요, 주문을 취소하고 환불해 드릴까요? 11월 24일 20:30까지 고르지 않으시면 배달팁까지 모두 환불해 드려요." 고른 뒤 `systemKind: MISDELIVERY_CHOSEN` 한 줄 더.
- 상세 `GET /inquiries/{id}` `misdelivery`:
  `{ caseId, deliveryId, state: AWAITING_CHOICE|CHOSEN, options: [RESHIP, REFUND](고르기 전만, 고른 뒤 []), deadlineAt, resolution: RESHIP|REFUND|null, chosenByType: CUSTOMER|STAFF|AUTO|null, chosenAt, autoResolved }`.
- senderType 은 이제 SUPPORT(상담원)·CUSTOMER(고객이 이어 씀)·RIDER·SYSTEM. 상세 `replies[]` 는 지금처럼 상담원 답변만(새 값이 섞이지 않음) — 대화 화면은 `/messages` 를 쓴다.

## Q2 — 배송 ID 없이 주문만
그렇게 했다. `deliveryId` 를 안 보내면 서버가 **그 주문의 가장 최근 전달 완료 고객 배송**을 고르고, 문의 `deliveryId` 에 채워 돌려준다. 보내면 그 배송을 본다(다른 주문의 배송이면 오배송 흐름을 열지 않는다).
- 한계: 판단은 case 가 받는 배송 사건 사본으로 한다 — **이 판 배포 전에 만들어진 배송**은 사본이 없어 오배송 흐름이 열리지 않고 상담원 문의가 된다(상담원이 직접 처리).

## Q3 — content(body) 필수?
지금처럼 **필수(1자 이상)**. 앱이 기본 문구(예 "상품을 받지 못했어요")를 넣어 바로 접수하면 된다. 제목은 없어도 된다.

## Q4 — 상담원 대신 vs 자동 환불
`misdelivery.chosenByType`: CUSTOMER(고객) · STAFF(상담원이 대신 — 대화에 "상담원이 고객님 대신 골랐어요." 안내) · AUTO(기한 지나 자동 — `autoResolved: true`). 상담원 ID·사유는 고객에게 주지 않는다.

## Q5 — 목록에 선택 대기·기한
`GET /inquiries` 줄에 `misdeliveryState`(AWAITING_CHOICE|CHOSEN|null)·`misdeliveryDeadlineAt`(AWAITING_CHOICE 일 때). "고르기 기다림 · HH:mm까지" 꼬리표에 쓰면 된다.

## Q6 — 고객 알림
case 는 **새 알림을 내지 않는다** — 선택 안내·결과는 대화(시스템 메시지)로만. 상담원이 답변(`/replies`)하면 지금처럼 알림함 "답변이 등록되었어요". 다시 보내기·환불 진행 알림은 지금 있는 주문·환불 알림(ADR-0086 "남은 것" — 충분한지 화면 시험 뒤 본다). 기한 전 푸시가 필요하면 notification(PC 1)에 따로 요청.

## Q7 — 고르기 응답·거절 code
- 200 = 문의 상세(`Inquiry` — `misdelivery.state` CHOSEN). 같은 Idempotency-Key·같은 본문 재전송은 같은 결과. 짝 조회는 `GET /inquiries/{id}`(misdelivery).
- 400 VALIDATION_FAILED — resolution 이 RESHIP·REFUND 가 아님.
- 403 — 고객·상담원(CS_AGENT·OPERATOR_ADMIN)이 아님.
- 404 MISDELIVERY_NOT_FOUND — 오배송 선택 문의가 아니거나 남의 문의.
- 409 MISDELIVERY_ALREADY_CHOSEN — 이미 고름(자동 포함). 409 MISDELIVERY_CHOICE_EXPIRED — 기한 지남(1분 안에 자동 환불됨 — 상세를 다시 읽기).
- 고른 뒤 진행: RESHIP 은 재발송 배송이 주문 상세에 생기고, REFUND 는 클레임·환불(배달팁·봉투값 포함 — ADR-0086 결정 8). 둘 다 오배송된 상품 회수는 따로 진행(고객이 할 일 없음).

## 고객이 이어 쓰기
`POST /inquiries/{id}/messages { body }` 를 고객도 쓴다(201, senderType CUSTOMER). 상태가 RECEIVED 로 돌아가 상담원 목록에 다시 뜬다. 닫힌 문의 409 INQUIRY_CLOSED, 이용제한·정지 고객 403 ACCOUNT_RESTRICTED, 막기 단어 422 BLOCKED_WORDS(새 문의와 같음).
