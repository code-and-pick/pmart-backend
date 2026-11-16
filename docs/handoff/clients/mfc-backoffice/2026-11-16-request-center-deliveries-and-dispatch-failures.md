# [pmart-backend → pmart-clients] 요청: MFC 백오피스 — 우리 센터 배송 현황·배차 실패 사건 (2026-11-16)

- 보낸 영역: backoffice
- 받는 앱: mfc-backoffice
- 결정: 사용자 2026-11-16 "①②③ 추천대로" — 센터 관리자(MFC_SUPERVISOR)가 자기 센터의 실시간 배송 현황과 배차 실패 사건을 본다. 근거 배송 원문 §1(점포 관리자 — 준비 지연 대응·추가 배차 요청), §3.6(배차 지연·불가는 "MFC 관리자·고객센터에 안내").
- 계약: `backoffice-bff.yaml` **0.3.1**, `case-service.yaml` **0.5.2**. 백엔드 PR(backoffice/mfc-supervisor-views), LAN 배포 전.

## 1. 우리 센터 실시간 배송 현황
- `GET /api/v1/backoffice/views/live-delivery?state=&size=&cursor=` — 백오피스와 같은 API(응답 모양은 `backoffice/2026-11-16-request-live-delivery.md` §1).
- 센터 관리자 토큰이면 **센터가 자기 센터로 고정**: mfcId 를 비우면 하나뿐인 자기 센터, 다른 센터를 주면 403, 소속 센터가 여럿이면 mfcId 를 골라야 함(400).
- openCases 는 그 관리자에게 보이는 사건만(아래 §2 — 자기 센터 배차 실패).

## 2. 우리 센터 배차 실패 사건(보기만)
- `GET /api/v1/cases?kind=DISPATCH_FAILED`(state 비우면 열린 것, `state=CLOSED` 면 닫힌 것 최근 순) · `GET /api/v1/cases/{caseId}`.
- 센터 관리자는 **자기 센터의 DISPATCH_FAILED 만** 보인다. 다른 센터 사건은 404. **집기·닫기는 없다(403)** — 처리는 지원센터(RIDER_OPS).
- 사건 요약 예 "배차 실패 2번째 — 응답 만료 (10분마다 자동 재배차 중)", refs deliveryId·orderId, 처리 기한(slaDueAt·overdue) 10분 표시.
- **주의**: 지금 배차 쪽 사건(`DispatchFailed`)에 센터가 실리지 않아 **배포돼도 당분간 0건**이다 — 배송 영역에 요청함(`service-requests/delivery-dispatch-service.md` DD-MFC). 실리기 전 사건은 계속 안 보인다.

## 3. 화면 제안
- MFC 백오피스 메뉴 "배송 현황"(센터 고정, 상태 고르기, 1분 이상 새로 고침) — 표 열은 백오피스와 같게.
- 같은 화면 또는 따로 "배차 실패" 목록(열린 것·닫힌 것), 상세는 보기만(집기·닫기 버튼 없음) + "처리는 지원센터" 안내.
- 직원 알림은 없다(ADR-0064) — 목록을 본다.
