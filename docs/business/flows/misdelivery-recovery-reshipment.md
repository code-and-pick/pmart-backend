# 확인된 오배송 — 회수와 재발송 (ADR-0052 결정 3·4·5)

2026-11-16 작성(PC 5). 근거: ADR-0052(오배송 확인 → 회수와 새 발송을 동시에), ADR-0049(결품은 그 품목만 부분 취소·환불), 사용자 결정 2026-11-15(오배송 확인은 상담원·지원센터가 기록), 2026-11-16("추천대로" — 회수 수행료·회수지·실패·반납, "원래 고객의 전화는 회수 라이더에게 보여야 해", "사진보기 기능 넣어", "새상품 재배차 서비스 넣어" → "전부 PR로").

## 0. 시작

상담원·지원센터가 백오피스에서 오배송 사건을 연다(case `POST /cases` kind MISDELIVERY) → case 가 `MisdeliveryConfirmed`(platform.case.v1, 키 caseId: caseId·deliveryId·riderId·confirmedBy·confirmedAt·evidenceRefs)를 낸다. 같은 사건을 두 서비스가 따로 받는다.

| 받는 쪽 | 하는 일 | 같은 사건 두 번 |
|---|---|---|
| delivery-dispatch | **회수**(§1) | 회수 배송 ID 를 caseId 로 정함 — 하나 |
| order-orchestration | **재발송**(§2) | 사가 `reshipment_saga` caseId 유일 — 하나 |

## 1. 회수 (delivery — DD-2)

1. dispatch: 회수 배송(type RETURN, purpose MISDELIVERY_RECOVERY, shipment `RCV-<caseId>`) + 배차 시도(원래 라이더 제외). 원래 배송이 없거나 픽업 전이면 만들지 않는다.
2. 회수지 = 원래 라이더가 전달 완료를 누른 시각의 위치 점(tracking `location-at`, 앞뒤 3분), 없으면 원래 배송지 좌표. 도착지 = MFC. 예상 배달료 = 일반 표(회수지 → MFC 거리).
3. 회수지 근처(같은 반경 단계) AI 추천 라이더 중 회수지까지 가장 빠른 1명에게 제안(묶지 않음, 배달리스트 없음).
4. 회수 라이더: 원래 완료 증빙(놓은 곳·사진 — 사진 보기 API), **원래 고객 전화번호**(주소는 아님)를 본다 → 회수지에서 사진 → `returns/pickups`(ReturnPickedUp, lines = 원래 배송이 전달한 줄) → MFC 도착 = 반납 `returns/store-deliveries`(ReturnDelivered, 수행료).
5. 실패(부재·거부): 도움요청 → 지원센터, 운영자 배차 취소(reassign=false)로 끝. 자동 재시도 없음.
6. MFC 점원 수령·검수(신선 폐기·상온 미개봉 재고 복구)는 mfc-inbound(MI-1 — 아직).

## 2. 재발송 (order-orchestration 사가)

| 단계 | 하는 일 | 거절이면 |
|---|---|---|
| RESOLVE_ORDER | 사건에 orderId 가 없으면 dispatch `GET /internal/deliveries/{id}/assignment`(orderId). 주문 원장 `GET /internal/orders/{id}` → 보낼 줄 = 원수량 − 출고 전 취소 − 이미 재발송(출고는 주문당 하나 — D6-2). 출고 ID `<orderId>-RS-<caseId 로 정한 8자>` | 취소된 주문·보낼 것 없음·배송 없음 → 상신 |
| RECORD_RESHIPMENT | order 수량 원장 `RESHIPMENT`(행마다 미이행 +q·재발송 +q) — **같은 수량을 재발송과 환불로 겹치지 않게**(ADR-0052 결정 5) | 상신 |
| RESERVE_INVENTORY | mfc-inventory 예약(전부 아니면 전무). 422 INSUFFICIENT_STOCK 이면 details(SKU 가용·줄 적격 수량)까지 줄여 다음 시도(키 `sagaId:RESERVE_INVENTORY:<n>`, 최대 5번). 줄인 만큼 = 못 보내는 줄 | 해석 안 됨·다른 거절 → 상신 |
| CONFIRM_RESERVATION | 예약 확정(결제 없음 — 고객 비용 0원) | 상신 |
| REQUEST_WORK | mfc-fulfillment ConfirmOrder `kind: RESHIPMENT`·`reshipmentRef: caseId` — 같은 주문의 두 번째 작업 지시(원래 작업 중지 수량은 빼지 않음) | REJECTED → 상신 |
| APPLY_SHORTAGE | 못 보내는 줄 order `SHORTAGE`(미이행 −q·출고 전 취소 +q) | 상신 |
| REQUEST_REFUND | claim `shortage-refunds`(그 줄만 부분 환불, ADR-0049) → COMPLETED | 상신 |

- 예약된 줄이 없으면 CONFIRM_RESERVATION·REQUEST_WORK 를 건너뛴다. 못 보내는 줄이 없으면 REQUEST_WORK 뒤 끝.
- 결과 모름(미전달·시간 초과)은 실패로 단정하지 않고 같은 키로 sweep 이 다시.
- 시작 기준: `pmart.orchestration.reshipment.start-cases-confirmed-after` 이후 확인된 사건만(새 구독 그룹이 토픽 처음부터 읽으므로 — 없으면 시작하지 않는다).

### 2.1 재발송 출고가 흘러가는 길
- MFC: 재발송 작업 지시의 PICK(READY)·PACK — 점원이 평소처럼 피킹·포장·배차 요청. 그 출고의 사건(WorkAccepted·PackingFinished·ShipmentReady·DispatchRequested·LineShortageReported)에 `purpose: MISDELIVERY_RESHIPMENT`·`caseId`.
- 피킹 중 결품(LineShortageReported purpose MISDELIVERY_RESHIPMENT): 결품 사가가 **주문 전체 취소 판정(CHECK_ORDER) 없이** 그 품목만 부분 취소·환불.
- 배송: dispatch 가 DispatchRequested 로 배송을 만든다(출고당 하나) — 재발송이면 type RESHIPMENT·caseId, 약속 = 오배송 확인 + 50분. 주소·배송 방식은 주문 그대로(O-4).
- 주문 원장: 재발송 상품의 인계·전달은 O-1 그대로(보낼 수량 = 원수량 − 출고 전 취소 + 재발송). 주문 상태는 뒤로 가지 않는다(이미 COMPLETED 면 그대로).

## 3. 남은 것
- 재발송 지연(재고 확보·배정 실패) 기한과 고객 안내(ADR-0052 남은 것) — 지금은 상신(수동).
- 재발송 출고 작업 중지(고객이 재발송을 원치 않을 때) — 원래 작업 지시만 중지된다.
- 처리 대기 목록(BO-12 `stuck-sagas`)에 재발송 사가가 아직 없다.
