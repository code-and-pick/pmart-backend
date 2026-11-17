# [pmart-backend → pmart-clients] 회신: 고객앱 빠진 기능 — 배송 쪽 (2026-11-17)

- 보낸 영역: delivery(PC 5)
- 받는 앱: customer_app
- 근거: `docs/handoff/clients/customer-app/2026-11-17-notice-reference-gap.md`(PC 1) B·C 의 배송 항목. 사용자 결정 2026-11-17(PC 5 물음 넷).

| 항목 | 결정 | 백엔드 |
|---|---|---|
| 실시간 ETA·배송현황 | (요청 TR-3) | `GET /deliveries/{id}/eta`·`/stream`(SSE) — tracking 0.5.0 PR #75 |
| 라이더 위치 지도(C) | **픽업 뒤 정확한 위치** | `riderLocation`(lat·lng·recordedAt·staleSeconds) — 내 상품을 픽업한 뒤 ~ 끝나기 전만, 한 점(동선 없음). tracking 0.6.0(#75 위 PR) |
| 배송 지도의 매장 위치(B) | **보이지 않음** | 만들지 않음 — 지도는 도착지·라이더만 |
| 오배송 확인 — 전달 사진(B) | **본인 배송 완료 사진 공개** | `GET /deliveries/{id}/delivery-proof`(완료 뒤 놓은 곳·사진 참조) + `GET /deliveries/{id}/evidence-photos/{photoRef}` — execution PR #80. 라이더 통화는 없음(문의로 상담원이 연락) |
| 배달 지연 제보·미수령·오배송 신고(B) | **기존 문의로, 배송 연결** | 고객 문의 `POST /inquiries` 에 배송 ID·신고 종류(지연·미수령·오배송) — case PR(따로) |

- 응답은 서버 값을 그대로 보이면 된다. 사진은 `Cache-Control: no-store` — 앱에 저장하지 않는다.
