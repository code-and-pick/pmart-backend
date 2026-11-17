# mfc-inbound-service 에 대한 요청

## MI-1. 오배송 회수품 MFC 반납 받기 (2026-11-16, 보낸 영역 delivery — DD-2 뒷단)
- **요청한 곳**: delivery-execution(오배송 회수 — `docs/architecture/service-requests/delivery-dispatch-service.md` DD-2 구현).
- **지금**: 회수 라이더가 MFC 에 도착해 `POST /deliveries/{id}/returns/store-deliveries` 를 누르면 회수 배송이 끝난다(RETURNED_TO_STORE, 수행료). **점원 수령 확인은 없다** — 사용자 2026-11-16 "추천대로": mfc-inbound 가 생기기 전까지 MFC 도착 = 반납.
- **사건(이미 나감)**: `ReturnDelivered`(delivery.delivery.v1, 계약 `contracts/events/ReturnDelivered.v1.json`) — purpose `MISDELIVERY_RECOVERY`, deliveryId(회수 배송), originDeliveryId(오배송된 원래 배송), caseId(오배송 확인 사건), orderId, mfcId, **lines = 원래 배송이 전달한 줄·수량**(회수 대상 — 실제 상태·수량은 검수가 정한다), deliveredAt. 앞서 `ReturnPickedUp`(회수지에서 받음)도 같은 모양.
- **mfc-inbound 가 할 것**:
  1. `ReturnDelivered`(MISDELIVERY_RECOVERY) 구독 → 회수품 입고 대기.
  2. 점원 수령 확인 → 검수: **신선·냉장·냉동은 폐기, 상온 미개봉품만 검수 후 재고 복구**(ADR-0052 결정 4). 회수품을 원래 고객에게 다시 보내지 않는다.
  3. (나중) 점원 수령 확인 참조를 라이더 반납과 잇기 — execution 은 `receivedByStaffRef` 를 이미 받는다. 확인을 반납 조건으로 바꾸려면 사용자 결정.
- **사용자 결정**: 검수 규칙은 ADR-0052 에 있음. 수령 확인을 반납 조건으로 할지는 나중.

## MI-2. 반납 도착 대기 목록 — 점원이 회수품이 온 줄 알게 (2026-11-17, 보낸 영역 delivery — 고객 반품 회수 DD-3·오배송 회수 DD-2)
- **지금 빈 곳**: 회수 라이더가 MFC 에 도착해 `returns/store-deliveries` 를 누르면 delivery 는 `ReturnDelivered`(delivery.delivery.v1)를 내고 배송을 끝낸다. 점원이 회수품을 격리 반입하는 `POST /return-receipts`(claimId·returnPickupId·줄)는 있지만, **mfc-inbound 가 `ReturnDelivered` 를 구독하지 않아 점원 화면에 "반납 도착"이 보이지 않는다** — 점원이 claimId·returnPickupId 를 알 길이 없어 반입·검수·환불(claim `ReturnItemReceived`→`ReturnInspected` 뒤)이 이어지지 않는다(2026-11-17 PC 5 점검).
- **사건 모양**(이미 나감): `ReturnDelivered` — purpose `CLAIM_RETURN`(claimId, lines = claim 이 요청한 줄 — orderLineId·qty·claimLineId·sku) 또는 `MISDELIVERY_RECOVERY`(caseId·originDeliveryId, lines = 원래 배송 전달 줄), deliveryId(회수 배송 = returnPickupId), orderId, mfcId, riderId 는 ReturnPickedUp 에, deliveredAt.
- **mfc-inbound 가 할 것(PC 3)**:
  1. `ReturnDelivered` 구독(두 purpose) → 그 MFC 의 "반납 도착" 대기 행(회수 배송 ID·claimId 또는 caseId·줄·도착 시각).
  2. 점원 화면(mfc-app)에 대기 목록 → 고르면 `POST /return-receipts` 를 그 값으로 채워 반입(격리). 반입하면 대기에서 빠짐.
  3. 오배송 회수(MISDELIVERY_RECOVERY)는 claim 이 없으니 MI-1 검수 규칙(신선·냉장·냉동 폐기, 상온 미개봉만 재고 복구)으로.
- delivery 는 바꿀 것 없음. 점원 수령 확인을 반납 조건으로 바꾸는 것(execution `receivedByStaffRef`)은 나중 사용자 결정.
- **보충(2026-11-17 PC 5)**: 세 번째 purpose `FAILED_DELIVERY_RETURN` — 지원센터가 전달 실패를 확정하고 상품이 라이더 손이면 같은 라이더가 MFC 에 반납한다(originDeliveryId = 실패한 배송, lines = 그 배송이 인계받은 줄, claim·case 없음). 대기 목록에 같이 올리고, 검수는 MI-1 규칙(신선·냉장·냉동 폐기, 상온 미개봉만 재고 복구) — 이 주문의 환불·재배송은 고객센터가 정한다(자동 환불 없음).
