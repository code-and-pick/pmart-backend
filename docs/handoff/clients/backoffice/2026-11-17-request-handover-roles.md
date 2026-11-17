# 요청 — 교대 인계 화면 역할 맞추기 (case-service 0.13.1)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(교대 인계 화면)
상태: case PR 대기(병합·배포는 PC 1).

## 바뀐 것 (사용자 2026-11-17 "(나)")
- 인계서 작성 `POST /shift-handovers`: **지원센터 팀 DELIVERY_OPS = RIDER_OPS·OPERATOR_ADMIN, 관리자 팀 ORDER_OPS = OPERATOR_ADMIN 만**. 그 밖(상담원·센터 관리자 등)은 403. 건을 맡을(집을) 수 있는 사람과 같다.
- 수락 `…/acceptances`: 후임에게 그 팀 역할이 없으면 **403**(예전엔 200 + 건마다 SKIPPED_NOT_ALLOWED). 인계서는 PENDING 그대로 — 전임·관리자가 취소하고 다시 쓴다.

## 화면 요청
- 교대 인계 작성 버튼·메뉴는 위 역할에만 보이기(RIDER_OPS 는 팀 선택을 DELIVERY_OPS 로 고정).
- 후임 고르는 칸 옆 안내: "후임도 그 팀 역할(지원센터 팀 = 지원센터·관리자, 관리자 팀 = 관리자)이 있어야 받을 수 있습니다."
- 수락 403 이면 "이 팀 인계를 받을 역할이 없습니다 — 전임에게 다시 써 달라고 하세요".
