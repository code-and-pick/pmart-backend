# 요청 — 읽음은 서버에, 사건 알림은 화면 배지로 (case 0.20.0 · bff 0.10.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(사건함·도움요청 대화 — 지원센터·상담원·관리자)
상태: 백엔드 PR 대기(병합·배포는 PC 1).

## 정한 것 (사용자 2026-11-17 "가가가나")
1. **직원별 읽음 = 서버**: 지금 탭 sessionStorage 로 기억하던 "본 시각"을 case 에 둔다(탭을 닫아도·기기를 바꿔도 유지).
2. **사건 알림 = 화면 안 배지**: 새 백엔드 없이 기존 API 를 1분 폴링. 화면을 연 사람에게만(푸시·알림함 없음).
3. 다른 서비스 지표 — 지금은 안 함. 4. 역할별 메뉴 확정 — 계속 미룸.

## 1. 읽음 API
- 접수 건(또는 그 도움요청 대화)을 열 때 `POST /api/v1/cases/{caseId}/views` (본문 없음, Idempotency-Key 필요 없음) → `{ caseId, lastSeenAt }`. 못 보는 건 404.
- 목록·상세에 `myLastSeenAt`(부른 직원 자신의 값, 본 적 없으면 null):
  - case `GET /cases`, `GET /cases/{id}`
  - bff `GET /backoffice/views/case-inbox` 항목
- **새 메시지 점** = 사건함 항목 `linked` 의 HELP_REQUEST `lastRiderMessageAt` 이 있고 `myLastSeenAt` 이 null 이거나 그보다 이르면. sessionStorage 는 빼도 된다.

## 2. 사건 알림 배지 (백엔드 변경 없음)
- 백오피스 상단(또는 사건함 메뉴)에 배지 — 1분마다(화면이 열려 있고 보이는 동안만):
  - 아무도 안 집은 건: `GET /api/v1/cases?unassigned=true&size=50` 의 items 수(50 이상이면 "50+")
  - 기한 지난 건: `GET /api/v1/cases?overdue=true&size=50`
  - 역할별로 보이는 범위는 case 가 정한다(지원센터 = 배송 사건, 관리자 = 전부, 센터 관리자 = 자기 센터 배차 실패).
- 새로 늘었을 때만 잠깐 강조(소리·브라우저 알림 없음). 관리자는 `GET /cases/metrics` 를 써도 된다.
