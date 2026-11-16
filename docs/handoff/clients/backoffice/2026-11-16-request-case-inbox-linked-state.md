# 요청 — 사건함 목록에 연결 배송·주문의 지금 상태 (backoffice-bff 0.5.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(사건함 — 지원센터·고객센터·관리자)
상태: bff PR 대기(병합·배포는 PC 1). PR #27 → #31 → 이 PR 순서로 병합.

## 1. 왜
사건함에서 사건을 열지 않고도 "그 배송이 지금 어디까지 갔는지(픽업·도착·완료)", "주문이 취소됐는지"를 바로 보게 한다. 지금 웹은 case-service `/cases` 를 직접 읽어 사건 정보만 보인다.

## 2. API
`GET /api/v1/backoffice/views/case-inbox` — 필터는 case 목록과 같은 뜻: `state·kind·assignee(= assigneeId)·assigneeGroup·unassigned·overdue·centerId·priority·size(≤50, 기본 20)·cursor`. 보이는 범위·정렬은 case 와 같다(역할별 범위 그대로).
- `items[]`: case 목록의 값(caseId·kind·state·priority·summary·subjectType/Ref·centerId·assigneeId·assigneeGroup·slaDueAt·overdue·openedAt·openObligations·linkedRefs) + **`linked[]`**: `{kind: DELIVERY|ORDER, id, state, version, source}`.
  - `state` 는 원천 값 그대로(배송 = delivery-execution state, 주문 = order status). 화면 문구로 옮기는 표는 웹 쪽 기존 것을 쓴다.
  - `source.confidence = UNKNOWN` 이면 그 칸은 "확인 불가"(원천 장애이거나 그 직원이 못 보는 원천 — 예: 지원센터는 주문을 못 볼 수 있음). 0건·정상으로 보이지 않게.
  - `state = null` 이고 `confidence = DIRECT` 이면 원천에 없음(404).
- `unavailableSources` 에 원천 이름이 있으면 그 원천 칸들이 모두 확인 불가라는 뜻. `case-service` 가 있으면 목록 자체를 못 읽은 것(빈 목록 ≠ 사건 없음).
- `page.nextCursor` 는 case 목록 커서 그대로.

## 3. 바라는 것
- 사건함 목록을 이 API 로 바꾸고, 각 줄에 연결 배송·주문 상태를 작게 표시(확인 불가 표시 포함). 집기·닫기·상세는 지금처럼 case-service 를 직접.
- 목 모드가 있으면 위 모양으로.

## 4. 계약
`contracts/openapi/backoffice-bff.yaml` 0.5.0 `/views/case-inbox`.
