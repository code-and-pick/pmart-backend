# [pmart-backend → pmart-clients] 알림: 사건함에 사가·결제 이상 두 종류 — 관리자만 (2026-11-16)

- 보낸 영역: backoffice
- 받는 앱: backoffice
- 결정: case R-3(`docs/architecture/service-requests/case-service.md`), 사용자 2026-11-16 — 처리 기한 "한시간으로"(표시만), 받을 범위 "이상만", 업무 거절로 멈춘 사가는 "지금은 안 함"(사건으로 열지 않고 처리 대기에만).
- 계약: `case-service.yaml` **0.5.0**(설명만 바뀜 — 모양·`CaseKind` 이름은 그대로라 `gen:api` 다시 할 필요 없음). 백엔드 PR code-and-pick/pmart-backend#21, LAN 배포 전(PC 1 이 설정 저장소 바인딩과 함께 배포).
- 클라이언트 반영: 같은 날 pmart-clients PR #13 으로 이미 올라감(이 알림은 기록용).

## 1. 바뀐 것
- `GET /cases` 에 새 종류 둘이 나온다 — **OPERATOR_ADMIN 에게만**(RIDER_OPS·CS_AGENT 는 목록에 없고 단건 404).
  - `RECONCILIATION`(대사 필요): 주문 처리 대사 필요, 결제 승인 불일치, 성공하지 못한 보상 해제.
  - `PAYMENT_UNKNOWN`(결제 결과 불명): 결제 결과 조회가 상신된 것.
- `assigneeGroup` = `ORDER_OPS`, 우선순위 P2, `slaDueAt` = 열린 뒤 1시간(표시만, 알림 없음).
- `refs`: `orderId`(있으면 — 주문 화면으로), `sagaId`, `paymentAttemptId`, `resourceId`(보상 자원) 중 있는 것만. 금액·상세는 사건에 없다.
- 같은 주문의 같은 종류는 열린 사건 하나 — 다시 오면 이력에 `RECURRED`(결제 불명 반복은 이력도 없음).
- 집기·닫기는 기존 API 그대로(관리자만 가능).

## 2. 화면 제안(이미 #13 에 반영)
- 관리자에게만 종류 필터·이름에 두 종류.
- 상세에 "사건은 알림·기록, 조치는 처리 대기(`/pending`)와 각 메뉴(환불·재고 정정)" 안내 — 처리 대기(BO-12)와 겹치지 않게.

## 3. 질문이 있으면
- `pmart-clients/docs/handoff/backoffice/` 에 남겨 주세요.
