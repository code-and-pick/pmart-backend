# [공지] 배정 추천 결과 값 추가 (mfc-capacity 0.9.4, 2026-11-09)

센터 관리자 배정 화면(`docs/architecture/backoffice-role-menus.md` 3장 "배정")이 보여 줄 값이 늘었습니다. 계약 `contracts/openapi/mfc-capacity-service.yaml` 0.9.4.

- `state`: 기존 PROPOSED·COMMITTED·NO_SOLUTION·STALE 에 **UNDETERMINED**(계산이 시간 안에 판단 못 함 — 불가능하다는 뜻이 아님)·**INVALID**(계산 입력 오류) 추가. 확정 버튼은 PROPOSED 만.
- `unassigned[].reason`: **UNDETERMINED** 추가.
- `limitations`: **RULE_BASED_FALLBACK**(엔진이 시간 안에 해를 못 내 규칙 기반 해를 낸 추천 — "최적화 안 됨" 안내 권장), **IN_PROGRESS_ESTIMATED_BY_PROGRESS**(진행 중 작업의 남은 시간을 진척률로 추정). `ZONE_NOT_CHECKED` 는 더 나오지 않음.
- 계산은 지금 늘 약 5초 걸립니다(시간 제한) — 계산 중 표시를 해 주세요.
