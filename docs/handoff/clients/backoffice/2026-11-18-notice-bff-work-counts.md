# 알림 — bff 0.15.0 지금 일 건수(대시보드 D4)·라이더 번호 403

보낸 영역: backoffice
- 작성: pmart-backend PC 2(백오피스 백엔드), 2026-11-18
- 받은 요청: pmart-clients `docs/handoff/backoffice/2026-11-18-backend-dashboard-metrics.md` **D4 전체 건수**
- 상태: 백엔드 PR(PC 1 검토·배포 전) — 병합 뒤 `gen:api`

## 새 화면 `GET /backoffice/views/work-counts`(본사 관리자만 — 그 밖 403)
지금 시각 스냅샷. `counts[]` 한 줄 = `{ group, key, segment, value, complete, source }`.

| group | key | segment | 어디서 | 정확한가 |
|---|---|---|---|---|
| CASES | OPEN·UNASSIGNED·OVERDUE | 접수 건 종류 | case 현황 | 정확 |
| OBLIGATIONS | OPEN·OVERDUE | 담당 팀 | case 현황 | 정확 |
| SHIFT_HANDOVERS | PENDING | — | case 현황 | 정확 |
| INQUIRIES | AWAITING_ANSWER | CUSTOMER·RIDER | case 현황 | 정확 |
| PENDING_WORK | COUNT | 처리 대기 kind | 처리 대기와 같은 모음 | MFC 목록이 잘리면 아님 |
| APPROVALS | REVIEW_PENDING·EVIDENCE_REQUIRED | 승인 종류 | 승인 목록 쪽 넘김(2,000건까지) | 넘으면 아님 |
| DELIVERIES | ACTIVE | 배송 상태(끝나지 않은 9개) | 배송 목록 쪽 넘김(상태마다 500건까지) | 넘으면 아님 |

- **`complete=false` 면 "N건 이상"** 으로 보여 주세요(쪽 상한에 걸림 — 적어도 이만큼).
- 0건이면 줄이 없을 수 있습니다(사건·후속 처리·문의·배송 상태). 없는 줄은 0으로 보이면 됩니다 — 단 그 묶음의 원천이 `unavailableSources` 에 있으면 0이 아니라 "모름".
- 원천 실패: 그 묶음 줄이 통째로 빠지고 `unavailableSources` 에 원천 이름(case-service·approval-service·delivery-execution-service·order-orchestration-service 등). 화면은 200.
- **승인 수는 심사 대기 전부**입니다(보는 사람이 심사할 수 있는 것만이 아님) — approval AP-3(`decidableByMe`)이 생기면 바꿉니다.
- **늦은 배송 수는 없습니다**(배송마다 도착 예정을 읽어야 해서) — 실시간 배송 현황에서 보세요.

## 라이더 번호(0.14.0) 바뀐 점
- rider-service 403(이 직원은 라이더를 못 봄 — 예: 센터 관리자)은 이제 장애가 아닙니다 — 번호·이름만 null, `unavailableSources` 에 rider-service 가 들어가지 않습니다. 실패(연결·5xx)는 그대로 unavailableSources.
