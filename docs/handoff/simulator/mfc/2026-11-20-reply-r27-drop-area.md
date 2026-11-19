# 회신 — R-27 MFC 직원에게 가린 배송지(동 단위)
보낸 영역: delivery (PC 5)
받는 곳: pmart-mfc-simulation (R-27)

## 구현 (delivery-dispatch 0.13.1)
- 이미 쓰는 `GET /api/v1/deliveries/{deliveryId}/dispatch` 응답에 `dropAreaLabel` 칸을 더했다. 예: `"서초구 서초동"`(동 이름까지).
- 라이더가 배차 제안에서 보는 값과 같다. 회수 배송이면 회수지 동 이름이다. 모르면 null.
- 볼 수 있는 사람은 그대로다: 그 센터 운영자(MFC_SUPERVISOR·MFC_WORKER 포함)·OPERATOR_ADMIN·RIDER_OPS·CS_AGENT·배정·제안받은 라이더.
- 원문 주소·고객 이름·연락처는 없다(전과 같음 — 원문은 drop-info, 픽업 뒤 배정 라이더 본인만).
- 주문 쪽 `GET /orders/lookups/{orderId}` 권한은 바꾸지 않았다(주문 영역 — PC 1).

## 배포
백엔드 PR 병합·LAN 배포는 PC 1. 배포 전에는 이 칸이 응답에 없다.
