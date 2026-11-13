# 공지: PDA·패킹 앱 응답 다듬기 계획 (참고용, 아직 구현 안 함)

- 근거: 사용자 결정 2026-11-08("클라이언트 쪽이 참고할 문서로 남기고 넘기자"). 백엔드는 **작업자 쪽(일일크루·다음 작업 받기 등) 구현 때 같이** 다듬는다. 그 전에 화면에 꼭 필요한 것이 있으면 `pmart-clients/docs/handoff/` 요청서로 보내 주세요.
- 관련 공지: `2026-11-08-clients-notice-handover-check.md`(점원 확인·배차 요청·복귀 추천 칸·포장 재구성 — 이미 구현된 API)

## 앞으로 바뀔 작업 방식 (결정됨, 구현은 작업자 쪽 때)
- 현장 크루는 정규직이든 일일크루든 **시스템이 작업을 하나씩 배정**("다음 작업 받기" — 할 수 있는 일 중 가장 급한 대기 작업). 감독자는 예외만 바꾼다. 한 번에 들고 있는 작업은 1개.
- 순서: 주문 작업(약속 급한 순) → 자동 보충 → 감독자 지시 이동(긴급은 보충과 같은 순위) → 취소품 복귀.
- MFC 는 온도대 출입 자격이 없다(누구나 냉동·냉장·상온 구역 이동).
- 휴게 중·근무 밖에는 새 작업을 받지 않는다. "작업 내놓기"(교대)는 잔량 확인 뒤 대기로.

## 다듬을 응답 (계획)
| 화면 | 지금 | 계획 |
|---|---|---|
| 피킹 지시 | `picks[].unallocatedQty` 숫자만 | "어느 칸에서도 못 찾음 — 감독자 확인" 같은 표시용 상태 |
| 점원 확인이 필요한 포장 | 포장마다 `GET /packages/{id}/handover-resolution` 의 `checkRequired` | 센터 전체 **확인 필요 포장 목록** API |
| 작업 목록·배정 추천 | 작업 ID 위주 | 상품명·칸·약속 시각을 한 번에 |
| 거절 응답 | `code`(영문) + `detail`(한국어 설명) | 화면 문구용 짧은 안내 필드(예: "추천 칸에 놓아 주세요") |
| 다음 작업 받기 | 없음 | 받은 작업의 상세(상품·칸·LOT·수량·약속 시각)를 한 번에 |

## 지금 쓸 수 있는 것
위 표의 "지금" 열 API 는 구현돼 있다(계약 `contracts/openapi/mfc-fulfillment-service.yaml` 0.4.7, `mfc-capacity-service.yaml` 0.5.2). 응답 모양은 계획이 구현될 때 **필드 추가만** 한다(기존 필드는 그대로).

## 추가(같은 날): 작업자 쪽 구현됨 — PDA·백오피스가 쓸 API
| 화면 | API |
|---|---|
| PDA "다음 작업 받기" | `POST /api/v1/mfcs/{mfcId}/workers/{workerId}/next-tasks`(본인) → 201 새 배정 / 200 들고 있는 작업 / 409 `NO_TASK_AVAILABLE`·`OFF_SHIFT`·`ON_BREAK` / 422 `DAILY_NOT_ENROLLED`. 응답 `Assignment{taskId, taskType(PICK·PACK·RETURN·REPLENISH·MOVE), source SYSTEM}` — PICK·PACK·RETURN 은 fulfillment `/tasks/{id}`, REPLENISH·MOVE 는 inbound `/move-orders/{moveId}` |
| PDA 휴게 | `POST …/workers/{workerId}/breaks`, `POST …/breaks/current/end` |
| PDA 작업 내놓기 | `POST /api/v1/tasks/{taskId}/releases {workerId, reason}` — 409 `EXECUTION_IN_FLIGHT`(반영 중) |
| 일일크루 출근 | `POST …/workers/{workerId}/day-enrollments`(본인 또는 감독자) |
| 백오피스 작업자 | 등록 `employmentType`·`qualification{taskTypes, expiresAt}`, `POST …/qualification-changes`, 응답 `effectiveTaskTypes`·`onBreak`·`todayEnrollment` |
| 백오피스 작업 감시 | fulfillment `GET /tasks` 의 `late`·`idle`·`lastActivityAt` |
| 이동 지시 | inbound 지시 본문 `urgent`, 응답 `assigneeId`·`pickedBy`, 거절 `NOT_ASSIGNEE`·`NOT_PICKER` |
- MFC 는 온도대 출입 자격이 없다 — 업무의 `temperatureZones` 는 선택(기록만).

## 추가(같은 날 밤): 응답 다듬기 구현됨 (fulfillment 0.4.9, capacity 0.9.1 — LAN 배포)
| 계획 | 구현 |
|---|---|
| 피킹 칸 상태 | `lines[].picks[]` 에 `pickStatus`(OK·PARTIAL·NOT_FOUND·DONE), `unallocatedQty`, `notice`(그대로 보일 안내 — 예 "어느 칸에서도 못 찾음 — 감독자 확인"). PICK 작업만 |
| 확인 필요 포장 목록 | `GET /api/v1/packages?mfcId={mfcId}&checkRequired=true` → `items[]` = HandoverResolution(+ `shipmentId`·`orderId`·`packingLocation`·`riderId`), 만료 오래된 순 |
| 작업 목록 | `GET /tasks` 항목에 `headline{productName, otherLineCount}`("상품명 외 N건") — 목록에도 `lineCount`·`plannedQty`·`completedQty`·`remainingQty` 채움. 칸은 목록에 없음(상세·다음 작업 받기에서) |
| 다음 작업 받기 상세 | 응답 `detail{available, promisedReadyAt, urgent, packingLocations[], lines[{sku, productName, lotId, expiresOn, qty, fromLocation, toLocation, notice}]}`. `available=false` 면 상세 API(`/tasks/{id}`·`/move-orders/{id}`)를 직접 부른다. 이동은 `productName` 없음 |
| 거절 응답 짧은 안내 | **안 함** — 오류 모양은 전 서비스 공통이라 바꾸지 않았다. 화면 문구는 `code` 로 매핑해 주세요(`detail` 은 한국어 설명). 꼭 필요하면 요청서로 |
