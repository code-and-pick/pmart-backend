# 회신 — 목록 상태별 수(숫자 띠)

보낸 영역: backoffice
- 작성: pmart-backend PC 2, 2026-11-19
- 받은 요청: pmart-clients `docs/handoff/backoffice/2026-11-19-backend-list-status-counts.md`

## 질문 답
1. **bff 로 묶지 않고 각 서비스에 둔다.** 수는 DB 를 가진 서비스만 정확히 센다 — bff 는 DB 가 없어 결국 각 서비스를 다시 부를 뿐이고, 목록 화면 하나는 원천 하나만 부른다. 모양은 요청한 그대로 하나로(`{counts:[{key,count,capped}], asOf}`, 모든 상태 0 까지, 원천 실패 503).
2. **주문 수 상한**은 order 주인(PC 1)이 정해 요청서 O-9 에 적는다.

## 상태
| 목록 | 경로 | 담당 | 상태 |
|---|---|---|---|
| 라이더 컴플레인 | case `GET /rider-complaints/counts?riderId&deliveryId&from&to` → complaintType 별 | PC 2 | **구현(case 0.26.0, PR)** — 정확한 수라 capped 늘 false |
| 상품 조회 | product `GET /products/counts` | PC 1 | 요청 PD-1 |
| 주문 조회 | order `GET /orders/lookups/counts` | PC 1 | 요청 O-9 |
| 클레임 | claim `GET /claims/counts`(state, 가능하면 type) | PC 1 | 요청 CL-6 |
| 배송 확인 | execution `GET /deliveries/counts` | PC 5 | 요청 DE-7 |

- 클레임·라이더 컴플레인 목록은 bff `views/*` 를 쓰지만 수는 원천을 직접 부른다(라이더 컴플레인 = case).
