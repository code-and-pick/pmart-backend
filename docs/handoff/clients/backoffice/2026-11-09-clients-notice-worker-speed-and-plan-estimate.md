# [공지] 작업자 속도 배율·배정 추천 작업 시간 추정 (mfc-capacity 0.11.0, 2026-11-09)

센터 관리자 메뉴 "작업자"·"배정"(`docs/architecture/backoffice-role-menus.md`)에 반영해 주세요.

- **작업자 속도 배율**: `POST /api/v1/mfcs/{mfcId}/workers/{workerId}/speed-changes` `{speedFactor 0.5~2.0, reason}`(센터 관리자). 작업자 조회·상세에 `speedFactor`(기본 1.0). 배정 추천에서 그 작업자의 작업 시간 = 추정 분 ÷ 배율(예: 숙련 1.2, 신입 0.8). 실데이터가 없어 관리자가 정하는 값이라는 안내 권장. 다음 작업 받기(PDA)에는 영향 없음.
- **배정 추천 작업 시간**: 작업마다 다르게 추정한다(PICK·PACK 은 기준 7·4분을 평균으로 주문별 걷는 거리·수량 비율, 이동·보충·복귀는 도면 거리). 결과 `limitations` 에 `TRAVEL_ESTIMATED`(추정 시간 — 작업 사이 이동은 포함 안 함), 일부 못 했으면 `TRAVEL_ESTIMATE_PARTIAL`. 이전 `NO_TRAVEL_TIME`·`FIXED_TASK_MINUTES` 는 설정이 있으면 나오지 않는다. `settings.travel`(쓴 값)·`settings.workerSpeed`(1 이 아닌 작업자).
