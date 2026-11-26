보낸 영역: cdc(PC 1 D 클론 — CDC 세션)
받는 앱: cdc-backoffice
원 문서: pmart-clients `docs/handoff/cdc-backoffice/2026-11-26-backend-kpi-workforce.md`(KP-1·KP-2·WF-1·WF-2)
작성: pmart-backend, 2026-11-26
상태: KP-2·WF-1 **구현(cdc-outbound 0.27.0) — LAN 배포 전**, KP-1·WF-2 는 뒤 PR(아래 덧붙임)

# 회신: 리포트·KPI·작업자·인력

사용자 결정(2026-11-26): KP-1 은 **있는 자료만 계산하고 나머지는 N/A**, WF-2 는 **시뮬레이션에서 정한 대로**(근무조·인원 계획).

## KP-2 `GET /cdcs/{cdcId}/outbound-summary/daily?from=&to=` (cdc-outbound, 자기 센터 운영자)
- from·to 필수(서울 날짜, to 포함), 최대 31일(넘거나 거꾸로면 400). 응답 `{items: OutboundSummary[]}` — 날짜 순, 줄마다 지금 `outbound-summary?date=` 와 같은 값(transferCount·상태별·승인·할당·피킹·부족·progress·Wave·작업 상태별·byMfc).

## WF-1 `GET /cdcs/{cdcId}/worker-throughput?from=&to=` (cdc-outbound, 자기 센터 운영자)
- 끝난 시각(completedAt — 서울 날짜)이 [from, to] 인 작업(DONE·PARTIAL)의 담당자별, 작업자 ID 순. 생략 = 오늘, 최대 31일.
- 줄: workerId·tasks·tasksByKind[{key, count}]·pickedLines(실피킹 > 0 줄)·pickedQty(낱개 — 단위 섞지 않음)·shortLines·shortQty·firstStartedAt·lastCompletedAt·mainFloor·mainZone(가장 많이 한 층·구역).
- 작업 기록에서 계산합니다(새 정본 없음). 근무 시간·가동률은 WF-2 근무 기록이 생긴 뒤.
- 작업자 이름은 없습니다(ID 만) — 이름은 직원 정보(hr)에서.

## 덧붙임 — KP-1 운영 확인 기준(cdc-outbound 0.28.0·cdc-inventory 0.21.0, PR `cdc/operating-metrics`)
`GET /cdcs/{cdcId}/operating-metrics?from=&to=&mfcId=` (자기 센터 운영자, from·to 필수 — 서울 날짜, 최대 31일)
- 평가 대상 = 기한(dueAt)이 그 기간인 TRF. 응답 `{from, to, mfcId, definitionVersion: "CDC-F-034/v1-provisional", asOf, evaluatedTrfCount, items[6]}` — 항목은 늘 같은 순서.
- 줄 = `{key, label, status(VALUE·NA), numerator, denominator, value, unit(EA_MIXED_SKU·COUNT·MINUTES), provisional, reasons[], components[], sources[](계산에 쓴 TRF ID — 원천 추적)}`.
  | key | 지금 |
  |---|---|
  | APPROVED_DEMAND_FULFILLMENT 승인 수요 충족률 | 계산 — 마지막 수령 반영 ≤ dueAt 인 정상 수령 ÷ 승인 합(취소 TRF 포함 — 분모를 줄이지 않음). 늘 잠정(MIXED_SKU, 수령 전이면 OPEN_RECEIPTS) |
  | ORIGINAL_DEMAND_FULFILLMENT 원수요 충족률 | NA — 원수요(replenishment) 원천 없음 |
  | NORMAL_RECEIPT_RATE 정상 수령률 | 계산 — 정상 ÷ (정상 + 격리 + 미판정). 받은 게 없으면 NA ZERO_DENOMINATOR, 미판정 있으면 PENDING_QUALITY |
  | READY_TIME 사용 준비 시간 | NA — MFC 사용 준비 확인 원천 없음 |
  | PROMISE_COMPLIANCE 약속 준수 | NA — 사용 준비와 함께 비교해야 해서(부분 비교를 합치지 않음) |
  | EXCEPTION_VOLUME 예외 운영량 | 건수 — components DEPARTURE_MISMATCH·PICK_SHORTAGE·TRANSIT_UNRECONCILED(VALUE), DEADLINE_LOSS·URGENT_SHARE(NA) |
- 화면: status NA 면 값 대신 reasons 를 보여 주세요(N/A 와 0 을 구분). provisional 이면 "잠정". BI·목표 수치가 아닙니다.
- MFC 수령 사건(PC 3)이 아직 없어 수령은 복구용 API 로 들어온 것만 잡힙니다 — LAN 에서는 승인 수요 충족률이 거의 0·잠정으로 보일 수 있습니다.

## 덧붙임 — WF-2 근무(cdc-outbound 0.29.0, PR `cdc/workforce`)
사용자 "시뮬레이션에서 정한대로 해" — 근무조·계획 인원은 `pmart-cdc-simulation` 설정값([시뮬]·[가정])을 그대로 시드했습니다. 계획 인원은 **참고값**이라 배치·출근을 막지 않습니다.
- `GET /cdcs/{cdcId}/shifts` → `{shifts[{shiftCode, name, startTime, endTime(시작보다 이르면 다음 날), breaks["HH:mm-HH:mm"], source}], plan[StaffingCell]}`
  - 칠곡: DAY 주간조 08:00~17:00 · EVENING 석간조 13:00~22:00(휴게 15:30-15:45·18:00-19:00·20:30-20:45) · NIGHT_DOCK 야간 도크 22:00~04:30.
  - 계획(층 × 직무): 5F FC 11·SORT 13·JOCKEY 8·DPS 15·INSPECTOR 4(석간), REPLENISH 주간 20·석간 3, NIGHT_JOCKEY 4 / 6F FC 14·SORT 9·JOCKEY 4·INSPECTOR 4, REPLENISH 14·3, NIGHT_JOCKEY 4 / 7F FC 19·SORT 6·JOCKEY 7·INSPECTOR 4, REPLENISH 16·3, NIGHT_JOCKEY 4 / 도크 LOADER 22(층 ALL).
- 직무 `WorkRole`: FC·SORT(DAS 라인·분배)·JOCKEY·REPLENISH·DPS·INSPECTOR·NIGHT_JOCKEY·LOADER. 층 5·6·7·ALL.
- 배치 `POST /cdcs/{cdcId}/shift-assignments {workDate, shiftCode, workerId, floor, role}`(CDC_SUPERVISOR, Idempotency-Key·X-Source-System) — 날짜·근무조·작업자마다 하나, 출근 전이면 바꾸고 출근 뒤면 409 CHECKED_IN. 목록 `GET …/shift-assignments?date=&shiftCode=`.
- 출근·퇴근 `POST …/shift-assignments/{id}/check-ins`·`/check-outs`(작업자 본인 또는 관리자, 다시 보내면 그대로, 출근 전 퇴근 409 NOT_CHECKED_IN).
- 현황 `GET /cdcs/{cdcId}/staffing?date=` → `cells[{shiftCode, floor, role, planned, assigned, checkedIn}]` — 계획 칸 + 계획 밖 배치 칸(planned 0). "지금 출근" = 출근했고 퇴근 전.
- 작업자 이름은 없습니다(ID 만). 작업 받기에 근무·자격 검사는 아직 없습니다.

