# delivery-capacity-service 에 대한 요청 (MFC 쪽에서)

## CP-1. `CapacityReduced` 구독 (2026-11-09 기록)

- **요청한 곳**: mfc-capacity — 수용 축소 때 넘친 창의 영향 주문을 알린다(이미 발행).
- **지금**: 받는 곳이 없어 새 Hold 만 막힌다. 이미 잡힌 주문은 그대로.
- **할 것**: 구독 → 슬롯 반영, 영향 주문 처리. 주문·고객 안내는 order·notification(N-1 과 같은 채널)과 함께.
- **사용자 결정**: #15 는 임시 구현값 — 영향 주문을 어떻게 다룰지(옮김·취소·안내만)는 질문 필요(`mfc-defaults-status.md` #15).
- **결정(2026-11-15, 사용자 "추천대로")**: 이미 잡힌 주문은 **유지**하고 고객에게 **지연될 수 있다고 안내**한다. 고객이 원하면 **다른 시간대로 바꾸거나 취소**한다(자동으로 옮기거나 취소하지 않는다). 구현 때 필요한 것: 안내 채널(notification N-1), 고객의 시간대 변경(order·capacity 교체 점유 — `SlotHoldResult` 는 아직)·취소(claim/order 기존 흐름) 경로 확인.
- **1부 구현(2026-11-16, PC 5)**: delivery-capacity 가 `CapacityReduced`(mfc.capacity.v1, 바인딩 `capacityEvents`)를 받아 영향 주문을 **확정된 도착 시간대별**로 묶어 `SlotCapacityReduced`(delivery.slot.v1, window ARRIVAL — 시간대 점유 없는 주문은 MFC 작업 창 WORK 로 한 건, reason MFC_CAPACITY_REDUCED) — 점유·정원은 그대로(자동으로 옮기거나 취소 안 함). notification 이 받아 고객마다 "배송이 늦어질 수 있어요"(도착 시간대, 상품 준비 전까지 취소 가능, 목적지 주문). **2부(남음)**: 고객 시간대 변경(S4 — order·orchestration·capacity 교체 점유 `newSlotFor` 422 UNSUPPORTED_ACTION) — 생기면 안내 문구에 더한다.
- **상태(2026-11-17 PC 5 정리)**: 1부·2부 모두 병합·배포(#59, #61·#64·#65) — **CP-1 끝**.
- **2부(S4) 구현(2026-11-16, PC 5 — PR 여럿)**: 고객 시간대 변경 = order `reservation-time-changes` → orchestration S4 사가(delivery-capacity 교체 점유 · mfc-capacity 교체 Hold · mfc-inventory 약속일 · mfc-fulfillment 작업 지시 시간 변경). 흐름 `order-checkout-saga.md` §2.2. 지연 안내 문구에 시간 변경 안내를 더하는 것은 고객 앱 화면이 생긴 뒤.
