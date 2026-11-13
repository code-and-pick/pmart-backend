# 회신: PDA 업무 선택 배정 — BR-52 R1·R3·R4 (mfc-capacity 계약 0.13.0)

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-13
- 답하는 것: pmart-clients `docs/handoff/2026-11-10-backend-pda-work-areas.md` 의 R1·R3·R4. 나머지(R2·R5~R20)는 아직 — 아래 §4.
- 계약: `contracts/openapi/mfc-capacity-service.yaml` 0.13.0(PR 병합 뒤 main 에서 `gen:api`). LAN 반영은 PC 1 배포 뒤.

## 1. R4 업무 ↔ 작업 유형 묶음 — 수용

새 스키마 `WorkArea`(앱 `WorkArea` 와 같은 다섯, 이름은 UPPER_SNAKE):

| WorkArea | 앱 | 작업 유형(지금 배정 가능한 것만) |
|---|---|---|
| `INBOUND` | inbound | RECEIVE |
| `INSPECTION` | inspection | INSPECT |
| `STOCK_MOVE` | stockMove | PUTAWAY·REPLENISH·MOVE·RETURN |
| `STOCK_COUNT` | stockCount | (없음) |
| `PICK_PACK` | pickPack | PICK·PACK |

- 앱 임시 값 `RETURN_INSPECT`·`RETURN_RECEIPT`·`CYCLE_COUNT` 는 **아직 백엔드 작업 유형이 아니다**(R2·R12~R14). 생기면 같은 묶음에 더하고 알린다. 그때까지 `STOCK_COUNT` 는 누구의 `workAreas` 에도 나오지 않는다.
- DISPATCH(배차 요청)는 어느 업무에도 없다 — 점원 일(BR-51).
- 자격·업무를 업무 단위로 **주는 API** 는 따로 만들지 않았다 — 센터 관리자 화면(백오피스)이 묶음을 펼쳐 작업 유형으로 보내면 된다. 응답 쪽은 아래 `workAreas`.

## 2. R3 작업자 본인 조회 — 수용(경로 그대로)

- `GET /api/v1/mfcs/{mfcId}/workers/{workerId}` 를 **본인 토큰으로** 부르면 된다(workerId = 토큰 subject). 그 센터 운영자면 원래 읽을 수 있었고, 계약 설명에 본인 사용을 적었다. `/me` 는 만들지 않았다.
- 응답 `Worker` 에 새 필드 **`workAreas: WorkArea[]`** — 묶음이 `effectiveTaskTypes` 와 하나라도 겹치는 업무(표 순서). 여기에 없는 업무를 누르면 "권한이 없어요".
- **고친 버그**: 0.12.0 까지 상세 응답에서 `employmentType`·`qualification`·`effectiveTaskTypes`·`todayEnrollment`·`onBreak` 가 비어 왔다(목록에만 있었음). 이제 상세에도 모두 온다.
- 일일크루는 그날 근무 등록(`POST …/day-enrollments`) 전에는 `effectiveTaskTypes`·`workAreas` 가 비어 있다.

## 3. R1 다음 작업 받기에 고른 업무 — 수용

- `POST /api/v1/mfcs/{mfcId}/workers/{workerId}/next-tasks` 에 **선택 본문** `{ "workArea": "PICK_PACK" }`.
  - 주면 그 업무의 작업 유형 ∩ 유효 업무 안에서만 새 작업을 준다.
  - 겹치는 유형이 없으면 **422 `WORK_AREA_NOT_ALLOWED`**.
  - 본문을 생략하면 예전과 같다(유효 업무 전체).
- 판정 순서: 등록·ACTIVE·일일크루 등록(422) → 근무 밖(409 OFF_SHIFT) → **들고 있는 작업이 있으면 그 배정 200** → 휴게(409 ON_BREAK) → 업무 판정(422 WORK_AREA_NOT_ALLOWED) → 배정 201 / 409 NO_TASK_AVAILABLE.
- **들고 있는 작업은 고른 업무와 상관없이 돌려준다**(한 번에 하나 원칙). 앱의 "고른 업무와 다른 작업이 배정됐어요" 안내는 이제 이 경우(전에 받은 작업이 남아 있을 때)에만 나온다.
- `INBOUND`·`INSPECTION` 을 골라도 지금은 409 NO_TASK_AVAILABLE 이다 — 다음 작업 받기 후보에 RECEIVE·INSPECT·PUTAWAY 가 아직 없다(R2).

## 4. 아직 안 한 것

- R2(입고·품질검사·적치·회수품·실사를 다음 작업 받기 후보로), R5(업무 외 메뉴 자격), R6(작업 가능 신호), R7, BR-53 R8~R11, R12~R20 — 회신 없음. 다음 차례 후보는 R20(피패킹 같은 작업자 연속 배정)과 R8·R9(입고 화면 상품명·바코드, taskId ↔ trfId).

## 5. 시험

- 단위 `WorkAreasTest` 2개(Docker — OR-Tools 포함 단위 전체 통과).
- 통합 `AssignmentPlanIT.nextTaskStaysWithinChosenWorkArea`: 본인 상세 읽기, 그 결과의 `workAreas`·자격·고용 형태 확인, INBOUND 422, STOCK_MOVE 를 골랐을 때 더 급한 PICK 대신 RETURN 201, 그 뒤 PICK_PACK 을 골라도 같은 작업 200.
