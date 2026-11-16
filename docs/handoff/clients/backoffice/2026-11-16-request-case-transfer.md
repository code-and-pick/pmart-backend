# 요청 — 사건 넘기기(이관): 지원센터 ↔ 운영 관리자 (case-service 0.7.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(사건함 상세 — 지원센터·관리자)
상태: case PR 대기(병합·배포는 PC 1). PR #34(재개 0.6.0) 뒤에 병합.

## 1. 정한 것 (사용자 2026-11-16 "A안")
- **배송 쪽 사건만** 지원센터 팀(`DELIVERY_OPS`)과 운영 관리자 팀(`ORDER_OPS`) 사이로 넘긴다. 예: 지원센터가 감당 못 하는(환불이 얽힌) 오배송을 관리자에게. 사가·결제 사건은 원래 관리자 팀이라 넘기지 않는다.
- 넘기면 담당이 비고(집은 상태 → 열림), 처리 기한은 그대로. 관리자 팀으로 간 배송 쪽 사건은 **지원센터·상담원이 계속 보지만** 집기·닫기·다시 열기·넘기기는 관리자만.
- 사유 필수. 다른 직원이 맡은 사건은 관리자만 넘긴다. 센터로 넘기기·사건 합치기는 없다.

## 2. API
- `POST /api/v1/cases/{caseId}/transfers` 본문 `{toGroup: "DELIVERY_OPS"|"ORDER_OPS", reason}`, `Idempotency-Key`·`X-Source-System`, 선택 `X-Expected-Version`. 200 = 사건 상세(assigneeGroup 바뀜, assigneeId 비움).
- 403 = 권한 없음(상담원, 남이 맡은 사건, 관리자 팀 사건을 지원센터가), 409 `INVALID_STATE` = 닫힌 사건·이미 그 팀, 422 `UNSUPPORTED_ACTION` = 넘길 수 없는 사건·팀.
- 이력에 `TRANSFERRED`(note `DELIVERY_OPS → ORDER_OPS: 사유`).
- 목록 `GET /cases?assigneeGroup=ORDER_OPS` 로 관리자 팀 사건만.

## 3. 화면에 바라는 것
- 배송 쪽 열린 사건 상세에 "관리자에게 넘기기"(지금 팀이 DELIVERY_OPS 일 때)·"지원센터로 돌려보내기"(ORDER_OPS 일 때, 관리자만) — 사유 입력 필수. 할 수 있는지는 서버가 판정(403 은 권한 없음 안내).
- 목록·상세에 담당 팀을 보여 주고, ORDER_OPS 로 간 배송 쪽 사건은 지원센터 화면에서 **"관리자 처리 중"**으로 표시(해결된 것처럼 보이지 않게 — 업무 원문 §5.3).

## 4. 계약
`contracts/openapi/case-service.yaml` 0.7.0 `/cases/{caseId}/transfers`, AssigneeGroup 에 ORDER_OPS.
