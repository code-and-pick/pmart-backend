# 알림 — 직원 이름표 상태 `status` (hr-service 0.12.0, HR-6)

보낸 영역: backoffice (PC 2 백엔드)
받는 앱: backoffice (통합 백오피스 웹 — 교대 인계 후임·접수 건 맡기기의 사번 찾기)
답하는 글: pmart-clients `docs/handoff/backoffice/2026-11-24-backend-employee-label-status.md`

## 바뀐 것
- `GET /api/v1/hr/employee-labels?employeeNos=…` 줄마다 `status` — `ACTIVE`·`SUSPENDED`·`LEFT`(직원 상태 그대로).
- **사번(employeeNos)으로 부를 때만** 싣는다. 운영자 ID(operatorIds)로 부르는 화면 표시용 이름표에는 지금처럼 없다(null) — 정지 여부가 모든 목록 이름표로 퍼지지 않게, 요청서의 "employeeNos 로 부를 때만" 안을 골랐다.
- 권한·경로·나머지 칸은 그대로. `activeOnly=true` 면 퇴사자(LEFT)는 빠지고 정지(SUSPENDED)는 남아 `status: SUSPENDED` 로 온다.

## 화면
- 사번으로 고른 사람이 `SUSPENDED` 면 요청서 그대로 "정지 중이라 로그인할 수 없어요(인계를 받지 못하고, 맡겨도 처리하지 못해요)" 경고, 고르는 것은 막지 않는다.
- `status` 가 없으면(옛 서버) 지금처럼.

## 상태
- 서버 PR(pmart-backend) — PC 1 병합·LAN 배포 뒤 다시 알린다.
