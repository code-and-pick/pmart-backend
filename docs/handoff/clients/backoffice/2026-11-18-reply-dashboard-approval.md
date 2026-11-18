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
| D2 기간 주문 요약 | **사용자 결정(2026-11-18)**: 주문 확정일(한국 날짜) 기준 상태별 건수 + **결제 승인 금액 합**, 환불 금액은 환불 완료일 기준으로 **따로 나란히**(빼서 순매출을 만들지 않음 — 기준 날짜가 달라서). 원천 API 요청 order O-8·claim CL-3(PC 1) |
| D3 상품별 판매 상위 N | **사용자 결정**: 주문 확정 기준 — 확정 수량 − 출고 전 취소, 금액 같은 기준. 반품은 빼지 않음, 화면에 "주문 확정 기준" 표시. 요청 order O-8 |
| D5 센터별 재고 부족 | **사용자 결정**: 안전재고(Q-13)가 정해지지 않아 **"품절 SKU 수"**(가용 ≤ 0)로 바꿈 — 칸 이름도 "품절". 요청 mfc-inventory IV-5(PC 3) |

원천 API 가 생기면 PC 2 가 bff 에 잇고 버전·필드를 알린다.

받기 전까지 화면은 지금처럼(빈 0 칸·추정치 없음).
