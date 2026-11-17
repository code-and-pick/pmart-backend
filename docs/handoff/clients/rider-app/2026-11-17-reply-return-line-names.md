# 회신 — 회수 품목 이름 짝짓기

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app` — "반품 줄에는 이름이 없고 주문 품목에는 줄 번호가 없다"
- 상태: 백엔드 PR(order 0.11.1 — platform 영역 PC 1 검토, execution 0.14.3). `gen:api` 다시

## 답
- 맞다 — 앞서 "이름은 orderSummary.items 의 같은 orderLineId"라고 안내했지만 **주문 요약 품목에 orderLineId 가 없었다**(백엔드 쪽 잘못된 안내).
- 고침:
  1. **`returnLines[].name`·`optionLabel`** — 배송 조회 `GET /deliveries/{id}` 때 서버가 주문 요약에서 같은 주문 줄의 상품명을 채운다. **앱은 짝짓지 말고 이 값을 그대로** 보이면 된다. 주문 요약을 못 읽으면 null → 지금처럼 "상품 1 × 2".
  2. `orderSummary.items[].orderLineId` 도 더함(다른 화면에서 쓸 수 있게).
- 순서대로 이름을 붙이는 방식은 빼 주세요 — 주문 요약은 보낼 수량 0 인 줄을 빼므로 줄 수가 같아도 순서가 어긋날 수 있다.
- 전달 실패 반납(FAILED_DELIVERY_RETURN)·오배송 회수는 `returnLines` 가 없다 — 받을 품목 = 원래 주문의 `orderSummary.items` 전부(이름 있음).
