# 알림 — bff 0.17.0 처리자·요청자 "이름 · 사번"(ADR-0070, HR-4)

보낸 영역: backoffice
- 작성: pmart-backend PC 2, 2026-11-19
- 받은 요청: pmart-clients `docs/handoff/backoffice/2026-11-18-backend-operator-display-names.md` R2(bff 화면 응답에 같은 표시)
- 상태: 백엔드 PR(PC 1 검토·배포 전) — 병합 뒤 `gen:api`

## 새 필드 — `OperatorLabel { operatorId, name, employeeNo }`
| 화면 | 필드 |
|---|---|
| 승인함 `/views/approval-inbox` | `items[].requestedByLabel`(요청자) |
| 처리 대기 `/views/pending-work` | `items[].lastRetry.operator`(MFC 최근 재시도한 직원) |
| 사건함 `/views/case-inbox` | `items[].assignee`(맡은 직원) |

- 이름·사번은 hr-service 값 그대로(가리지 않음). 목록에는 "홍길동 · P2026-00001", `operatorId`(OP-…)는 상세의 접힌 "시스템 번호" 칸에만(ADR-0070).
- `name` 이 null 이면 "확인 불가"(hr 실패면 `unavailableSources` 에 hr-service — 없음과 구분). 운영자 ID 가 없으면 칸 전체가 null(안 맡은 건 등).
- R1(화면이 hr `GET /hr/employee-labels` 를 직접 부르기)은 그대로 쓸 수 있다 — 승인 상세 결정자·이력 행위자·메모 작성자·열람 기록·교대 인계처럼 bff 를 거치지 않는 화면은 직접.
