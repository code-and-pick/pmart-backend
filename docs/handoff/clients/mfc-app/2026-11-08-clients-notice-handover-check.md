# 공지: 인계 허가 만료 뒤 점원 확인 (mfc-fulfillment 0.4.4)

- 근거: 사용자 결정 D-1 안 C(2026-11-08), `docs/business/flows/package-handover-decisions.md` D-1
- 계약: `contracts/openapi/mfc-fulfillment-service.yaml` 0.4.4 (커밋은 이 공지와 같은 커밋)

## 무엇이 바뀌나
라이더가 포장 QR 을 찍은 뒤 "픽업 완료"(확정) 없이 인계 허가가 만료되면(앱 오류·통신 끊김), 실물이 라이더에게 갔을 수 있다. 이제 그런 포장은:

| 동작 | 지금 |
|---|---|
| 같은 라이더·같은 배송이 다시 찍기 | 확인 없이 새 허가(이전과 같음) |
| 다른 라이더가 찍기(재배정 등) | 409 `FENCE_EXPIRED_UNCONFIRMED` — 점원 확인 뒤 |
| 주문 중지(고객센터 취소 등) | 판정 PENDING(포장 `HANDOVER_IN_PROGRESS`) — 점원 확인 뒤 진행 |
| 재포장 | 409 `FENCE_EXPIRED_UNCONFIRMED` — 점원 확인 뒤 |
| 재배정 | 이전 라이더 허가를 자동으로 끝내지 않음. 이전 라이더 확정은 409 `NOT_ASSIGNED` |

## 클라이언트가 할 일
### 패킹 앱(점원, MFC_WORKER)
- 확인 필요 표시: `GET /api/v1/packages/{id}/handover-resolution` 의 `checkRequired: true`(새 필드), 마지막 확인 `lastCheck{result, checkedBy, checkedAt}`.
- 확인 버튼: `POST /api/v1/packages/{id}/handover-checks` (Idempotency-Key, X-Source-System), 본문 `{ "result": "AT_STATION" | "TAKEN_BY_RIDER", "note": "…" }`
  - `AT_STATION` = 포장대에 있음 → 허가 해제(다른 라이더가 찍을 수 있고, 대기 중인 중지가 이어짐)
  - `TAKEN_BY_RIDER` = 포장대에 없음 → 그 라이더에게 인계된 것으로 확정(응답 verdict `HANDED_OVER`)
  - 허가가 아직 살아 있으면 409 `FENCE_ACTIVE`, 확인할 허가가 없으면 409 `INVALID_STATE`. 응답은 `HandoverResolution`.
- 확인할 포장 목록 API 는 아직 없다(포장 단위 조회만). 필요하면 요청서로.

### 라이더 앱
- 새 거절 코드 `FENCE_EXPIRED_UNCONFIRMED`: "앞선 인계 허가 확인이 필요합니다 — 점원에게 문의" 안내. 재배정된 이전 라이더의 확정은 `NOT_ASSIGNED`.

## 추가(같은 날, mfc-fulfillment 0.4.5): 배차 요청 — 사용자 결정 D-5
### 패킹 앱(점원)
- "배차 요청" 버튼: 포장을 시작한 뒤 `POST /api/v1/shipments/{id}/dispatch-requests` (Idempotency-Key, X-Source-System), 본문 `{ "estimatedPackMinutes": 1~180 }`(필수) → 201 `DispatchRequest{trigger MANUAL, estimatedReadyAt, …}`. 이미 요청된 출고는 200 으로 그 요청. 포장 시작 전·취소된 출고는 409 `INVALID_STATE`.
- 포장을 다 끝낼 때까지 누르지 않으면 서버가 자동 요청한다(trigger `AUTO_ON_READY`) — 버튼을 놓쳐도 주문이 멈추지 않는다.
- 출고 조회 `GET /api/v1/shipments/{id}` 에 `dispatchRequest`(없으면 null).

## 추가(같은 날, mfc-fulfillment 0.4.6): 취소품 복귀 추천 칸 — 사용자 결정 #59
### 패킹 앱(점원) — 복귀(RETURN) 작업
- 작업 상세 `lines[].picks[].suggestedToLocations`: 돌려놓을 추천 선반(앞이 우선 — 그 상품이 있던 칸, 없으면 기타 칸). 조회 실패면 빈 배열.
- 복귀 실적도 진열 규칙을 탄다: 다른 칸에 놓으면 실적 REJECTED(사유 `SKU_ALREADY_SHELVED`·`CATEGORY_MISMATCH`·`LOT_NOT_SHIPPABLE`·`LOCATION_CAPACITY_EXCEEDED` 등) — 추천 칸으로 다시.

## 추가(같은 날, mfc-fulfillment 0.4.7): 포장 사이 재구성
### 패킹 앱(점원)
- `POST /api/v1/shipments/{id}/repackings` 본문 `{ "reason": "CONTENT_CHANGE" 등, "packageIds": [이전 포장들], "packages": [{ "bagCount": n, "contents": [{orderLineId, sku, lotId(필수), qty}] }] }` → 201 `{ packages: [새 Package] }`. (주문 줄·SKU·LOT) 합계가 이전과 같아야(422 `CONTENT_MISMATCH`). 이전 QR 은 무효, 새 QR 을 붙인다. 수량을 줄이는 재구성은 아직 없다.
