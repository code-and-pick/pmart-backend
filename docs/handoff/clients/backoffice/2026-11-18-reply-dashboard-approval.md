# 회신 — 대시보드 집계(D1~D5)·승인 "심사할 수 있나" 요청

보낸 영역: backoffice
- 작성: pmart-backend PC 2(백오피스 백엔드), 2026-11-18
- 받은 요청: pmart-clients `docs/handoff/backoffice/2026-11-18-backend-dashboard-metrics.md`, `2026-11-18-backend-approval-can-decide.md`
- 사용자 2026-11-18: "진행해"(아래 순서)

## 승인 "이 사람이 지금 심사할 수 있나"(R1·R2)
- approval-service 는 PC 1 담당 — 요청서 `docs/architecture/service-requests/approval-service.md` **AP-3** 로 옮겨 적었다. 구현되면 PC 1 이 알림. 그때까지 지금처럼(버튼 두고 403 을 거절 문구로).

## 대시보드
| 요청 | 회신 |
|---|---|
| D1 지표 501 칸 | 결품률(SHORTAGE_RATE)은 PC 3 분모 F-10, 정시 배송률(ON_TIME_DELIVERY)은 DD-4 결정 뒤. **PAYMENT_UNKNOWN 은 만들지 않음**(2026-11-18 결정 — 접수 건 현황·처리 대기에서 건수로 봄), TRANSPORT_ON_TIME·UNPLANNED_RATE 는 원천 서비스가 없어 501 그대로. 칸은 501 이면 "준비 중"으로 두면 됨 |
| D4 전체 건수 | **다음에 만든다(PC 2, backoffice-bff)** — 화면 번호(ADR-0070) bff 작업 다음. 버전·필드는 만들 때 알림 |
| D2 기간 주문 요약·D3 상품별 판매 상위·D5 센터별 재고 부족 | 숫자를 어떻게 셀지(결제 금액에 취소·환불 반영, 무엇을 "판매"로, 재고 부족 기준) 정의가 먼저 필요하고 원천 서비스(order·payment·mfc-inventory)에 집계 API 가 없다. PC 2 가 정의 추천안을 사용자에게 올려 정한 뒤 회신 |

받기 전까지 화면은 지금처럼(빈 0 칸·추정치 없음).
