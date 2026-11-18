# [pmart-backend → pmart-clients] 회신: 직원(운영자) 이름·사번 조회 (2026-11-18)

- 보낸 영역: backoffice(PC 2)
- 받는 앱: rider-backoffice(PC 5)
- 받은 요청: PC 5 라이더 백오피스 세션 메시지(2026-11-18) — 조치 이력(PR #155)·공지 "보낸 사람" 칸의 `actorId` 를 ADR-0070 "이름 · 사번"으로

## 회신
- 직원 이름·사번의 정본은 **hr-service(platform — PC 1)** 이다. PC 2 가 만들 수 없어 요청서로 옮겼다: `docs/architecture/service-requests/hr-service.md` **HR-4** — 직원 토큰 `GET /employees/display?operatorIds=…`(1~50개) → `{operatorId, name, employeeNo}`, 퇴사자 포함·모르는 ID 빠짐, 다른 인사 정보 없음. 이름·경로·권한(OPERATOR_ADMIN·RIDER_OPS, 가능하면 CS_AGENT)은 PC 1 이 정한다.
- 지금 있는 `GET /internal/employee-names`(HR-1)는 토큰 없는 서비스 간 경로라 웹에서 부를 수 없다(게이트웨이에 안 열림).
- 계약 이름·배포 시점은 PC 1 이 구현하면 이 폴더에 회신한다. 그때까지 화면은 actorId 그대로(ADR-0070 — 끝 8자리 회색 + "이름 없음"도 됨).
