# 요청 — 접수 건 현황 건수 화면 (case-service 0.18.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(본사 관리자)
상태: case PR 대기(병합·배포는 PC 1). 병합 순서 #83(0.15.0) → #82(PC 5, 0.16.0) → #85(0.17.0) → 이 PR(0.18.0).

## 정한 것 (사용자 2026-11-17 "우리 데이터 건수부터" — 업무 원문 BO-28)
- 지금 가진 데이터(접수 건·후속 처리·교대 인계·문의)의 **건수만** 보여 준다. 비율·목표·SLA 달성률은 승인된 정의가 없어 내지 않는다 — 화면이 "N건 중 M건"으로 보여 준다.
- 직원 개인별로 나누지 않는다. 다른 서비스 지표(정시 배송률·결품률 등 — bff `views/metrics`)는 아직 없다(501).

## API
`GET /api/v1/cases/metrics` (OPERATOR_ADMIN 만, 그 밖 403)
```json
{
  "asOf": "2026-11-17T05:00:00Z",
  "cases": [ { "kind": "MISDELIVERY", "open": 3, "unassigned": 1, "overdue": 1 } ],
  "obligations": [ { "owner": "DELIVERY_OPS", "open": 2, "overdue": 0 } ],
  "pendingHandovers": 1,
  "inquiries": [ { "requesterType": "CUSTOMER", "awaitingAnswer": 4, "oldestReceivedAt": "…" } ]
}
```
- 0건인 종류·팀은 줄이 없다(0으로 그린다). `overdue` 는 처리 기한(표시용 SLA)이 지난 열린 건.
- 문의 만족도는 따로 `GET /inquiries/satisfaction-summary`(#83).

## 화면 제안
- 대시보드(관리자 홈 또는 사건함 위): 종류별 카드 "열린 N · 안 집음 M · 기한 지남 K"(K>0 빨강), 후속 처리 팀별, "수락 전 교대 인계 N건", 문의 "답변 대기 N건(가장 오래된 것 X분 전)". `asOf` 표시와 새로고침 버튼(자동 새로고침은 1분 이상).
- 카드를 누르면 사건함·문의 목록으로 그 조건 거르기.
