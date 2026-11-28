# ES 결과를 믿기 어려울 때 하이브리드로 넘어가는가 — 실제 사슬 (2026-11-28, PC 1)

사용자: "실제 es에 검색 결과가 신뢰성이 떨어질때 RAG 시스템으로 하이브리드가 수행되는지 테스트 해봐."

- 자동 시험 `services/rag-query-service/src/integrationTest/.../HybridFallbackIT`(실제 ES 8.18+nori + embedding-service 흉내): 확실한 질문 → ES 끝·벡터 호출 0 / 바꿔 쓴 질문(핵심어 비율 낮음) → 벡터 1번·BM25 가 못 찾은 조항이 근거로 / 벡터도 먼 질문 → 답변 불가. 3개 통과.
- 실제 사슬: ES·pgvector 컨테이너 + integration·chunking·embedding·rag-query jar + LAN PC 2 Ollama, pmart-all-docs 1725acb 문서 88 적재(847 청크). 2차 기록에서 ES 신뢰도가 낮았던 14문항(`hybrid-live-pick.json` — BM25 3위 안에 근거 없음 6·BM25 1위 틀림 4·근거 없음 4)을 `POST /api/v1/internal/rag-searches` 로.
- 'ES 만(상위 3)' = ES 에서 끝냈다면 돌려줬을 카드 3개(가중치 정렬)에 근거가 있었는지. '최종 근거' = rag-query-service 가 실제로 돌려준 것.

| 구분 | 문항 | ES 만(상위 3) 근거 | 판정(이유) | 벡터 호출 | 끝난 곳 | 최종 근거 | 최종 근거 맞음 |
|---|---|---|---|---|---|---|---|
| BM25 3위 안에 근거 없음 | A15 보충 물량은 어느 CDC에서 몇 곳의 MFC로  | X 03-replenishment-transfe PMart CDC 선반 보충·MFC  | coverage 0.6 < 0.7 | 1 cos 0.755 | HYBRID | 03-replenishment-transfe 제1조 (목적), 03-replenishment-transfe 제3조 (한 요청은 한 번만 출발) | X |
| BM25 3위 안에 근거 없음 | B04 내가 요청한 변경을 내가 승인할 수 있나요? | X 03-approval.md 제2조 (승인이 필요한 변경과 승인자 | relGap 0.0348 < 0.1 | 1 cos 0.781 | HYBRID | 03-approval.md 제2조 (승인이 필요한 변경과 승인자, 03-approval.md 제4조 (요청과 제출) | O |
| BM25 3위 안에 근거 없음 | P01 라면 반품 기준이 뭐야? | X 06-return-exchange.md 제3조 (반품 접수) | coverage 0.3333 < 0.7 | 1 cos 0.558 | NO_ANSWER | — | X |
| BM25 3위 안에 근거 없음 | C16 다른 집 주문이 저희 집에 왔어요 | X 10-address-area.md 우리 집도 배송되나요? [채택 설계] | coverage 0.3333 < 0.7 | 1 cos 0.574 | NO_ANSWER | — | X |
| BM25 3위 안에 근거 없음 | OG02-1 PDA는 얼마 동안 안 쓰면 다시 로그인해야 하 | X 05-reviews.md 제4조 (삭제와 다시 쓰기) | coverage 0.25 < 0.7 | 1 cos 0.685 | HYBRID | 01-staff-account-login.m 제6조 (로그인 유지), 01-staff-account-login.m 제7조 (로그아웃) | O |
| BM25 3위 안에 근거 없음 | OG02-2 현장 작업 단말기 자동 로그아웃 시간이 몇 분이 | X 02-task-assignment.md 제6조 (지연·무활동 표시) | coverage 0.4286 < 0.7 | 1 cos 0.648 | HYBRID | 01-staff-account-login.m 제7조 (로그아웃), 01-worker-qualification- 제8조 (휴게) | X |
| BM25 1위 틀림 | A05 분할 배송 기준 무게는 얼마인가요? | O 01-service-area.md 제4조 (배송 가능 판정의 기준) | coverage 0.3333 < 0.7 | 1 cos 0.715 | HYBRID | 01-order-amount.md 제10조 (나눠 배송), 01-service-area.md 제5조 (배송 조건) | O |
| BM25 1위 틀림 | A19 MFC 피킹 방문 순서는 온도대별로 어떻게 정해 | O 02-pricing-promotions.md 제3조 (가격이 정해지지 않은 판매  | coverage 0.2857 < 0.7 | 1 cos 0.716 | HYBRID | 04-picking-packing.md 제2조 (피킹 작업), 02-dispatch-planning.md 제7조 (여러 센터 방문) | O |
| BM25 1위 틀림 | E01 쿠폰을 쓴 주문에서 한 상품이 결품되면 환불은  | O 02-pricing-promotions.md 제10조 (쿠폰의 사용·해제·복원) | coverage 0.6667 < 0.7 | 1 cos 0.799 | HYBRID | 14-refund.md 제7조 (쿠폰을 쓴 주문), 02-pricing-promotions.md 제10조 (쿠폰의 사용·해제·복원) | O |
| BM25 1위 틀림 | C02 주문 취소는 언제까지 할 수 있어요? | O 08-return-exchange.md 언제까지 신청할 수 있나요? [확정  | coverage 0.6667 < 0.7 | 1 cos 0.713 | HYBRID | 08-order-cancel.md 제2조 (취소할 수 있는 시점), 02-cancel.md 상품 준비가 시작된 뒤 취소 문의를  | O |
| 근거 없음 질문 | A08 주문 사가는 오케스트레이션인가요 코레오그래피인가 | —(근거 없음) 04-earnings.md 제8조 (이의 신청) | coverage 0.1667 < 0.7 | 1 cos 0.531 | NO_ANSWER | — | O |
| 근거 없음 질문 | A10 컨테이너 이미지는 어느 레지스트리에 올리나요? | —(근거 없음) 06-product-display-searc 제7조 (고객용 상품 콘텐츠) | coverage 0.25 < 0.7 | 1 cos 0.512 | NO_ANSWER | — | O |
| 근거 없음 질문 | A11 서비스 설정의 정본은 어디에 두나요? | —(근거 없음) 06-customer-notification 제5조 (수신 설정) | coverage 0.25 < 0.7 | 1 cos 0.487 | NO_ANSWER | — | O |
| 근거 없음 질문 | A12 서비스 간 내부 호출에도 인증 토큰이 필요한가요 | —(근거 없음) 03-approval.md 제2조 (승인이 필요한 변경과 승인자 | coverage 0.1667 < 0.7 | 1 cos 0.463 | NO_ANSWER | — | O |

- 근거 있는 질문 10: ES 만(상위 3) 맞음 4 → 하이브리드 뒤 맞음 6
- 근거 없는 질문 4: 답변 불가로 끝남 4
- ES 에서 끝난 것 0 · 벡터 호출 14/14

## 읽는 법
- **전환은 됐다**: 14문항 모두 판정에서 걸려(핵심어 비율 < 0.7 또는 상대 점수 차 < 0.1) ES 에서 끝나지 않고 벡터 검색을 1번씩 불렀다.
- **벡터가 근거를 되찾은 것**: B04(자기 승인 금지 — 승인 정책)·OG02-1(PDA 30분 — 로그인 유지 제6조). BM25 상위 3 에 없던 근거가 최종 근거로 들어왔다.
- **BM25 1위가 틀렸던 4개**(A05·A19·E01·C02): ES 카드 3개 안에도 근거가 있었고, 하이브리드 뒤에도 근거가 유지됐다.
- **근거 없는 4개**: 모두 답변 불가로 끝났다(벡터 1위 코사인 0.46~0.53).
- **못 한 것**: A15(보충 운송 — 같은 문서 제1조·제3조가 나옴), OG02-2(단말 자동 로그아웃 — 제7조 로그아웃이 나옴, 정답 제6조), P01·C16 은 근거가 있는데 벡터 1위 코사인 0.558·0.574 가 기준 0.58 바로 아래라 **답변 불가로 잘못 끝남** — 2차 검증에서 본 '기준 근처 답변 불가 판정이 흔들림'과 같은 문제.
