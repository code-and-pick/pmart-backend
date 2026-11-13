# employee-auth-service 에 요청

## EA-1 권한 조기 회수·종료 사건 (2026-11-13)

- **요청한 곳**: mfc-capacity(mfc 영역, PC 3) — 작업자 명단 ↔ 권한 연결(사용자 2026-11-13 "센터 관리자는 작업자와 별개").
- **필요한 것**: 직원 권한(grant)이 **기간 전에 끝날 때**(BO-01 권한 변경·회수, 센터 이동 때 앞 센터 권한을 일찍 끝냄 등) 그 사실을 알리는 사건 — 예 `GrantRevoked`(또는 `GrantEnded`) `{grantId, operatorId, roles, centerScope, endedAt, reason}`, 토픽 `platform.auth.v1`(`GrantApplied` 와 같은 곳). 기간대로 끝나는 것(`GrantApplied.effective.to`)은 사건이 없어도 된다 — mfc-capacity 가 기간을 저장해 맞춘다.
- **지금(임시 방식)**: mfc-capacity 는 `GrantApplied`(역할 MFC_WORKER)의 기간 `[effective.from, effective.to)` 만 안다. 권한이 일찍 회수돼도 작업자는 기간 끝까지 그 센터에서 ACTIVE 로 남는다(토큰에서 그 센터가 빠지므로 본인은 다음 작업 받기를 못 하지만, 센터 관리자 수동 배정·배정 추천에는 나올 수 있다). 센터 관리자가 작업자 상태를 직접 SUSPENDED 로 바꾸면 막힌다.
- **요청한 쪽에서 바꿀 것**: 사건이 생기면 `HrLinkService` 가 그 권한 행의 `valid_to` 를 `endedAt` 로 당기고 맞춘다(구독 필터에 사건 이름 하나 추가).
- **사용자 결정 필요**: 없음(회수 자체는 BO-01 확정 정책 — 백오피스 영역에서 구현될 때 같이).
- 근거: `services/mfc-capacity-service/CLAUDE.md`(작업자 명단 ↔ 인사·권한 연결), `docs/architecture/auth/employee-auth-structure.md`(센터 이동 = 소속만 바꾸고 권한은 A 종료·B 시작을 따로 요청).
