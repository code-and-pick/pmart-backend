# 알림 — 오배송 문의 처리 화면용 API (R-16, ADR-0086)

보낸 영역: backoffice
받는 앱: backoffice
작성: pmart-backend PC 2 백엔드, 2026-11-24 — ADR-0086 결정 1~3, R-16 ⑤ "통합 백오피스 오배송 문의 처리 화면: 문의·채팅·선택 상태·남은 시간·회수/재발송/환불 진행·대신 고르기"
상태: **PR 대기**(case-service 0.39.0) — 병합·LAN 배포는 PC 1. 새 bff 경로 없음 — case 를 직접 부른다(이미 쓰는 길).

## 무엇이 생기나
- 고객이 "받지 못했어요"(NOT_RECEIVED)를 남기고 그 배송이 전달 완료면 → 고객 문의 + **오배송 접수 건(MISDELIVERY, 귀책 RIDER, 지원센터 팀 DELIVERY_OPS)** 이 같이 생긴다. 접수 건 refs: deliveryId·orderId·inquiryId·source CUSTOMER_INQUIRY·fault RIDER·riderId, 요약 "고객 오배송 문의 — 고객 선택 대기(다시 보내기·환불)".
- 고르면 접수 건 refs 에 resolution, 이력 `MISDELIVERY_CHOSEN`("다시 보내기 — 고객이 고름" 등), 그 뒤 회수 배송(recoveryDeliveryId)·재발송 배송(reshipmentDeliveryId)은 지금처럼 이력·refs 에 붙는다(bff 접수 건함이 이미 상태를 보여 줌).

## 화면이 쓸 것(case)
| 보여 줄 것 | 경로 |
|---|---|
| 오배송 문의 목록 | `GET /inquiries?deliveryIssue=NOT_RECEIVED` — 줄 `misdeliveryState`(AWAITING_CHOICE·CHOSEN·null)·`misdeliveryDeadlineAt`(남은 시간) |
| 문의·선택 상태 | `GET /inquiries/{id}` — `misdelivery`{ caseId, deliveryId, state, options, deadlineAt, resolution, chosenByType CUSTOMER·STAFF·AUTO, chosenAt, autoResolved, **chosenBy·chosenReason(직원에게만)** } |
| 채팅 | `GET /inquiries/{id}/messages` — senderType SUPPORT·CUSTOMER·SYSTEM(`systemKind` MISDELIVERY_CHOICE_REQUEST·MISDELIVERY_CHOSEN), 상담원 답은 지금처럼 `POST /inquiries/{id}/replies` |
| 대신 고르기 | `POST /inquiries/{id}/misdelivery-choices { resolution: RESHIP·REFUND, reason(2~200자 필수) }` — CS_AGENT·OPERATOR_ADMIN, 기한 안·한 번만(409 MISDELIVERY_ALREADY_CHOSEN·MISDELIVERY_CHOICE_EXPIRED), 200 = 문의 상세 |
| 회수·재발송·환불 진행 | `misdelivery.caseId` 로 접수 건 상세(`GET /cases/{id}` 또는 bff `views/case-inbox`) — 회수·재발송 배송 상태, 환불은 bff `views/claims?orderId=`(claim) |

- 기한(5시간)이 지나면 서버가 1분 안에 자동 REFUND(chosenByType AUTO) — 화면은 남은 시간이 0 이면 "자동 환불 중"으로.
- 상세 `replies[]` 는 상담원 답변·라이더 대화만 — 오배송 문의 화면은 `/messages` 로 대화를 그린다.
- 웹 용어: 접수 건·후속 처리·완료 처리·맡기·다시 처리하기 그대로.
