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
