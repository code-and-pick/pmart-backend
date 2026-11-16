# 요청 — 교대 인수인계 화면 (case-service 0.9.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(지원센터 — 지원센터·관리자)
상태: case PR 대기(병합·배포는 PC 1).

> 화면 용어(2026-11-16 사용자 결정, pmart-clients PR #28): 사건(case) = **"접수 건"**, 상태 OPEN 접수됨 · CLOSED 처리 끝남 · REOPENED 다시 처리 중, 닫기 = 처리 끝내기, 의무 = 후속 처리. 아래 API 이름은 백엔드 이름 그대로.

## 1. 정한 것 (사용자 2026-11-16 "진행해 줘" — 업무 원문 BO-27 최대한 그대로)
- 교대 때 **내가 맡은(집은) 처리 중인 접수 건**을 후임에게 넘긴다. 건마다 **다음 조치(필수)**와 확인 기한(선택)을 적는다 — 이름만 바꾸지 않는다.
- 인계서를 쓰는 것만으로는 담당이 바뀌지 않는다. **후임이 수락해야** 넘어간다. 후임은 **건별로** 받을지 고르고, 받지 않은 건은 전임에게 남아 **"미인수"**로 관리자에게 보인다.
- 수락 전에는 전임이 계속 담당하고, 전임은 인계서를 취소할 수 있다. 전임이 갑자기 없으면 **관리자가 대신 인계서를 쓴다**.
- 작성 때 그 팀의 처리 중인 접수 건·후속 처리 수가 스냅샷으로 함께 남는다(교대 기록).
- 시간이 지나도 자동으로 넘어가거나 돌아가지 않는다. 알림 없음.

## 2. API
- 작성 `POST /api/v1/shift-handovers` `{group: "DELIVERY_OPS"|"ORDER_OPS", toOperatorId, items: [{caseId, nextAction, checkDueAt?}], centerId?, fromOperatorId?(관리자가 대신 쓸 때), note?}` → 201 인계서(state PENDING).
  - 400(다음 조치 빠짐·같은 건 두 번·후임 = 전임) / 403(남의 인계서 — 관리자만) / 409 `CASE_IN_HANDOVER`(다른 진행 중 인계서에 든 건) / 422 `NOT_HANDOVERABLE`(처리 끝난 건·내가 맡지 않은 건·다른 팀 건).
- 수락 `POST /api/v1/shift-handovers/{id}/acceptances` `{excludedCaseIds?: []}` — **후임 본인만**(관리자도 403). 200 인계서(COMPLETED), `items[].outcome`:
  - `ACCEPTED` 받음(담당이 후임으로) / `EXCLUDED` 안 받음(전임 그대로 — 미인수) / `SKIPPED_CHANGED` 그사이 처리 끝났거나 담당이 바뀜 / `SKIPPED_NOT_ALLOWED` 후임이 그 건을 다룰 수 없는 역할.
- 취소 `POST /api/v1/shift-handovers/{id}/cancellations` — 수락 전, 전임·관리자.
- 조회 `GET /api/v1/shift-handovers/{id}`, 목록 `GET /api/v1/shift-handovers?state=&unreceived=true` — 관리자는 모두, 그 밖은 내가 전임·후임인 것. `unreceived` = 수락 전이거나 안 받은 건이 있음.
- 접수 건 이력에 `HANDOVER_REQUESTED`(인계서에 들어감)·`HANDED_OVER`(note "전임 → 후임: 다음 조치").

## 3. 화면에 바라는 것
- 지원센터 메뉴에 "교대 인계": ① 내 처리 중 접수 건을 다 보여 주고(기본 전부 선택) 건마다 다음 조치 입력 → 후임 선택 → 작성, ② "나에게 온 인계"(내가 후임, PENDING) — 건마다 받기/안 받기 → 수락, ③ 내가 쓴 진행 중 인계서 취소.
- 관리자에게 "미인수 인계" 목록(`unreceived=true`) — 전임 부재 시 대신 쓰기.
- 수락 결과를 건별로 보여 주기(받음·안 받음·바뀌어서 건너뜀·권한 없어 건너뜀).

## 4. 계약
`contracts/openapi/case-service.yaml` 0.9.0 `/shift-handovers`·`/shift-handovers/{id}`·`…/acceptances`·`…/cancellations`, `ShiftHandover` 스키마.
