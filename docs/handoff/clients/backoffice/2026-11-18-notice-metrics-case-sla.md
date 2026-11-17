# [pmart-backend → pmart-clients] 알림: 지표 화면 — 접수 건 기한 준수(CASE_SLA) (2026-11-18)

- 보낸 영역: backoffice(PC 2)
- 대상: `web/backoffice`(본사 관리자)
- 계약: `backoffice-bff.yaml` 0.13.0 `GET /views/metrics`(원천 `case-service.yaml` 0.24.0 `GET /cases/metrics/sla`)
- 근거: 사용자 2026-11-18 "일단 그렇게 하고"(추천안), 업무 원문 BO-28 — 분모를 줄여 비율을 올리지 않는다, 분모 0 은 N/A, 승인된 정의만.

## 1. 되는 것
`GET /views/metrics?metric=CASE_SLA&from=YYYY-MM-DD&to=YYYY-MM-DD[&granularity=DAY|HOUR][&centerId=]` — **본사 관리자만**(그 밖 403).
- 기간 안에 **처리 끝난(완료 처리된) 접수 건**을 처리 끝낸 한국 시각으로 묶는다(DAY `2026-11-18`, HOUR `2026-11-18T13:00`). 다시 처리하기 뒤 다시 끝냈으면 마지막으로 끝낸 시각과 그때 기한으로 판정.
- `points[]`: 묶음 × `segment.kind`(사건함 종류) × `segment.assigneeGroup`(끝낼 때 팀) — `numerator` 기한 안에 끝냄, `denominator` 기한이 있던 건, `unjudged` 기한이 없던 건(판정 불가), `ratio`(분모 0 이면 null).
- `totals[]`: 묶음별 합. `currentOverdue[]`: **지금** 처리 중인데 기한이 지난 건(종류별 — 기간과 무관). 그 조회만 실패하면 null + `unavailableSources`.
- 기간: DAY 93일, HOUR 7일까지(넘으면 400 — detail 문구 그대로 보여 주면 됨).

## 2. 화면에 부탁
- 비율만 보이지 말고 **"N건 중 M건"**을 같이(분모가 작으면 비율이 크게 흔들림).
- `ratio` null 은 **"—"(N/A)**, 0% 나 100% 로 그리지 않는다. `unjudged` 가 있으면 "기한 없음 K건"을 따로.
- 종류마다 기한이 달라(배차 실패 10분, 결제 이상 1시간 등) 한 숫자로만 보이지 말고 종류·팀으로 나눠 볼 수 있게.
- 503 은 "접수 건 원천에 닿지 않았어요"(0건이 아님). LAN 데이터는 시험·시뮬레이터라 운영 성과처럼 읽히지 않게.

## 3. 아직 안 되는 지표 — 501 NOT_IMPLEMENTED
SHORTAGE_RATE(결품률 — 줄 단위·사유별로 정했고 MFC 쪽 분모 대기), ON_TIME_DELIVERY(정시 배송률 — 누가 셀지 결정 전), TRANSPORT_ON_TIME·UNPLANNED_RATE(원천 서비스 없음), PAYMENT_UNKNOWN(만들지 않음 — 처리 대기·사건함에서 본다). 메뉴에는 CASE_SLA 만 두거나 나머지를 "준비 중"으로.
