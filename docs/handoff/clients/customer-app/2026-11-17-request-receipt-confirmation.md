# 요청 — 고객 앱 "받았어요"(수령 확인) 버튼

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/customer_app`(PC 1 클라이언트)
- 계약: delivery-execution 0.20.0 `POST /deliveries/{deliveryId}/receipt-confirmations`, notification 알림 `DELIVERY_RECEIPT_CHECK`(백엔드 PR — PC 1 검토·배포 전)
- 사용자 2026-11-17: **"도착예정 시간보다 20분이 지나면 고객에게 음식을 받았는지 물어보고 고객이 전달완료를 눌러주면 라이더앱에도 반영"**, "5시간 지나면 자동완료"

## 무엇이 생기나
- 픽업 뒤 전달 완료 전인 배송이 도착 예정(라이더 ETA — 꺼져 있으면 약속 시간대 끝) + 20분을 넘으면 고객 알림함에 **"상품을 받으셨나요?"**(templateKey `DELIVERY_RECEIPT_CHECK`, 목적지 ORDER + orderId). 배송마다 한 번.
- 고객이 누르면 `POST /deliveries/{deliveryId}/receipt-confirmations`(본문 없음, Idempotency-Key·X-Source-System) → 200 → 배송 COMPLETED(라이더 앱에도 완료로 보인다). 남의 주문 404, 이미 완료·실패 처리 중 409("이미 처리된 배송이에요" 안내).
- 그래도 아무도 누르지 않으면 픽업 5시간 뒤 서버가 자동 완료한다(라이더 수행료는 지원센터가 따로 확인).

## 앱이 할 것
- 주문 상세: 배송이 픽업 뒤·전달 전(배송 조회/ETA 단계 TO_CUSTOMER·AT_CUSTOMER)일 때 **"받았어요"** 버튼. 위 알림을 누르면 그 주문 상세로 열리므로 같은 버튼을 쓴다. 누르면 확인 대화("상품을 받으셨나요? 받음으로 처리하면 배송이 완료돼요") 뒤 호출.
- 못 받았으면 지금처럼 배송 신고(문의 `deliveryIssue NOT_RECEIVED`)로.
- 이 알림을 휴대폰 푸시로도 보낼지는 notification `customer-templates` 설정(PC 1) — 넣는다면 `DELIVERY_RECEIPT_CHECK`.
