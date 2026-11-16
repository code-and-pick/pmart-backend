# 요청 — 사건 다시 열기(재개)와 이전 사건 표시 (case-service 0.6.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(사건함 상세 — 지원센터·관리자)
상태: case PR 대기(병합·배포는 PC 1).

## 1. 정한 것 (사용자 2026-11-16 "1번 + 이전 사건 연결")
- 닫힌 사건은 **사람이 사유를 적고 다시 연다**(자동 재개 없음). 닫기와 같은 사람 — 배송 쪽 사건은 지원센터(RIDER_OPS)·운영 관리자, 사가·결제 사건은 운영 관리자. 상담원은 보기만(403). 기간 제한 없음.
- 다시 열면 담당이 비어 팀 공용 목록으로 돌아가고, 처리 기한은 그때부터 다시 센다. 마지막 종결 내용(resolution·처리 내용)은 상세에 남는다.
- 닫힌 뒤 같은 대상(같은 종류·같은 배송/주문)으로 새 사건이 열리면 새 사건 `refs` 에 **`previousCaseId`**(가장 최근 닫힌 사건)가 붙는다.

## 2. API
- `POST /api/v1/cases/{caseId}/reopenings` 본문 `{reason}`(필수, 500자), `Idempotency-Key`·`X-Source-System`, 선택 `X-Expected-Version`. 200 = 사건 상세(state `REOPENED`).
- 409 `INVALID_STATE` = 닫히지 않은 사건, 409 `CASE_ALREADY_OPEN` = 같은 일로 이미 열린 사건이 있음(그 사건에서 처리 — 보통 previousCaseId 로 이 사건을 가리키는 새 사건).
- 상태 `REOPENED` 는 열린 사건으로 취급(목록 기본에 나오고 집기·닫기 가능).

## 3. 화면에 바라는 것
- 닫힌 사건 상세에 "다시 열기" 버튼(사유 입력 필수) — 권한 없는 역할에는 숨기거나 비활성.
- 사건 상세의 refs 에 `previousCaseId` 가 있으면 "이전 사건" 링크. 재개 409 CASE_ALREADY_OPEN 이면 열린 사건으로 안내.
- 이력에 `REOPENED`(사유)를 다른 이력처럼 표시.

## 4. 계약
`contracts/openapi/case-service.yaml` 0.6.0 `/cases/{caseId}/reopenings`, CaseSummary.refs 설명.
