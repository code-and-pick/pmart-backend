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
