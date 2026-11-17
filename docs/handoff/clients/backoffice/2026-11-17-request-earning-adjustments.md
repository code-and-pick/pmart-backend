# 요청 — 라이더 운영: 산정 불가 수행료 조정 화면

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 받는 곳: pmart-clients backoffice 웹(라이더 운영 — RIDER_OPS·OPERATOR_ADMIN, 상담원은 보기만)
- 계약: `delivery-execution-service.yaml` 0.13.0 — `gen:api` 다시
- 상태: 백엔드 PR 단계. 게이트웨이 경로(`/api/v1/rider-earnings/**`)는 PC 1 이 더한 뒤 열린다

## 무엇인가
전달 실패 확정·운영자 배차 취소 등으로 라이더 수행료가 **"산정 불가(지원센터 조정)"** 로 남는다. 지금까지 조정 방법이 없어 라이더에게 지급도, 탈퇴도 안 됐다.
사용자 결정(2026-11-17): **귀책만 고르면** 서버가 정한다 — 라이더 귀책이면 미지급, 고객·점포·PMart 귀책이면 **수락 당시 금액(거리별 금액)**을 **바로 확정**, 지급 예정일 = 조정한 날 + 3영업일.

## API
| | |
|---|---|
| 목록 | `GET /api/v1/rider-earnings/adjustment-queue?riderId=&size=&cursor=` → `items[]`(Earning — earningId·deliveryId·riderId·note·recordedAt…), `page.nextCursor`. 오래된 것 먼저. RIDER_OPS·OPERATOR_ADMIN·CS_AGENT |
| 조정 | `POST /api/v1/rider-earnings/{earningId}/adjustments`(Idempotency-Key, X-Source-System) `{fault: RIDER|CUSTOMER|STORE|PMART, reason: 2~300자}` → `{adjustmentId, decision: PAY|NO_PAY, fault, earning}`. RIDER_OPS·OPERATOR_ADMIN 만(상담원 403) |
| 오류 | 400(사유·귀책), 403, 404 EARNING_NOT_FOUND, 409 NOT_ADJUSTABLE(이미 조정·산정됨 — 조정은 한 번), 409 FEE_UNKNOWN(수락 금액 모름 — 지급으로 못 바꿈) |

## 화면 제안
- 라이더 운영 메뉴에 "수행료 조정 대기" 목록 — 배송 ID 를 누르면 배송 상세(실패 사유·사진·도움요청)로 가서 귀책을 판단.
- 조정 창: 귀책 4개 중 하나 + 사유(필수). 고르기 전에 "라이더 귀책 = 미지급, 그 밖 = 수락 당시 금액 지급" 안내. 금액 입력 칸은 없다.
- 사유는 직원만 본다(라이더에게는 결과만 — 알림함·정산 내역 note).
