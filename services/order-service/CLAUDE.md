# order-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `order-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/order-service.yaml` → `openApiGenerate` 가 `com.pmart.order.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.order.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:order-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:order-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8201.

## 주문 초안 (2026-10-31, BR-13·14·21)
- `POST /order-drafts`(고객만): 정본 재확인 → 거절(422): 구매 불가 계정·권역 밖/판정 불명·판매 불가 SKU·구매 상한·가격 미설정·10kg 넘는 단일 상품. `MFC_CHANGED`(409).
- 차단 사유(초안은 만든다): 최소주문 미달(부족액)·한집/알뜰 불가(심야·센터 운영 밖·설정·조회 실패)·예약 시간 미선택/불가. 순서는 `OrderRules.BLOCKER_ORDER`.
- 금액(`OrderRules`, Commerce 8.3 사례로 시험): 한집 4,000·알뜰/예약 3,000, 30,000 이상·첫 구매 무료, 봉투 100, 최소 15,000, 10분 동결, 10kg 단위 분할.
- 첫 구매는 customer `first-purchase-checks` 후보면 EXPECTED(자격 확보는 결제 진입 때). 같은 구매 의도의 유효 초안은 그대로 돌려준다.
- `GET /delivery-options`(회원·비회원): 방식별 가능 여부·사유·예상시간(NOT_SET — 사용자 결정)·배달팁(기본/적용/사유)·가장 빠른 예약 슬롯.
- 의존: customer·product·pricing·center-master 는 `/internal`(내부 호출), 예약 슬롯은 capacity `/slots`(사용자 토큰 전파).

## 결제 진입 (2026-10-31, 슬라이스 4)
- `POST /order-drafts/{id}/checkouts`: 견적 버전·동결·차단 사유(`CHECKOUT_BLOCKED`)·계정 구매 가능(customer `/internal/accounts/{id}/status`, 아니면 422 `ACCOUNT_NOT_PURCHASABLE` — 2026-11-02) 확인 → orderId(초안+요청 키로 결정적) → orchestration S1 시작. 진행 중이면 기존 주문 ID.
- 초안 조회가 사가 사본을 읽어 status(RESOURCE_PENDING·PAYMENT_PENDING·PAYMENT_UNKNOWN·DRAFT)·nextAction(WAIT·PAY·REVIEW)·resources·`payment`(결제창 정보)·`checkoutFailure` 를 채운다. 사가 조회 실패면 WAIT·UNKNOWN.

## 주문 확정·조회 (2026-10-31, 슬라이스 5 S1 g~j)
- `POST /internal/orders/{orderId}/confirmation`(내부 호출, orchestration): orderId 로 초안 잠금 → 승인 금액 = 동결 `quote.totalAmount` 확인(`OrderRules.amountMatches`, 아니면 422 `AMOUNT_MISMATCH`, 기록 안 함) → 초안 스냅샷을 `customer_order`/`customer_order_line`(V4)로 옮기고 초안 CONFIRMED → `OrderConfirmed`(토픽 `order.order.v1` 명시, correlationId = 사가 ID)를 한 트랜잭션에. 초안 없음 404, 같은 초안이 다른 주문으로 확정됨 409. 이미 확정이면 다른 키도 200(사건 한 번).
- **이행 계획(슬라이스 6a, 2026-11-07)**: `OrderConfirmed.shipments` = 출고 1개(`<orderId>-S1`, 모든 행·수량, D6-2), 행에 `productName` 사본. orchestration 이 이 사건으로 S2(작업 요청)를 시작한다.
- 주문행 ID `<orderId>-L<n>`(`OrderRules.orderLineId`, 사가 시작 때 보낸 값과 같음). 수량: originalQty = unfulfilledQty = 주문 수량, 나머지 0. 첫 구매 적용 = 배달팁 사유 FIRST_PURCHASE → 사건 `firstPurchase`.
- 초안 스냅샷 품목에 `unitsPerSku`(product `/internal/skus`) 추가. 그 전 초안은 확정 때 트랜잭션 밖에서 product 로 채운다.
- **상품명·옵션 라벨 사본(BR-35, 2026-11-02)**: 초안 생성 때 품목마다 product-content `/internal/product-contents/{productId}/snapshot?sku=`(`pmart.order.content-base-url`)에서 이름·옵션 라벨을 받아 초안 `Line` 에 고정 → 확정 때 `customer_order_line.product_name`·`option_label`(V6)로 옮김(다시 조회 안 함). 조회 실패는 null(지어내지 않음, 초안은 계속). 결제창 주문명 `OrderRules.orderName`: 첫 이름 + "외 N건", 이름 없으면 "PMart 장보기 N개 상품", 100자 상한.
- **주문 당시 배송지 사본(BR-37, 2026-11-02)**: 초안 생성 때 customer `shopping-context` 의 배송지(도로명·상세·우편번호·수령인·연락처·출입·핀)를 `domain.ShippingAddress` 로 초안 스냅샷 `delivery.shippingAddress` 에 고정 → 확정 트랜잭션에서 `customer_order_shipping`(V5, 개인정보라 원장과 분리)으로 옮김. 본인 초안·주문 응답에만 싣고(내부 조회·사건·로그 제외, `toString` 가림), 주소록 수정·삭제와 무관. 필수값이 없으면 사본 없음(null, 지어내지 않음). 그 전 초안·주문은 null.
- `GET /orders/{id}`·`GET /orders`(고객 본인, 남의 것 404, 최근 확정 순 keyset cursor). 원천은 order 원장만(shipments·claims 빈 배열).
- 초안 조회: 확정 주문이 있거나 사가 COMPLETED → CONFIRMED·DONE. RECONCILIATION_REQUIRED → PAYMENT_UNKNOWN·WAIT, COMPENSATED → DRAFT·REVIEW(만료면 EXPIRED·RE_QUOTE). 결제 진입은 확정·COMPLETED·RECONCILIATION_REQUIRED 면 기존 주문 ID(새 사가 없음).

## 출고 전 취소 (2026-10-31, 슬라이스 8 S3 d)
- `GET /internal/orders/{orderId}`(내부 호출): 주문 원장 사본 — accountId·결제 시도·승인액·금액 구성·행별 수량 누계(claim 심사·환불 근거).
- `POST /internal/orders/{orderId}/line-quantity-changes`: reason=CANCEL_BEFORE_SHIP·SHORTAGE(결품 사가, 2026-11-07 ADR-0045 — 같은 산식, 주문 상태 유지) 만(그 밖 422 UNSUPPORTED_ACTION). 주문 행 잠금 → `OrderRules.cancelBeforeShip`(미이행 −q·출고 전 취소 +q, 초과·없는 행 409 INVALID_STATE, 바뀌지 않음) → 전 행 미이행·인계 0 이면 CANCELLED, version +1 → 행마다 `OrderLineQuantityChanged`, 주문에 `OrderCancelled`(scope·claimId·accountId·firstPurchase) 한 트랜잭션. 이미 CANCELLED 면 409. 같은 키는 재생.

## 상태
주문 초안·배송 방식 안내·결제 진입(2026-10-31)·주문 확정·주문 조회·내부 주문 조회·출고 전 취소 반영 구현. 예약 시간 변경·그 밖 수량 변경 사유(결품·인계·반품)·탈퇴 확인·배송 사실·고객 약속은 501/422.
- 준비 시작(슬라이스 6b, 2026-11-07, ADR-0043): `PreparationStarted`(mfc.fulfillment.v1, 바인딩 `fulfillmentEvents-in-0`) → CONFIRMED 주문만 PREPARING(+버전)·`OrderPreparationStarted`. 다른 상태면 그대로(재수신·취소 뒤 도착).
- **이행 확인 필요(2026-11-08 사용자 결정, 외부 검토 #5, 계약 0.2.0, V7)**: `ReservationBlocked`(mfc.inventory.v1, 바인딩 `inventoryEvents-in-0`) → `order_fulfillment_issue`(OPEN, 같은 사건 한 번 — source_event_id 유일). **자동 취소·환불·LOT 대체·고객 안내 없음, 주문 상태 불변.** `GET /fulfillment-issues?mfcId=&state=`(그 센터 운영자, 기본 OPEN), `POST /fulfillment-issues/{id}/acknowledgements`(확인 표시 + note). 실제 결품 처리는 피킹 결품 보고 → 결품 사가 그대로.
- 이행 확인 필요 자동 종결(2026-11-08 #5, V8, 계약 0.2.1): `ReservationBlockCleared`(mfc.inventory.v1) → 그 예약·SKU 남은 보류 0 이면 RESOLVED(`resolvedAt`), 일부면 메모.
- **배차용 배송지(2026-11-13, O-4 — delivery PC 5 가 넣음, 계약 0.2.2)**: `GET /internal/orders/{orderId}/delivery-destination`(내부 호출) — 도로명주소·핀·배송 방식·예약 창·상태만(수령인·연락처·출입 정보 없음). delivery-dispatch 가 좌표 변환에만 쓴다. **2026-11-14 고침**: `OrderService.internal`(claim 용 — 사본 없음)을 써서 도로명주소가 늘 null 이던 것 → `internalWithShipping`.
- **라이더용 전달지(2026-11-14, O-5 — delivery PC 5 가 넣음, 계약 0.2.4)**: `GET /internal/orders/{orderId}/drop-info`(내부 호출) — 주문 당시 사본 전부(주소·상세·수령인·**고객 전화번호**·출입·핀) + 배송 요청사항. 공개 범위(배정 라이더·픽업 뒤 ~ 완료 전)는 delivery-execution 이 판정. 사본 없으면 404 DROP_INFO_NOT_FOUND. 로그 금지.
