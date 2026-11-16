# claim-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `claim-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/claim-service.yaml` → `openApiGenerate` 가 `com.pmart.claim.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.claim.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:claim-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:claim-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8204.

## 고객 전체 취소 (2026-10-31, 슬라이스 8 첫 단계)
- 흐름 정본: `docs/business/flows/cancel-vs-handover.md` §6. 규칙 `domain/ClaimRules`, 접수·사건 `application/ClaimService`, 환불 `application/RefundService`, 표 `claim`·`claim_line`·`refund_intent`(V2).
- `POST /claims`: 고객 토큰·type CANCEL 만(운영자·CS 접수, RETURN·EXCHANGE 는 422 UNSUPPORTED_ACTION). order `GET /internal/orders/{id}`(내부 호출)로 본인(아니면 404)·CONFIRMED(아니면 ORDER_NOT_CANCELLABLE)·모든 행 × 미이행 수량(아니면 PARTIAL_CANCEL_NOT_ALLOWED, 초과 QUANTITY_EXCEEDS_AVAILABLE) 심사. 진행 중 취소 클레임이 있으면 DUPLICATE_CLAIM. 클레임 STOP_PENDING + stopRequestId, `ClaimOpened`·`CancellationRequested`(order.claim.v1). 결제 시도·승인액·금액 구성을 접수 때 스냅샷.
- 구독(inbox): `OrderCancelled`(order.order.v1, claimId) → 행 APPROVED·IN_PROGRESS·환불 의도(원 승인액 전액, 구성 합 ≠ 승인액이면 FAILED AMOUNT_MISMATCH)·`RefundIntentCreated` → 커밋 뒤 payment 취소(멱등키 `REFUND-<의도 ID>`). `RefundResult`(order.payment.v1) SUCCESS → 환불 COMPLETED·클레임 CLOSED·`ClaimClosed`. `WorkStopResult`(mfc.fulfillment.v1) HANDED_OVER → REJECTED.
- 환불 sweep(`pmart.claim.refund-sweep-interval`, 기본 15초): 실행 대기는 같은 키로 재실행, PG 결과 대기·불명은 `GET …/cancellations/{requestId}` 원거래 조회. 기준 `pmart.claim.refund-retry-after`(30초).
- 조회: `GET /claims/{id}`·`/claims/{id}/refunds`(고객 본인, 남의 것 404, 운영자·서비스 모두), `GET /claims`(고객은 본인 것, cursor 없음 — 최근 순 size 건).

## 시스템 자동 전체 취소 (2026-11-07, 슬라이스 6a D6-4)
- `POST /internal/claims/system-cancellations`(orchestration S2, MFC 작업 거절 뒤): order 원장으로 CONFIRMED·미이행 수량 확인(아니면 422 ORDER_NOT_CANCELLABLE, 없으면 404) → 모든 행 × 미이행 수량, channel SYSTEM·reason FULFILLMENT_REJECTED, 계정은 주문의 accountId. 뒤 흐름은 고객 취소와 같다(`CancellationRequested` reason FULFILLMENT_REJECTED·channel → S3 → OrderCancelled → 전액 환불).
- 진행 중인 취소 클레임이 이미 있으면(고객이 먼저) 새로 만들지 않고 200 으로 그 클레임. 같은 키·같은 본문은 재생(201). sourceRef(사가 ID)는 reasonDetail 끝에 붙인다.

## 상태
고객 전체 취소·환불 실행·조회(2026-10-31), 시스템 자동 전체 취소(2026-11-07). 심사·결정·철회·반품·교환·회수 수령은 501.
- **결품 전체 취소(2026-11-08, ADR-0046)**: 시스템 전체 취소가 reason `OUT_OF_STOCK`(결품 사가)도 받고 주문 PREPARING 도 받는다(`ClaimRules.checkFullCancel(..., system=true)`, 고객은 CONFIRMED 만). CancellationRequested 사유는 시스템 사유 그대로. 활성 취소(`activeCancel`·유일 인덱스 V3)는 현장 중지가 있는 클레임만 — 결품 부분 환불 클레임은 제외. 전체 환불액 = 원 승인액 − 이 주문의 실패 아닌 환불 합(`refundedForOrder`, 상품 금액도 같이 뺌).
- **결품 부분 환불(2026-11-07, ADR-0045)**: `POST /internal/claims/shortage-refunds`(orchestration 결품 사가, 주문 SHORTAGE 반영 뒤) — 행마다 요청 ≤ 출고 전 취소 누계 − 이미 결품 환불(`shortageRefundedQty`), 할인 안분 있으면 422 UNSUPPORTED_ACTION. 클레임 IN_PROGRESS(type CANCEL·reason OUT_OF_STOCK·channel SYSTEM·행 APPROVED)·환불 의도 PARTIAL = 단가×수량(봉투·배달팁 0) → 커밋 뒤 payment 부분 취소, RefundResult 로 CLOSED(기존 흐름). CancellationRequested 는 내지 않는다. 고객 접수는 FULFILLMENT_REJECTED·OUT_OF_STOCK 사유를 400 으로 막는다.
- **공제 있는 부분 환불(2026-11-16, ADR-0065)**: `shortage-refunds` 에 `feeDeduction`(원, 선택) — 환불 = 단가×수량 − 공제(0 이상, 환불 의도 amount = 실제 환불·item_amount = 상품값). 0원이면 환불 의도 없이 클레임 CLOSED. 쓰는 곳: orchestration 고객 잘못 오배송 회수 후 환불(9,000원).
