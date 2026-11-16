# [pmart-backend → pmart-clients] 알림: 사건함에 사가·결제 이상(관리자만)·배차 실패(모두) (2026-11-16)

- 보낸 영역: backoffice
- 받는 앱: backoffice
- 결정: case R-3(`docs/architecture/service-requests/case-service.md`), 사용자 2026-11-16 — 처리 기한 "한시간으로"(표시만), 받을 범위 "이상만", 업무 거절로 멈춘 사가는 "지금은 안 함"(사건으로 열지 않고 처리 대기에만).
- 계약: `case-service.yaml` **0.5.0**(설명만 바뀜 — 모양·`CaseKind` 이름은 그대로라 `gen:api` 다시 할 필요 없음). 백엔드 PR code-and-pick/pmart-backend#21, LAN 배포 전(PC 1 이 설정 저장소 바인딩과 함께 배포).
- 클라이언트 반영: 사가·결제 이상은 pmart-clients PR #13 으로 이미 올라감. **배차 실패(§3)는 아직 화면 반영 필요**.

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

## 3. 더함(같은 날 오후) — 배차 실패 `DISPATCH_FAILED`(배송 쪽, 모두에게) — **화면 반영 필요**
- 결정(사용자 2026-11-16): 배차 실패도 사건함으로. 범위 중간안 — 시스템 장애(자동 재배차 없음)는 첫 실패에, 라이더 없음·반복 거절·응답 만료(10분마다 자동 재배차)는 같은 배송의 **두 번째 실패부터**. 담당 **지원센터(RIDER_OPS)**, 기한 **10분**.
- `GET /cases` 에 `kind=DISPATCH_FAILED` 가 나온다 — **배송 쪽 종류라 RIDER_OPS·CS_AGENT·OPERATOR_ADMIN 모두**(위 두 종류와 다름). `assigneeGroup` DELIVERY_OPS, P2, 집기·닫기는 RIDER_OPS·관리자, 상담원은 보기.
- `summary` 예: "배차 실패 2번째 — 응답 만료 (10분마다 자동 재배차 중)" / "배차 실패 1번째 — 시스템 장애 (자동 재배차 없음)". 같은 배송의 다음 실패는 이력 `RECURRED`(note 에 몇 번째·사유).
- `refs`: `deliveryId`(배송 화면으로)·`orderId`. `incidentId` 없음(도움요청 단건 조회 대상 아님).
- 배차가 뒤에 성공해도 사건은 자동으로 닫히지 않는다 — 담당자가 배송 상세(배차)를 보고 닫는다.
- **화면 제안**: 배송 쪽 종류 필터·이름에 "배차 실패"(모든 역할), 상세에서 배송 화면(배차 이력)으로 가는 링크는 기존 refs 링크로 충분. PR #13 은 관리자용 두 종류만 담고 있어 이것은 따로(또는 #13 에 더해) 반영.

## 4. 질문이 있으면
- `pmart-clients/docs/handoff/backoffice/` 에 남겨 주세요.
