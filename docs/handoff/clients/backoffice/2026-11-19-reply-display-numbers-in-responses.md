# 회신 — 클레임·라이더 컴플레인·문의 응답에 화면 번호 (bff 0.18.0)

보낸 영역: backoffice
- 작성: pmart-backend PC 2, 2026-11-19
- 받은 요청: pmart-clients `docs/handoff/backoffice/2026-11-19-backend-display-numbers-in-responses.md`
- 상태: 백엔드 PR(PC 1 검토·배포 전) — 병합 뒤 `gen:api`

## 질문 답
1. **클레임 주문번호 — bff 에서 묶는다.** claim-service 가 주문번호 사본을 들지 않는다(정본은 order — 사본이면 옛 클레임을 채우는 작업이 따로 필요). 라이더 컴플레인도 같은 방식.
2. **라이더 가린 이름 — case-service 가 들지 않는다.** rider 가 정본이고 라이더가 탈퇴하면 rider 가 이름을 지우는데, case 사본이면 남는다. bff 가 rider `GET /riders?riderIds=` 로 한 번에 붙인다.

## 새 화면 (원천 목록 그대로 + 번호)
| 화면 | 원천 | 더한 것 |
|---|---|---|
| `GET /views/claims?orderId&type&state&size&cursor` | claim `GET /claims`(0.6.0 — 사유·접수 경로·닫힌 시각·환불 요약 그대로) | `items[].orderNumber` |
| `GET /views/rider-complaints?riderId&deliveryId&complaintType&from&to&size&cursor` | case `GET /rider-complaints` | `items[].deliveryNumber`·`orderNumber`·`rider`(RiderDisplay)·`recordedByLabel`(OperatorLabel — 이름 · 사번) |

- 원천 목록은 직원 토큰으로 그대로 — 보이는 범위·403 은 원천 판정, 401·403·400 그대로. 원천 장애는 200 + 빈 items + unavailableSources.
- 번호를 못 읽으면 그 칸만 `null` + `unavailableSources`("번호 확인 불가"), 404 는 `null`(원천 정상 — "번호 없음"). 0·빈칸으로 바꾸지 않음.
- 라이더 컴플레인 원천의 `source`(접수 경로)는 `recordedVia` 로 이름만 바꿨다(bff 원천 표시 `sources` 와 헷갈리지 않게). 메모(note)는 싣지 않는다.
- size ≤ 50.

## 문의 상세 `GET /inquiries/{inquiryId}` — 지금은 화면이 직접
- 한 건이라 화면 요청이 2~3개(배송 상세 하나로 배송 번호·주문번호, 라이더는 rider 일괄)로 끝난다 — bff 화면을 따로 두지 않았다. 문의 목록에서도 번호가 필요해지면 같은 방식으로 `views/inquiries` 를 만든다(요청 주세요).

## 참고 — 서버 쪽 부담
- order·execution 에는 여러 ID 를 한 번에 읽는 조회가 없어 bff 가 **같은 ID 를 한 번씩**(쪽 크기 ≤ 50) 부른다. 브라우저 요청은 한 번이 되고 서버 안에서 묶여 화면에는 번호가 함께 온다. 목록이 커지면 order `orderIds`·execution `deliveryIds` 일괄 조회를 요청하겠다.
