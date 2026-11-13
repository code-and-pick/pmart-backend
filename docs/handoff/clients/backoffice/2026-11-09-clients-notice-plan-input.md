# [공지] 배정 추천 계산 입력 조회 (mfc-capacity 0.12.0, 2026-11-09)

센터 관리자 메뉴 "배정"(`docs/architecture/backoffice-role-menus.md`)에서 필요하면 써 주세요. 화면에 꼭 넣을 필요는 없습니다(문제 분석용).

- 배정 추천 결과(`POST/GET /api/v1/assignment-plans…`)에 `inputHash`(그 계획 계산 입력의 SHA-256) 추가. 입력이 보관 상한으로 지워졌으면 null. 지문이 같으면 같은 입력으로 계산한 것.
- `GET /api/v1/assignment-plans/{planId}/input` — 그 센터 운영자. 응답 `planId·inputHash·bytes·createdAt·input`(작업: 분·약속까지 분·우선순위·버전 / 작업자: 할 수 있는 유형·속도 배율·막힌 구간 / 패킹대 수·가중치·계산 시간 제한·구간 시작). 지워졌으면 404 `INPUT_NOT_KEPT`.
- 보관: 기간 없음. 총량이 2GB 를 넘으면 오래된 입력부터(확정 안 된 계획 먼저) 지웁니다. 계획 결과는 지우지 않습니다. 다시 계산(replay)은 아직 없습니다.
