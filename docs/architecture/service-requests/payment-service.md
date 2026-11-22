# payment-service 에 대한 요청

## PY-1. 직원 결제 조회 — 주문의 결제 상태·결제수단 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **왜**: 고객 상세 "결제·환불 내역". 지금 payment-service 에는 직원(OPERATOR) 경로가 없다. 환불 금액·사유·진행은 claim `/claims/{id}/refunds` 로 본다.
- **필요한 것**: `GET /payments?orderId=`(또는 고객 ID) 직원 조회 — 결제 상태, 결제수단 종류(카드사·간편결제 이름 정도, 카드번호 없음), 승인·취소 금액, 시각. CS_AGENT·OPERATOR_ADMIN.
