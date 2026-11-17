# ADR-0064 알림 1차 — 고객 앱 알림함만, 거래 안내 8가지, 푸시는 나중

- 상태: 승인 (2026-11-16, 사용자 — "알람 기능도 만들어줘" → 채널 "앱 알림함만 먼저", 알릴 일 "주문 확정·상품 준비 시작 / 배송 출발·배송 완료 / 취소·품절·환불 / 고객센터 답변", 라이더 "이번엔 고객만")
- 일부 대체: 결정 2(받는 사람은 고객만)는 ADR-0066(라이더 알림함, 2026-11-16)이 대체.
- 관련: `docs/architecture/service-map.md` notification-service, 계약 `notification-service.yaml` 0.2.0, 사건 `InquiryAnswered`(새 — case 발행), `service-requests/notification-service.md` N-1·N-2, 업무 원문 Commerce 15.x(알림은 주문 상태와 별도 기록, 알림 성공으로 거래를 확정하지 않음)

## 결정
1. **채널은 앱 알림함(IN_APP)만.** 서버에 알림을 쌓고 앱이 알림함에서 읽는다. 휴대폰 푸시(FCM)는 Firebase 프로젝트·서비스 계정 키·앱 기기 토큰 등록이 필요해 뒤로 미룬다 — 붙일 때는 같은 알림에 채널만 더한다. 알림톡·문자도 없다(설정 변경에서 CHANNEL_NOT_APPROVED).
2. **받는 사람은 고객만.** 라이더는 배차 제안을 앱이 직접 받는다. 라이더·운영자 알림함은 나중.
3. **알릴 일(모두 거래 안내 — 끌 수 없음, TRANSACTIONAL_CANNOT_DISABLE)**: 주문 확정(`OrderConfirmed`), 상품 준비 시작(`OrderPreparationStarted`), 배송 출발(`PickupCompleted`), 배송 완료(`DeliveryCompleted` — 리뷰 14일 안내), 주문 취소(`OrderCancelled`, 품절 전체 취소 구분), 품절 부분 취소(`OrderLineQuantityChanged` reason SHORTAGE·전체 아님), 환불 결과(`RefundResult` SUCCESS·FAILED), 고객센터 답변(`InquiryAnswered`).
4. **결과를 모르는 일은 끝났다고 쓰지 않는다.** 취소·품절 알림은 "환불 결과가 나오면 다시 알려 드릴게요", 환불 완료는 결제 결과 SUCCESS 뒤에만, 결과 모름(UNKNOWN)은 알리지 않는다(N-1 보류 단계 안내도 하지 않음 — 추천대로).
5. **환불 결과를 주문과 잇기**: `RefundResult` 에는 주문이 없어 `RefundIntentCreated`(order.claim.v1)의 주문·클레임을 저장해 잇는다. 결과가 먼저 오면 잠시 두었다가 의도가 오면 알린다(같은 환불 건은 DB advisory lock 으로 한 줄로).
6. **받는 고객**: 사건에 accountId·customerId 가 있으면 그것, 없으면 order `GET /internal/orders/{id}`. 없는 주문은 건너뛰고, order 장애는 재시도(DLT).
7. 같은 사건은 한 번(dedupe = 사건 종류 + 사건 ID). 분할 배송이면 배송마다 출발·완료 알림이 간다(원문 "분할은 전량 완료 1회"는 order 가 배송 완료를 모으기 전까지 못 지킨다 — 지금 주문은 출고 1개).
8. 처음 켤 때 소비 그룹은 latest(지난 주문에 알림을 몰아 쌓지 않게).

## 결과
- 고객 앱의 알림함·수신 설정 화면(목)을 실서버에 붙일 수 있다. 목적지 종류에 INQUIRY(문의)가 더해졌다.
- 남은 것: 휴대폰 푸시(FCM), 라이더·운영자 알림, 발송 결과 사건(`NotificationDelivered`·`Failed`), 범용 요청 API, 야간 발송 제한, 지연 안내(`DeliveryEtaUpdated`).
