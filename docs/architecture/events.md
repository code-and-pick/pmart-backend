# 이벤트 카탈로그 (발행자 → 구독자)

문서 버전: 0.1 · 2026-10-23 · 설계안. 봉투 규약은 `architecture-design.md` §5.2. 스키마 파일은 `contracts/events/{EventType}.v1.json`(작성 예정).

> 규칙
> - 이벤트는 **이미 확정된 사실**만 싣는다. 요청·의도는 이벤트가 아니라 명령 API다(예외: `*Requested`는 "요청이 접수된 사실").
> - `(sourceSystem, eventId)` 멱등. consumer는 inbox 테이블로 1회 적용. 같은 aggregate의 역순 수신은 `aggregateVersion`으로 폐기하되 실물 사실(출발·인계·수령)은 폐기하지 않고 정정 경로로 수용.
> - 하위 호환: 필드 추가만. 삭제·타입 변경·rename은 `V2` 신규 이벤트.
> - 토픽 `{domain}.{aggregate}.v1`, 파티션 키 = aggregateId.
> - 개인정보·PG 비밀·주소 원문·출입 비밀번호는 payload에 넣지 않는다(참조 ID만).
> - 단계 표기: ① 1단계 ② 2단계 ③ 3단계.

---

## 1. Commerce

| 이벤트 | 발행자 | 구독자 | 핵심 payload | 발생 조건 | 단계 |
|---|---|---|---|---|---|
| `ProductVersionPublished` | product-service | product-content, search, storefront, pricing, cdc-inventory, mfc-inventory, order, cart, promotion(SKU → 카테고리 사본, 2026-11-20 ADR-0082) | productId, baseProductId, sku[], unitsPerSku, weightG, packagingSpec, shelfLifeGuarantee(ADR-0041), netContent(2026-11-19 BR-19, 내용량 — product-content·storefront 단위 가격), dimensions·dimensionsSource(2026-11-16, mfc-inventory 칸 용량), categoryPaths, version | 승인된 상품/SKU 버전 발효 | ① |
| `SkuIssued` | product-service | cdc-inventory, mfc-inventory, approval, pricing | sku(E-/B-), baseProductId, version | 본사 승인 후 SKU 발급 | ① |
| `ProductSaleStateChanged` | product-service | cart, search, storefront, order, demand-planning, mfc-inventory(단종 → 센터 취급 OFF, 2026-11-19) | sku, state(ON_SALE/TEMP_OUT/DISCONTINUED), effectiveAt | 전사 판매 상태 변경 | ① |
| `CategoryChanged` | product-service | search, storefront, product-content | categoryId, parent, state, version | 분류 승인 발효 | ① |
| `ProductContentPublished` | product-content | search, storefront | productId, contentVersion | 콘텐츠 발효(5분 내 반영 목표) | ① |
| `PriceVersionEffective` | pricing-service | cart, storefront, search, order, approval | sku, price, priceVersion, effectiveAt | 가격 승인 발효(확정 주문 소급 없음) | ① |
| `CustomerConsentChanged` | customer-service | notification, audit | accountId, consentType, version(기록 차수), granted, changedAt, termsVersion·required·channel(2026-11-17) | 약관/마케팅 동의 변경(항목마다 한 건, 같은 값이면 없음 — CU-1 2026-11-17 구현) | ① |
| `CustomerWithdrawalRequested` | customer-service | order, cart, notification | accountId | 탈퇴 접수(신규 구매·마케팅 중단) | ① |
| `CustomerWithdrawalCompleted` | customer-service | auth(세션 끊기·소셜 연결 지움·식별값 해시 분리 보관 5년), cart(회원 장바구니 지움), notification(고객 푸시 토큰 지움), audit(예정) | accountId, completedAt | 미결 의무(order·claim) 종결 확인 후 완료(2026-11-20 ADR-0077) | ① |
| `ShoppingAddressChanged` | customer-service | cart | accountId, shoppingAddressId, shoppingContextVersion, mfcId | 현재 쇼핑 주소 변경(늦은 응답 폐기 기준) | ① |
| `CartItemChangeApplied` | cart-service | BI | cartId, cartItemId, sku, qty, cartVersion, appliedRequestId | 장바구니 변경 확정 | ① |
| `ReviewPublished` / `ReviewDeleted` | review-service | product-content, storefront(진열 평점·순위 신호), audit | reviewId, orderLineId, productId, rating | 리뷰 등록/삭제(삭제 후 재작성 불가) | ② |
| `CouponIssued` | promotion | notification(쿠폰 도착 알림) | couponId, accountId, promotionId, promotionName, channel(SIGNUP·ADMIN), reason?(DISPATCH_UNAVAILABLE), orderId?, discountType, discountValue, discountMax?, validUntil, issuedAt | 서버가 준 쿠폰(가입 축하·보상·직원 지정 — 앱 내려받기 DOWNLOAD 는 없음). 토픽 commerce.coupon.v1, 2026-11-21 ADR-0082 5단계 | ② |
| `CustomerIdentityVerified` | customer | promotion(가입 축하 쿠폰 — stage SIGNUP, 같은 signupKey 에는 다시 안 줌) | accountId, stage(SIGNUP·PROFILE), signupKey?(번호 HMAC — 되돌릴 수 없음), verifiedAt | 휴대폰 번호 확정(ADR-0074 VERIFIED). 번호 원문·해시는 싣지 않는다. 토픽 commerce.customer.v1, 2026-11-21 ADR-0082 5단계 | ② |
| `SkuDisplayDiscountChanged` | promotion | search(Logstash — 색인 sku-discounts), storefront(display_sku_discount) | sku, seq, priceVersion, basePrice, unitDiscount, salePrice, ratePercent, promotionId?, promotionName?, endsAt? | SKU 표시 할인이 바뀜(행사 시작·끝·조기 종료·가격 변경·카테고리 상품 추가 — promotion 30초 주기 작업이 달라진 것만). 할인 없음 = unitDiscount 0. 받는 쪽은 seq 큰 것만, 가격 사본 priceVersion 이 같을 때만 할인가. 토픽 `commerce.sku-display-discount.v1`(키 SKU, **compact** — SKU 마다 마지막 상태 보존), 2026-11-21 ADR-0082 4단계 | ② |
| `ProductRankingSignalsChanged` | storefront | search(Logstash — products-v6 정렬 칸) | productId, version, ratingCount, ratingAverage, ratingScore, salesWindow, salesWindowDays | 상품 순위 신호가 바뀜(리뷰·주문 사건, 매일 판매 창) — 토픽 commerce.product-ranking.v1, ADR-0072 | ② |

## 2. Order & Payment

| 이벤트 | 발행자 | 구독자 | 핵심 payload | 발생 조건 | 단계 |
|---|---|---|---|---|---|
| `OrderDraftCreated` | order-service | BI | draftId, purchaseIntentId, accountId, mfcId, quoteVersion, quoteExpiresAt | 초안 생성·가격 동결 시작 | ① |
| `OrderConfirmed` | order-service | cart(주문분 정리), customer(첫 구매 사용 기록), order-orchestration(S2 시작 — ConfirmOrder 동기 호출, 2026-11-07), mfc-fulfillment(동기 ConfirmOrder 로 받음), notification, demand-planning, replenishment, delivery-dispatch(이행 계획), BI | orderId, orderVersion, accountId, draftId, mfcId, lines[{orderLineId, sku, qty, unitsPerSku, weightG, unitPrice, discountShare, cartItemId, productName}], deliveryMode, arrivalWindow, outOfStockPreference, reservationId, capacityHoldId, slotHoldId, paymentAttemptId, 금액(itemAmount·deliveryTip·deliveryTipReason·bagFee·totalAmount), firstPurchase(첫 구매 무료 적용 → customer 사용 기록), plannedShipments, shipments[](2026-11-07부터 주문당 1개 `<orderId>-S1`, D6-2), confirmedAt. 토픽 `order.order.v1` | PG 승인 대사 일치 + 자원 확정 후 | ① |
| `OrderPreparationStarted` | order-service | commerce(조회), notification | orderId, startedAt | MFC `PreparationStarted` 반영. 이후 예약 시간 변경 차단 | ① |
| `ReservationTimeChanged` | order-service(2026-11-16 S4 — orchestration 사가 결과 CONFIRMED 때) | 구독 전(notification 고객 안내·dispatch 약속·BI 예정) | orderId, orderVersion, requestId, previousArrivalWindow, arrivalWindow, changedAt | 예약배달 시간 변경 확정 | ① |
| `OrderLineQuantityChanged` | order-service | claim, demand-planning, replenishment, commerce | orderId, orderLineId, originalQty, unfulfilledQty, handedOverQty, cancelledBeforeShipQty, reason, version | 부분 취소·결품 반영, 포장 인계(reason HANDOVER + handoverId — O-1, 2026-11-16), 오배송 재발송(reason RESHIPMENT + reshippedQty·sourceRef — 2026-11-16), 반품 수량(reason RETURN + returnedQty·claimId — O-7, 2026-11-17, claim ClaimClosed 반영) | ① |
| `OrderPartiallyDelivered` | order-service | notification, commerce | orderId, deliveryId, deliveredLines[] | 분할배송 일부 완료 — `DeliveryCompleted` 뒤 아직 다 전달되지 않았을 때(구현 2026-11-16, O-1) | ① |
| `OrderCompleted` | order-service | review, notification, BI | orderId, completedAt | 모든 유효 배송 전달 완료 + 미전달분 해소(구현 2026-11-16, O-1 — 행마다 전달 + 출고 전 취소 = 원수량, payload deliveryIds) | ① |
| `OrderCancelled` | order-service | claim(취소 확정 → 환불 의도), customer(첫 구매 혜택 복원, FULL), delivery-dispatch(신규 배차 차단), notification, demand-planning, replenishment, BI | orderId, scope(FULL/PARTIAL), lines[], reason, cancelReason(2026-11-19 — 클레임 사유, 알림 문구), claimId, accountId, firstPurchase | 취소 확정(환불 완료와 별개) | ① |
| `OrderExpired` | order-service | cart, BI | draftId/orderId, reason(PRICE_EXPIRED/RESERVATION_EXPIRED/PAYMENT_FAILED) | 10분 만료·미승인 확정 | ① |
| `ReservationTimeChanged` | order-service | mfc-fulfillment, delivery-capacity, notification | orderId, oldWindow, newWindow, version | S4 성공 | ① |
| `CustomerCommitmentChanged` | order-service | replenishment | mfcId, sku, orderLineId, committedQty, dueAt, state(VALID/BLOCKED/CANCELLED), version | 확정·취소·결품으로 C 변동(취소 신청만으로는 아님) | ② |
| `CheckoutSagaStateChanged` | order-orchestration | backoffice-bff, BI | sagaId, orderId, step, state, lastError | 사가 단계 전이 | ① |
| `CompensationExecuted` | order-orchestration | audit, case | sagaId, resourceType, resourceId, result | 이번 시도 신규 자원 보상 해제 | ① |
| `ReconciliationRequired` | order-orchestration | case, backoffice-bff | sagaId, orderId, mismatchType(AMOUNT/QTY/STATE), details | 대사 불일치·자동 복구 한도 초과 | ① |
| `CancellationRequested` | claim-service | order-orchestration | claimId, orderId, lines[], reason(FULFILLMENT_REJECTED = 작업 거절 자동 취소), channel(SYSTEM 추가) | 고객/CS 취소 접수·시스템 자동 취소(S3 시작) | ① |
| `ClaimOpened` | claim-service | notification, case | claimId, orderId, type(CANCEL/RETURN/EXCHANGE), lines[], reason, RETURN 이면 channel·accountId·faultCode·returnFee(2026-11-17) | 접수 | ① |
| `ClaimDecided` | claim-service | order, delivery-execution(회수), mfc-quality, notification, backoffice | claimId, decisions[{claimLineId, APPROVED/REJECTED, qty}], refundIntentId? | 심사 결과(일부 승인 가능) | ① |
| `ReturnPickupRequested` | claim-service(2026-11-17 발행 — CL-1) | delivery-dispatch(2026-11-17 구현 #104 — 바인딩 claimEvents, 사건 하나 = 회수 배송 하나, 같은 클레임 진행 중이면 만들지 않음) | claimId, orderId, mfcId, pickupRequestId(`<claimId>-P<n>`), attempt, purpose CLAIM_RETURN, feeWaived, lines[{orderLineId, qty, claimLineId, sku}], pickupAddressRef(`order:<orderId>` 원주문 주소), deliveryId(원 — 선택, claim 은 안 실음) | 회수 요청(회수비 결제됨·면제, 첫 실패 뒤 자동 재요청, 상담원 재요청 — 회수 실패는 delivery 가 `ReturnPickupFailed` 로 알림, #104) | ② |
| `ReturnFeePaymentRequested` | claim-service(2026-11-17, CL-1) | notification(결제 필요 알림 — 연결 전) | claimId, orderId, accountId, amount(9,000), paymentAttemptId, deadlineAt | 반품 회수비(고객 사유) 결제 시도를 처음 만든 뒤 한 번 | ② |
| `ReturnRecoveryFailed` | claim-service(2026-11-17, CL-1 ③) | case(상담원 재연락 사건 RETURN_RECOVERY_FAILED — R-8 구현 2026-11-17 0.22.0, 바인딩 claimEvents·LAN 배포됨), notification(연결 전) | claimId, orderId, accountId, mfcId, attempts, countedAttempts·retryAllowed·returnDeadlineAt(2026-11-17 추가 결정 — 우리 잘못 RIDER_INCIDENT 는 안 셈, 전체 3번, false 면 취소만), lastDeliveryId, lastFailureReason, failedAt | 반품 회수 두 번 실패(또는 상담원 재요청 뒤 실패) — 클레임 RECOVERY_FAILED | ② |
| `RefundIntentCreated` | claim-service | payment(명령은 동기; 이벤트는 기록), audit, notification | refundIntentId, orderId, amount, kind(ITEMS·RETURN_FEE — 2026-11-17), basis(원안분), paymentAttemptId | 환불 의도·금액 산정. kind RETURN_FEE = 반품 취소 때 낸 회수비 돌려주기(회수비 결제 시도의 전액 취소, CL-1 추가 결정 2) | ① |
| `ExchangeReshipmentApproved` | claim-service | order, delivery-dispatch, mfc-fulfillment | claimId, orderId, lines[], newShipmentRef | 교환 승인(재배송은 교환 건만) | ② |
| `ClaimClosed` | claim-service | order(2026-11-17 구독 — O-7, 반품 종결의 returnedLines → 행 returnedQty, 바인딩 claimEvents), notification, case(2026-11-18 — 열린 반품 회수 실패 사건에 이력 CLAIM_CLOSED 만, 사건은 닫지 않음) | claimId, orderId, type, outcome(CANCELLED_REFUNDED·REJECTED_HANDED_OVER, 반품 2026-11-17: RETURN_REFUNDED·RETURN_FEE_UNPAID·RETURN_CANCELLED, 추가 결정: RETURN_SHORT_CLOSED(받은 만큼만 끝내기)·RETURN_EXPIRED(접수 + 7일 안에 회수 안 됨)), returnedLines[{orderLineId, qty, resaleQty, disposeQty}](반품 종결만) | 회수·검수·환불·통지 의무 모두 확인 | ② |
| `PaymentApproved` | payment-service | order-orchestration, audit, claim(2026-11-17 — 반품 회수비 결제 시도만, orderRef `RTF-<claimId>`) | paymentAttemptId, pgTransactionId, orderRef, amount, currency, approvedAt | PG 승인 사실 확인 | ① |
| `PaymentFailed` | payment-service | order-orchestration, notification | paymentAttemptId, code | 승인 실패 확정 | ① |
| `PaymentResultUnknown` | payment-service | order-orchestration, case(장기) | paymentAttemptId, inquiryCount, nextInquiryAt | 타임아웃·응답 유실(1분×5→상신→15분) | ① |
| `PaymentMismatch` | payment-service | order-orchestration, case | paymentAttemptId, expectedAmount, approvedAmount | 금액·통화·주문 불일치 | ① |
| `RefundResult` | payment-service | claim, order, notification, audit | refundRequestId, refundIntentId, pgTransactionId, amount, result(SUCCESS/FAILED/UNKNOWN), cumulativeRefunded, remainingLimit | 전체/부분 취소 결과 | ① |
| `CompensationCancelResult` | payment-service | order-orchestration | paymentAttemptId, result | 승인 후 확정 실패 보상 취소 결과 | ① |

## 3. SCM

| 이벤트 | 발행자 | 구독자 | 핵심 payload | 발생 조건 | 단계 |
|---|---|---|---|---|---|
| `ReplenishmentRecommended` | replenishment | mfc-inbound(요청함), backoffice-bff, notification | planRunId, mfcId, sku, N, Q(추천), W, K, 미충족, firstShortageAt, snapshotRefs | SCM-D04/D05 완료(요청·승인 아님) | ② |
| `SupplyRequestReceived` | replenishment | mfc-inbound(ACK), approval | supplyRequestId, version, mfcId, lines[], requestedReadyAt, result(ACCEPTED/CONFLICT/PENDING) | SCM-D06 | ② |
| `TransferApproved` (=ApprovedTransfer) | replenishment | cdc-outbound, transport-planning, mfc-inbound(2026-11-20 구독 구현 — 입고 예정 EXPECTED, 발행자 없음), approval | trfId, trfVersion, fromCdc, toMfc, lines[{sku, approvedQty, unit}], deadlineMeaning(ARRIVAL/HANDOVER/READY), dueAt, priority, approvalRef | 사업승인·부분승인 확정(SCM-D08) | ② |
| `TransferChanged` / `TransferCancelled` | replenishment | cdc-outbound, transport-planning, mfc-inbound(2026-11-20 구독 구현 — toVersion 이 더 클 때만, 출발 뒤 취소는 반영 안 함) | trfId, fromVersion, toVersion, scope, reason | 승인 후 변경·취소(실출발 전만) | ② |
| `TransferResidualClosed` | replenishment | cdc-outbound, backoffice-bff, BI | trfId, closedQty(Uclosed), approver, basis | 미출발 잔량 종료(`A0 = D + Cpre + Uclosed + Uopen`) | ② |
| `SupplyShortfallAlerted` | replenishment | notification, case | cdcId, sku, shortfall, affectedMfcs[] | CDC 전체 공급 부족 | ② |
| `SupplyStatusChanged` | replenishment | order(영향 주문 조사) | mfcId, sku, expectedQty, expectedAt, state(DELAYED/REDUCED) | 공급 지연·축소 | ② |
| `ForecastVersionPublished` | demand-planning | replenishment | forecastVersionId, mfcId, sku, buckets[], basis, confidence | 예측 버전 발행 | ② |

## 4. CDC WMS

| 이벤트 | 발행자 | 구독자 | 핵심 payload | 발생 조건 | 단계 |
|---|---|---|---|---|---|
| `AllocationResult` | cdc-inventory | replenishment, cdc-outbound | trfId, trfVersion, allocationId, lines[{sku, lot, allocatedQty, shortQty}] | TRF 확보(FEFO 다LOT) | ② |
| `TransferDeparted` (TOUT) | cdc-inventory | mfc-inbound(2026-11-20 구독 구현 — 출발 줄 = 입고 예정 수량, 같은 departureId 한 번), mfc-inventory(T 참조), replenishment, transport-execution, transport-planning | trfId, departureId, departureLineIds[], lines[{sku, lot, lpn, qty}], departedAt, source(REAL/SIMULATED) | 출발 검증 후 `현물−q·예약−q·Transit+q` 1회 전기 | ② |
| `TransitReconciled` | cdc-inventory | mfc-inbound, replenishment, backoffice-bff | trfId, departureId, q, r, l, t, state(RECONCILED/PENDING/CONFLICT) | `q = r + l + t` 대사 | ② |
| `TransferAckStatus` | cdc-inventory | mfc-inventory, replenishment | receiptId, ackState(ACKED/PENDING/CONFLICT) | TIN 확인 처리 결과 | ② |
| `QuantityPostingResult` | cdc-inventory | cdc-inbound, cdc-outbound, cdc-quality | requestId, commitId, postingType, appliedQty, result | 수량 반영 요청 결과 | ② |
| `InventorySnapshotPublished` | cdc-inventory / mfc-inventory | replenishment(예정), demand-planning(예정), BI | centerId, asOf, version, lines[{sku, lot, onHand/H, reserved/R, held/Q, available/A, safety}] | 주기/사건 스냅샷. mfc-inventory 는 2026-11-08 부터 주기 발행(SKU 단위·LOT 없음, 500줄씩 part/partCount, 설정 없으면 안 함) | ② |
| `LotBlocked` / `LotReleased` | cdc-inventory | cdc-outbound, replenishment, mfc-quality(리콜 참조), order(영향 참조) | sku, lot, scope, reason, blockedQty | quality 요청 반영 | ② |
| `InventoryAdjustmentPosted` | cdc-inventory / mfc-inventory | approval, audit, replenishment | centerId, adjId, approvalRef, lines[], executedQty | 승인 범위 내 ADJ 집행 | ② |
| `PutawayTaskStateChanged` | cdc-inbound | backoffice-bff | taskId, state, qty(C/U/K) | 적치 상태 전이 | ② |
| `ReplenishmentMoveCreated` | cdc-inbound | cdc-location, backoffice-bff | movId, fromLoc, toLoc, sku, qty | 최소 이하 자동 보충 | ② |
| `LocStateChanged` | cdc-location | cdc-inbound, cdc-outbound | locCode, state | LOC 상태 변경 | ② |
| `CapacityReservationResult` | cdc-location | cdc-inbound | reservationId, locCode, result | 공간 예약 결과 | ② |
| `DockReservationResult` | cdc-location | transport-planning | dockId, window, result | 도크 예약 | ② |
| `WaveStateChanged` | cdc-outbound | backoffice-bff | waveId, state | Wave 전이 | ② |
| `PickShortageReported` | cdc-outbound | replenishment, cdc-inventory | trfId, sku, lot, shortQty | 실피킹 부족 | ② |
| `OutboundReadinessChanged` | cdc-outbound | transport-planning | trfId, readyAt(예상/확정), hold, cargo[{lpn, weight, volume, temp}] | 출고 준비 상태 | ② |
| `TruckDepartureValidated` | cdc-outbound | cdc-inventory(전기 요청), transport-execution | departureId, trfId, validatedLines[], mismatches[] | TruckActuallyDeparted ↔ 실상차 대조 완료 | ② |
| `TransferShipmentManifestIssued` | cdc-outbound | mfc-inbound(2026-11-20 구독 구현 — LPN 목록 `packages`), transport-execution | shipmentManifestId, trfId, departureId, lines[{sku, lot, lpn, qty, expiryDate}] | 출발 매니페스트 | ② |
| `OutboundDiscrepancyRaised` | cdc-outbound | case, replenishment | trfId, type(MIXED/MISSING/EXCESS/SEAL), details | 출발 전 불일치 | ② |
| `HoldRequested` / `HoldReleased` / `DisposalApproved` | cdc-quality | cdc-inventory | sku, lot, qty, reason, approverRef | 품질 판단(실행은 inventory) | ② |
| `RecallIssued` | cdc-quality | cdc-inventory, cdc-outbound, mfc-quality, replenishment, order(참조), case | recallId, sku, lots[], scope | 리콜 | ② |
| `OperationRestrictionApplied` / `Lifted` | cdc-quality(CDC) / mfc-capacity(MFC) / rider(라이더) | order, replenishment, transport-planning, delivery-dispatch, approval, backoffice-bff | centerId/riderId, restrictionType(INTAKE_STOP/WORK_STOP/QUALITY/LINEHAUL/RIDER), scope, appliedAt, approvalRef | 종류별 제한 적용·재개(중첩 유지) | ① |

## 5. MFC

| 이벤트 | 발행자 | 구독자 | 핵심 payload | 발생 조건 | 단계 |
|---|---|---|---|---|---|
| `InventoryHeld` | mfc-inventory | order-orchestration | reservationId, orderId, lines[{sku, lot?, qty}], expiresAt | 결제 직전 전품목 예약 성공 | ① |
| `InventoryReleased` | mfc-inventory | **mfc-inbound(보충 즉시 점검, 2026-11-08)**, order-orchestration(예정), cart(예정) | reservationId, reason, lines | 해제 확인 — 실제로 바뀐 해제만(2026-11-08 첫 발행) | ① |
| `InventoryHoldExpired` | mfc-inventory | order-orchestration | reservationId | 10분 만료(PG 불명이면 `결제 확인 대기 점유`로 전환되므로 만료 아님) | ① |
| `ReservationBlocked` | mfc-inventory | **order(이행 확인 필요 표시, 2026-11-08 — 자동 처리 없음)**, order-orchestration(예정), replenishment(예정) | reservationId, orderId, sku, lot, blockedQty, reason | 품질 격리로 예약 BLOCKED(약속은 유지) | ① |
| `ReservationBlockCleared` | mfc-inventory | order(확인 필요 자동 종결) | reservationId, orderId, sku, lot, resecuredQty, remainingBlockedQty | 피킹 시작 전 보류분 자동 재확보(2026-11-08 #5) | ① |
| `MfcReceiptPosted` (TIN) | mfc-inventory | cdc-inventory, replenishment, mfc-inbound, transport-execution | receiptId, postingId, trfId, departureId, lines[{sku, lot, normalQty, quarantinedQty, pendingQty}], commitId | ConfirmReceipt COMMITTED 후 `H+r` | ② |
| `ReceiptCorrectionPosted` | mfc-inventory | cdc-inventory | receiptId, δr, reason | 수령 정정 | ② |
| `PackageHandedOverPosted` | mfc-inventory | mfc-fulfillment, BI | commitId, packageId, lines[{sku, qty}] | 인계 전기 `H−q, E−q, R−q` | ① |
| `InventoryExecutionApplied` | mfc-inventory | **mfc-inbound(자동 보충 즉시 점검, 2026-11-08)**, mfc-fulfillment(예정), mfc-quality(예정) | requestId, commitId, executionType, appliedQty, result | 작업 실행 반영 결과 — 성공만 발행(2026-11-08), 거절은 동기 응답 | ① |
| `StockAdjusted` | mfc-inventory | approval, audit, replenishment | adjId, lines[], approvalRef | 실사 ADJ, (2026-11-20 inventory 0.37.0) 수령장 분실 조사 보류 정리의 못 찾음(reason LOSS_WRITE_OFF) | ② |
| `StockHoldResolved` | mfc-inventory | **mfc-inbound(찾은 양 적치 작업, 0.18.0)** | resolutionId, mfcId, commitId, lines[{sku, lot, locCode, workState, foundQty, lostQty, sourceRefs[]}], approvalRef, resolvedBy | 센터 관리자 재고 정정(holdResolutions)이 적치 전 분실 조사 보류를 찾음·못 찾음으로 정리 (2026-11-20, 0.37.0) | ① |
| `MfcStockAvailable` | mfc-inventory | notification(재입고 알림) | mfcId, sku, availableQty | 정상 판정·판매 준비 후 A>0 전환 | ③ |
| `SkuHandlingChanged` | mfc-inventory | search(Logstash — 상품 carriedMfcIds), storefront(2026-11-19 BR-49 — mfcId 진열에서 미취급 빼기, sku_handling 사본) | mfcId, sku, productId?, handlingEnabled, version, changedAt, republished? | 센터 취급 ON/OFF 변경(바뀔 때만). 토픽 `mfc.sku-handling.v1`(무제한 보관 — 재색인 재생). `/internal/sku-handling-republications` 가 지금 상태를 같은 버전으로 다시 알림 (2026-11-05) | ① |
| `WorkAccepted` / `WorkRejected` | mfc-fulfillment | order-orchestration(6a 는 동기 응답으로 진행, 사건은 기록) | orderId, fulfillmentId, workVersion, sagaId, taskIds[], rejectReason?·retryable? | ConfirmOrder 결과(수락≠완료). 거절 사유 WorkRejectReason | ① |
| `PreparationStarted` | mfc-fulfillment | order, delivery-dispatch(준비 예상) | orderId, fulfillmentId, startedAt, estimatedPackDuration | 실제 준비 시작 확정 | ① |
| `LineShortageReported` | mfc-fulfillment | order-orchestration, case(동의 없음 시), mfc-quality(파손) | orderId, orderLineId, shortQty, reason(SHORT/DAMAGED/SPOILED/BLOCKED/PARTIAL_CLOSE — 2026-11-20 관리자 부분 종료 잔량, ADR-0079), alreadyHandedOverQty | 피킹 결품·파손·관리자 부분 종료 | ① |
| `PackingFinished` | mfc-fulfillment | delivery-dispatch, delivery-execution(포장 목록 — 픽업 판정), commerce | shipmentId, packages[{packageId, bagCount, weight, volume, alcohol}] | 포장 완료(인계 아님) | ① |
| `DispatchRequested` | mfc-fulfillment | delivery-dispatch(예정) | orderId, shipmentId, mfcId, trigger(MANUAL — 2026-11-13 BR-51 부터 새 요청은 MANUAL 만, AUTO_ON_READY 는 예전 기록), estimatedPackMinutes?, estimatedReadyAt | 배차 요청 — 점원이 포장 중 예상 시간과 함께, 없으면 출하 대기 때 자동(2026-11-08 D-5). 출고당 한 번 | ① |
| `ShipmentReady` | mfc-fulfillment | delivery-dispatch, delivery-execution(포장 목록 — 픽업 판정), order | orderId, shipmentId, mfcId, readyAt, packages[], pickupLocation | 출하 대기(배차는 `DispatchRequested` 로 — D-5) | ① |
| `PackageHandedOver` | mfc-fulfillment | order, delivery-execution, delivery-dispatch, claim, demand-planning, BI | orderId, shipmentId, deliveryId, packageId, riderId, handoverId, inventoryCommitId, lines[], handedAt | QR 전수 스캔·fence·inventory 커밋 후 | ① |
| `WorkStopResult` | mfc-fulfillment | order-orchestration, claim | orderId, stopRequestId, perPackage[{packageId, STOPPED/HANDOVER_IN_PROGRESS/HANDED_OVER}], notStartedLines[] (작업 지시 전 중지), claimId | 중지 요청 판정 | ① |
| `PackageInvalidated` | mfc-fulfillment | delivery-dispatch, delivery-execution | packageId, newPackageId, reason | 재포장(구 라벨 무효) | ① |
| `TaskProgressed` | mfc-fulfillment / mfc-inbound | mfc-capacity(PACK WAITING→READY + pickedBy → 피커에게 바로 배정, 2026-11-20 ADR-0080), backoffice-bff | taskId, type, state, qty, pickedBy(PACK), controlledBy·blockKind·reason(관리자 조작) | 작업 전이 | ① |
| `SupplyRequestSubmitted` | mfc-inbound | replenishment | supplyRequestId, version, mfcId, lines[], requestedReadyAt, urgency, reason, recommendationRef | 로컬 DRAFT→SUBMITTED | ② |
| `InboundArrivalRecorded` | mfc-inbound(2026-11-20 발행 — 도착 등록 때, mfc.inbound.v1) | transport-execution, replenishment(둘 다 구독 전) | trfId, tripId, stopId, arrivedAt | 차량 도착 기록(수령 아님) | ② |
| `ReceiptDiscrepancyReported` | mfc-inbound(2026-11-20 발행 — 수령이 재고 반영되고 SKU 별 출발 수량 ≠ 인수일 때, mfc.inbound.v1) | cdc-inventory, transport-execution, replenishment, case(모두 구독 전) | trfId, departureId, expected, received, missingQty, excessQty | 수령 차이 | ② |
| `ReturnItemReceived` | mfc-inbound | **mfc-quality(검수 사본, 2026-11-08)**, claim(2026-11-17 — 반품 수령 수량, returnReceiptId 로 한 번) | returnReceiptId, claimId(고객 반품만 — 2026-11-19 선택), returnPickupId, purpose·caseId·originDeliveryId·arrivalId(2026-11-19), lines[{sku, lot, qty, locationCode, orderLineId}], receivedBy, receivedAt | 회수품 점포 수령 — 격리(검수 대기, 판매 재고 아님) | ② |
| `MfcLocationStateChanged` | mfc-location | mfc-inventory, mfc-fulfillment | locationId, state, locationCode·zone·temperatureZone·version(전체 속성, 2026-11-07), reserveStock(보충재고 칸, 2026-11-21 R-30 — 추가만) | 위치 등록·속성 변경·폐쇄·활성(투영용 — version 큰 것만) | ① |
| `CapacityHoldResult` | mfc-capacity | order-orchestration | holdId, orderId, result, expiresAt | HoldCapacity 결과 | ① |
| `CapacityReduced` | mfc-capacity | delivery-capacity(2026-11-16 CP-1 구현 — 도착 시간대별 SlotCapacityReduced 로), order, notification | mfcId, window, oldCapacity, newCapacity, affectedOrders[] | 용량 축소(기존 약속 삭제 없음) | ① |
| `ReceivingCapabilityChanged` | mfc-capacity | replenishment, transport-planning | mfcId, windows[], maxPerArrival, staff | 수령창·수용량 변경 | ② |
| `AssignmentPublished` | mfc-capacity | mfc-fulfillment, mfc-inbound | planVersion, assignments[{taskId, workerId, assignmentVersion}] | 계획 발행(COMMITTED) | ① |
| `QualityDecisionMade` | mfc-quality | mfc-inventory, case(리콜), order(참조), **mfc-inbound(RELEASE → 해제분 적치 작업·격리 이동 정리, 2026-11-20)** | sku, lot, decision(QUARANTINE/RELEASE/DISPOSAL_PENDING), qty, approverRef | 판정·승인 | ① |
| `ReturnInspected` | mfc-quality | **mfc-inbound(재판매 적치 작업, 2026-11-08)**, claim(2026-11-17 — 검수 수량 원단가 부분 환불, RESALE·DISPOSE 모두, inspectionId 로 한 번) | inspectionId, claimId(고객 반품만 — 2026-11-19 선택), purpose·caseId·originDeliveryId(2026-11-19), returnReceiptId, lines[{sku, lot, qty, disposition, orderLineId(선택 — MQ-1 요청 2026-11-17, claim 은 있으면 그 행·없으면 SKU)}], inspectedAt | 회수품 검수(상온 미개봉만 재판매, 재고 반영은 quality 가 inventory 에 직접) | ② |
| `DisposalApproved` | mfc-quality | mfc-inventory, **mfc-inbound(격리 이동 작업 정리, 2026-11-20)** | disposalId, lines[], approverRef | 폐기 승인(실행은 inventory) | ② |

### MFC 구현 현황 (2026-11-08 코드·설정 기준)
위 표의 구독자는 설계상 목록이고, 상당수는 서비스가 아직 없거나 구독을 붙이지 않았다. 실제 상태:

- **발행 중**
  - mfc-inventory: `InventoryReleased`, `InventoryHoldExpired`, `ReservationBlocked`, `ReservationBlockCleared`, `PackageHandedOverPosted`, `InventoryExecutionApplied`, `StockAdjusted`, `StockHoldResolved`(0.37.0), `SkuHandlingChanged`, `InventorySnapshotPublished`(주기, 설정 있을 때만)
  - mfc-fulfillment: `WorkAccepted`, `WorkRejected`, `PreparationStarted`, `LineShortageReported`, `PackingFinished`, `ShipmentReady`, `DispatchRequested`, `PackageHandedOver`, `WorkStopResult`, `PackageInvalidated`, `TaskProgressed`
  - mfc-capacity: `AssignmentPublished`, `CapacityReduced`, `OperationRestrictionApplied`/`Lifted`
  - mfc-quality: `QualityDecisionMade`, `DisposalApproved`, `ReturnInspected`, `QualityIncidentImpactAssessed`(2026-11-19 R6 — 품질 사고 영향 LOT 이 인계된 주문, 바뀔 때마다 전체 목록; 구독 case-service — 2026-11-20 CS-MFC-Q 구현 case 0.31.0: 주문마다 상담원 사건 MFC_QUALITY_IMPACT, caseId 는 CaseOpened 로 mfc-quality 에) · mfc-location: `MfcLocationStateChanged` · mfc-inbound: `SupplyRequestSubmitted`, `ReturnItemReceived`, `InboundArrivalRecorded`·`ReceiptDiscrepancyReported`(2026-11-20 — 입고 예정, 원천 사건이 아직 없어 실제로는 안 나감)
- **아직 발행 안 함**(동기 응답으로 대신하거나 앞 흐름이 없음): `InventoryHeld`(예약은 동기 응답 — orchestration 이 응답으로 진행), `MfcReceiptPosted`·`ReceiptCorrectionPosted`(CDC 이송 흐름 없음), `MfcStockAvailable`(③), `CapacityHoldResult`(동기 응답), `ReceivingCapabilityChanged`(구독자 없음 — 값은 설정), (회수품 사건은 2026-11-08 부터 발행)
- **실제 구독(토픽 → 서비스: 처리하는 사건, 나머지는 inbox 만)**
  - `mfc.fulfillment.v1` → order-orchestration: `LineShortageReported` · mfc-quality: `LineShortageReported`(DAMAGED → 품질 사고, 2026-11-19) · order: `PreparationStarted` · claim: `WorkStopResult` · mfc-capacity: `TaskProgressed`(PACK 피킹 끝 → 피커 배정, 2026-11-20 ADR-0080 — 설정 저장소에 바인딩 `fulfillmentEvents-in-0` 필요)
  - `mfc.inventory.v1` → order: `ReservationBlocked`(이행 확인 필요)·`ReservationBlockCleared`(자동 종결) · mfc-inbound: `InventoryExecutionApplied`·`InventoryReleased`·`InventoryHoldExpired`·`StockAdjusted`(보충 즉시 점검)·`StockHoldResolved`(찾은 양 적치 작업, 2026-11-20) · mfc-fulfillment: `ReservationBlocked`(피킹 중 보류 점검)
  - `mfc.capacity.v1` → mfc-fulfillment: `AssignmentPublished`
  - `mfc.location.v1` → mfc-inventory: `MfcLocationStateChanged`
  - `mfc.sku-handling.v1` → search(Logstash)
  - `scm.replenishment.v1`·`cdc.inventory.v1`·`cdc.outbound.v1` → mfc-inbound: `TransferApproved`·`TransferChanged`·`TransferCancelled`·`TransferDeparted`·`TransferShipmentManifestIssued`(2026-11-20 — 입고 예정. 이 토픽에 내는 서비스는 아직 없다)
  - `mfc.inbound.v1` → claim: `ReturnItemReceived`(2026-11-17, 반품 수령 수량) · `mfc.quality.v1` → claim: `ReturnInspected`(2026-11-17, 검수 뒤 환불 — 줄의 orderLineId 가 있으면 그 행, MQ-1) — 같은 사건의 mfc 쪽 구독(quality·inbound)은 위 표
- **구독자가 아직 없는 서비스**(표에 "예정"으로 읽는다): replenishment, demand-planning, transport-planning·execution, delivery-dispatch·execution, cdc-*, approval(사건 구독), audit, notification, BI, backoffice-bff, cart. **case 는 2026-11-16 부터 구독**(2026-11-18 정리 — 실제로 받는 것: `DeliveryIncidentRaised`·`DispatchFailed`·`DeliveryCreated`·`ReturnPickedUp`·`ReturnDelivered`(delivery.delivery.v1), `RiderLocationStale`(delivery.tracking.v1), `ReconciliationRequired`·`CompensationExecuted`(order.saga.v1), `PaymentMismatch`·`PaymentResultUnknown`(order.payment.v1), `ReturnRecoveryFailed`·`ClaimClosed`(order.claim.v1) — 그 밖 표의 case 는 예정).

## 6. Middle-mile TMS

| 이벤트 | 발행자 | 구독자 | 핵심 payload | 발생 조건 | 단계 |
|---|---|---|---|---|---|
| `TransportRequestReceived` | transport-planning | replenishment | trfId, trfVersion, transportRequestId, state(PLAN_READY/DATA_PENDING/TIME_INFEASIBLE), missing[] | TMS-D01 접수 | ② |
| `TransportFeasibilityResult` | transport-planning | replenishment | trfId, feasible, candidateRuns[], capacityLimit, earliestReadyAt, reason | 운송 가능성·상한 회신 | ② |
| `TripAssigned` / `TripUpdated` | transport-planning | cdc-outbound, mfc-inbound, transport-execution, cold-chain, fleet | tripId, assignmentVersion, vehicleId, driverId, stops[{mfcId, plannedArrival, window}], cargo[{trfId, lpn[], compartment}] | 배정 CONFIRMED / 변경 | ② |
| `TripInstructionIssued` | transport-planning | transport-execution(기사 단말) | tripId, instructionVersion | 지시서 발행(구버전 무효) | ② |
| `TransportUnplannedReported` | transport-planning | replenishment, notification | trfId, qty, reason(NO_VEHICLE/TIME/DATA), alternatives[] | 미편성 | ② |
| `FleetReservationResult` | fleet | transport-planning | reservationId, vehicleId/driverId, window, result | 점유 예약 결과 | ② |
| `VehicleAvailabilityChanged` | fleet | transport-planning | vehicleId, nextAvailableAt | 재가용 계산 | ② |
| `RoadMatrixRefreshed` | transport-routing | transport-planning, transport-execution | matrixVersion, asOf | 도로시간 갱신 | ② |
| `TruckActuallyDeparted` | transport-execution | cdc-outbound(검증), replenishment, mfc-inbound(예고) | departureId, tripId, vehicleId, driverId, departedAt, evidenceRef, source(REAL/SIMULATED), trfCompositions[{trfId, lines[]}] | 실제 출발 사실(TOUT 아님) | ② |
| `TripStateChanged` | transport-execution | transport-planning, backoffice-bff | tripId, state | 운행 전이(IN_TRANSIT 삭제 없음) | ② |
| `StopArrived` | transport-execution | mfc-inbound, delivery? (아님) | tripId, stopId, mfcId, arrivedAt | 정차 도착(수량 증거 아님) | ② |
| `TransportHandoverEvidenceRecorded` | transport-execution | mfc-inbound, cdc-inventory(대사 참조) | tripId, stopId, trfId, departureId, lines[{lpn, sku, lot, qty}], handedBy, receivedBy, evidenceRef, at | 현장 인계 증빙(TIN 아님) | ② |
| `TransportIncidentRaised` / `Resolved` | transport-execution | replenishment, mfc-inbound, cdc-quality, case, notification | incidentId, tripId, type(DELAY/BREAKDOWN/GPS_LOST/TEMP/QTY), affectedTrfs[], custodyLocation | 사건 | ② |
| `TransportEtaUpdated` | transport-execution | mfc-inbound, replenishment | tripId, stopId, originalPromise, latestEta, basis | ETA 갱신(최초 약속 보존) | ② |
| `TripPhysicallyEnded` | transport-execution | fleet, cold-chain, transport-planning | tripId, endedAt, endLocation, openObligations[] | 승인 구간 종료(사건은 남을 수 있음) | ② |
| `TripPerformanceRecorded` | transport-execution | BI | tripId, plannedVsActual, costBasis(산정불가 포함) | 실적·비용 근거 | ② |
| `TemperatureExcursion` / `TemperatureRestored` | cold-chain | cdc-quality, mfc-quality, transport-execution | sensorScope(vehicle/compartment/zone), from, to, range, affectedLpns[] | 이탈/복구(복구≠정상 판정) | ② |

## 7. Last-mile Delivery

> **`Delivery` aggregate 의 발행자는 둘**(2026-11-14, PC 1 PR #8 검토 후속): delivery-dispatch(생성·제안·배정·배차 취소·배차 실패·지연)와 delivery-execution(가게 도착·픽업 완료·고객 도착·완료·실패·도움요청·수행료). 같은 토픽(`delivery.delivery.v1`)·같은 키(deliveryId)지만 DB 가 달라 `aggregateVersion` 을 하나로 셀 수 없다 — **버전은 발행 서비스(`sourceSystem`)마다 따로 오른다**. 역순 폐기는 `(sourceSystem, aggregateId)` 안에서만 비교하고, 두 서비스 사건 사이 순서는 버전이 아니라 업무 값으로 판단한다(배정은 payload `assignmentVersion`, 실물 사실은 시각·상태). 지금 구독자는 모두 사건 종류로 거르고 버전을 서비스 사이에서 비교하지 않는다.

| 이벤트 | 발행자 | 구독자 | 핵심 payload | 발생 조건 | 단계 |
|---|---|---|---|---|---|
| `ServiceabilityVersionApplied` | delivery-serviceability | order, customer, cart, approval | mappingVersion, effectiveAt, changedZones[] | 권역 매핑 발효(기존 주문 불변) | ① |
| `SlotHoldResult` | delivery-capacity | order-orchestration | slotHoldId, orderId, window, result, expiresAt | HoldSlot 결과 | ① |
| `SlotCapacityReduced` | delivery-capacity(2026-11-16 CP-1 — MFC 작업 여력 축소 때) | order(구독 전), notification(2026-11-16 — "배송이 늦어질 수 있어요") | mfcId, window(ARRIVAL 도착 시간대 / WORK 시간대 없는 주문), previousCapacity, newCapacity, affectedOrders[], reason | 정원 축소·지연 가능 | ① |
| `RiderStateChanged` | rider | delivery-dispatch, delivery-tracking | riderId, operatingState, newDispatchEnabled, vehicleType | 운행 시작/종료·신규배차 ON/OFF | ① |
| `RiderRestricted` | rider | delivery-dispatch(픽업 전 배정 풀고 재배차 — 2026-11-14 구현), notification(라이더 알림함 — 2026-11-16 §11-5), rider-auth(SUSPENDED·TERMINATED 는 진행 중 배송이 끝난 뒤 로그인 끊기 — 2026-11-15 구현), audit. mfc-fulfillment 는 구독하지 않고 `DeliveryAssignmentChanged` 로 인계를 막는다(D-4) | riderId, restriction(풀기는 NORMAL), previous, from, to, basis, evidenceRefs, approvalRef | 계정 제한(증빙 기반, 운영자 — RS-1) | ① |
| `RiderAppealDecided` | rider(2026-11-18 — 소명·이의 결정, 토픽 delivery.rider.v1) | notification(라이더 알림함) | riderId, appealId, targetType, decision, decidedAt | 설명 글·신청 내용 없음 |
| `RiderWithdrawn` | rider-service | rider-auth(세션 끊기·계정 사용 안 함·Google 연결 지움), notification(2026-11-19 — 그 라이더 단말 푸시 토큰 지움) | riderId, requestId, withdrawnAt(개인정보 없음) | 라이더 탈퇴 완료(2026-11-17 — 진행 중 배송·확정 전 수행료·산정 불가·열린 도움요청·제한 검토가 없을 때, 개인정보는 이미 지움) | ① |
| `RiderComplaintRecorded` | case(구현됨 2026-11-15 — 상담원 기록 `POST /rider-complaints`, case 0.3.0, service-requests case R-5) | rider | complaintId, riderId, deliveryId, complaintType(REPORT/MISDELIVERY/DAMAGE), recordedAt, caseId | 라이더 컴플레인 한 건(신고·오배송·파손) — rider 가 같은 종류 7일 안 서로 다른 배송 3건 이상이면 제한 검토 대상(RS-3, 2026-11-14, 자동 제재 없음). 토픽 platform.case.v1 | ① |
| `InquiryAnswered` | case(구현됨 2026-11-16 — 상담원 답변 `POST /inquiries/{id}/replies`) | notification | inquiryId, customerId, caseId, orderId, answeredAt | 고객센터 문의 답변 사실(답변 내용 없음) — 고객 알림함(notification N-2). 토픽 platform.case.v1 | ① |
| `RiderInquiryAnswered` | case(2026-11-16 — 라이더 문의 답변) | notification(라이더 알림함) | inquiryId, caseId, riderId, answeredAt | 지원센터가 라이더 문의에 답한 사실(답변 내용 없음, 라이더 앱 요청 §11-5). 토픽 platform.case.v1 | ① |
| `CustomerProfanityDetected` | case(2026-11-23 R-14 — 고객 문의 금칙어 자동 인식) | customer(CU-7 — 7일 정지·"7일 계정 정지" 알림, PC 1 구독 예정) | customerId, inquiryId, caseId, source(INQUIRY), detectedAt | 고객 문의 제목·본문이 설정 금칙어와 맞음(글·단어 없음). 문의는 그대로 접수. 토픽 platform.case.v1 | ① |
| `RiderSupplyChanged` | rider | delivery-capacity | zoneId, activeRiders | 수급 참고 | ① |
| `DeliveryCreated` | delivery-dispatch | delivery-execution, delivery-tracking, order, commerce, case(2026-11-16 — purpose MISDELIVERY_RECOVERY·MISDELIVERY_RESHIPMENT 만, 오배송 사건에 회수·재발송 배송 번호·이력; 2026-11-18 caseId `DF-<배송 ID>` 사고·파손 재발송은 그 배송의 전달 실패 사건에), claim(2026-11-17 — purpose CLAIM_RETURN 만, claimId·pickupRequestId 로 회수 요청에 배송 번호를 잇는다 — DD-3) | deliveryId, orderId, shipmentId, type(FORWARD/RETURN/RESHIPMENT — API 와 같게, 2026-11-10. 분할은 유형이 아님), mfcId, dropAddressRef, purpose·caseId·originDeliveryId(2026-11-16 — 오배송 회수면 RETURN·MISDELIVERY_RECOVERY, dropAddressRef `mfc:<mfcId>`) | 배송 단위 생성 | ① |
| `DispatchOfferSent` | delivery-dispatch | notification(라이더 알림) | offerId, attemptId, deliveryId, riderId, expiresAt(1분), fee | 제안 전송 | ① |
| `DeliveryListUpdated` | delivery-dispatch(2026-11-18 — 일반배차모드 새 배달, 라이더마다 1분에 한 번, 토픽 delivery.rider.v1) | notification(라이더 푸시만) | riderId, mfcId, newCount, notifiedAt | 일반 모드 라이더의 배달리스트에 지난 알림 뒤 새 배송 | ① |
| `DeliveryAssigned` | delivery-dispatch | delivery-execution, delivery-tracking, mfc-fulfillment, order, notification | deliveryId, riderId, assignmentVersion, acceptedAt, fee·vehicleType·distanceM(수락 당시 금액·조건 — execution 수행료 기준, 2026-11-13) | 유효 수락 1건 확정 | ① |
| `DeliveryAssignmentChanged` | delivery-dispatch | delivery-execution, mfc-fulfillment(이전 기사 인계 차단), delivery-tracking(위치 감시 — 해제면 RELEASED) | deliveryId, oldRiderId, newRiderId, assignmentVersion, reason | 재배정·배차 취소 | ① |
| `DispatchFailed` | delivery-dispatch | case, notification, order | deliveryId, reason(NO_RIDER/REPEATED_REJECTION/EXPIRED/SYSTEM_FAILURE — API 와 같게, 2026-11-10), attempts, mfcId(선택 — DD-MFC 2026-11-16, case 센터 관리자 사건함) | 배차 시도 실패 — **최종 실패가 아니다**: 배송은 배차 대기 그대로 다음 시도(RETRY)로 다시 요청되어 시도마다 반복될 수 있다(2026-11-14, PR #8). 구독자는 attempts 로 보고 주문·결제를 실패로 닫지 않는다 | ① |
| `DispatchDelayed` | delivery-dispatch | notification(점포·CS·고객), case(2026-11-19 R-9 — 상담원 팀 사건 DISPATCH_DELAYED, 센터 = mfcId, 배송마다 하나) | deliveryId, delayedMinutes, threshold, mfcId(선택 — DD-MFC 2026-11-16) | 지연 임계 초과 | ① |
| `DeliveryCancelledBeforePickup` | delivery-dispatch | delivery-execution, mfc-fulfillment, order, delivery-tracking(위치 감시 끝) | deliveryId, reason | 픽업 전 취소(상품은 점포 보관) | ① |
| `RiderArrivedAtStore` | delivery-execution | mfc-fulfillment(QR 활성), delivery-tracking | deliveryId, riderId, at | 가게 도착(인계 아님) | ① |
| `PickupCompleted` | delivery-execution | order, delivery-tracking, notification(delivery-dispatch 는 이 사건 대신 `PackageHandedOver` 를 직접 구독 — 2026-11-14) | deliveryId, packageIds[], handoverIds[], at | 모든 포장 `PackageHandedOver` 확인 후 | ① |
| `RiderArrivedAtCustomer` | delivery-execution | notification | deliveryId, at | 고객 도착 | ① |
| `DeliveryHeadingToCustomer` | delivery-execution(**요청 DE-6, 2026-11-19 — 아직 발행 안 함**) | notification(받는 쪽 만들어 둠) | deliveryId, orderId, purpose, at | 묶음 배달에서 다음 전달 차례가 이 배송 — 고객 "가고 있어요" | ① |
| `RiderApproachingCustomer` | delivery-tracking(**요청 TR-4, 2026-11-19 — 아직 발행 안 함**, delivery.tracking.v1) | notification(받는 쪽 만들어 둠) | deliveryId, orderId, purpose, distanceM, at | 전달지 300m 안 처음 — 고객 "곧 도착" | ① |
| `DeliveryCompleted` | delivery-execution(2026-11-17 method 에 CUSTOMER_CONFIRMED 고객 수령 확인·AUTO_COMPLETED 픽업 5시간 자동 완료 — 사진 없음, evidenceRef kind DOCUMENT) | order, review, notification, claim, demand-planning, BI, delivery-dispatch(배송 CLOSED — 라이더 진행 중 수에서 뺌), delivery-tracking(위치 감시 끝) | deliveryId, orderId, lines[{orderLineId, qty}], method(FACE/CONTACTLESS), evidenceRef, deliveredAt | 사진 증빙 확인 후 서버 완료(자동 완료 없음) | ① |
| `DeliveryHeadingToCustomer` | delivery-execution(2026-11-19 DE-6 — 여러 배송 중 앞 배송 완료 뒤 다음 차례, 배송당 한 번, 단건 없음) | notification(고객 "가고 있어요") | deliveryId, orderId, purpose, riderId, at | 순서 = 묶음 stopSequence → 배정 순 | ① |
| `DeliveryFailed` | delivery-execution(2026-11-17 returnBy SAME_RIDER·OTHER_RIDER — 다른 라이더 회수, 지원센터 직접 확정 가능. 2026-11-18 deliveryType·purpose 더함) | order-orchestration(2026-11-18 ADR-0069 — RIDER_INCIDENT·DAMAGED 면 새 상품 재발송 사가), order, claim(2026-11-17 — 고객 반품 회수 배송만: 첫 실패 자동 재요청, 두 번째 RECOVERY_FAILED. claimId 를 실어 달라는 요청 DD-3), case, notification, delivery-dispatch(배송 CLOSED + custody RIDER 면 반납 배송 FAILED_DELIVERY_RETURN 을 같은 라이더에게 바로 배정 — 2026-11-17), delivery-tracking(위치 감시 끝) | deliveryId, reason, custody(RIDER/STORE), goodsLocation, at | 운영자가 라이더 실패 등록을 확정했을 때(등록만으로는 진행 중 — 2026-11-13) | ① |
| `DeliveryFailureReverted` | delivery-execution(2026-11-19 — 잘못 누른 실패 확정 되돌리기, 확정 뒤 30분 안·상품 라이더 손·반납 받기 전) | delivery-dispatch(원래 배송 다시 열기·반납 배송 취소), delivery-tracking(단계 되돌림), order·order-orchestration(PC 1 — 배송 연결·재발송 사가 취소), notification(재발송 안내 정정) | deliveryId, orderId, riderId, deliveryType, purpose, originalReason, restoredState, returnDeliveryId, revertedAt | 사유 글 없음 | ① |
| `DeliveryIncidentRaised` | delivery-execution | case(R-4 구현 2026-11-16 — 라이더 도움요청·전달 실패 등록 → 지원센터 사건), rider, claim | incidentId, deliveryId, type(DAMAGE/MISDELIVERY/ABSENT/UNSAFE/STATUS_FIX/HELP_REQUEST), category, stage, orderId, mfcId, detail, evidenceRefs | 라이더 도움요청(HELP_REQUEST)·전달 실패 등록(category DELIVERY_FAILURE)·사고 | ① |
| `HelpRequestReplied` | delivery-execution(2026-11-16 — 도움요청 대화의 지원센터 메시지) | notification(라이더 알림함) | deliveryId, requestId, riderId, messageSeq, repliedAt | 상담원 답장 사실(메시지 내용 없음, 라이더 앱 요청 §11-5). 배송 버전을 올리지 않는다 | ① |
| `HelpRequestReopened` | delivery-execution(2026-11-19 0.32.1 — 라이더가 닫힌 도움요청을 대화·재개 요청으로 다시 엶) | case(2026-11-19 R-10 — 그 접수 건에 이력 RIDER_REOPENED 만, 상태 그대로) | deliveryId, requestId, incidentId, riderId, caseId, reopenedAt | 도움요청 다시 열림 | ① |
| `ReturnPickedUp` / `ReturnDelivered` | delivery-execution(2026-11-16 오배송 회수, 2026-11-17 고객 반품 회수 CLAIM_RETURN — claimId, lines = claim 요청 줄; 2026-11-17 전달 실패 반납 FAILED_DELIVERY_RETURN — ReturnDelivered 만, originDeliveryId = 실패한 배송); 2026-11-20 ReturnDelivered 줄마다 `sku`(PC 3 요청 — 고객 반품은 claim 줄, 오배송 회수·전달 실패 반납은 인계 줄, 그 전 인계는 없을 수 있음) | delivery-dispatch(회수 배송 PICKED_UP·CLOSED, 2026-11-16), case(오배송 회수만 — 사건 이력, 2026-11-16), delivery-tracking(ReturnDelivered — 감시 끝), claim(2026-11-17 구독 — purpose CLAIM_RETURN·claimId 만, 수거 수량·회수 축 상태), mfc-inbound(2026-11-19 구독 — ReturnDelivered 세 목적 모두 → 반납 도착 대기 `GET /return-arrivals`, MI-1·MI-2) | returnPickupId, claimId(고객 반품) 또는 caseId·originDeliveryId(오배송 회수), purpose, orderId, mfcId, lines[](오배송 회수 = 원래 배송이 전달한 줄), at | 회수지 수거 / 점포 반납 | ② |
| `ReturnPickupFailed` | delivery-execution | claim(2026-11-17 구독 — 회수 실패 세기, 2번이면 상담원 재연락 `ReturnRecoveryFailed`; 추가 결정: reason RIDER_INCIDENT 는 안 세고 바로 다시 요청, customerReason·사유로 취소 때 회수비 돌려줄지), delivery-dispatch, delivery-tracking | deliveryId, claimId, orderId, mfcId, riderId, reason, customerReason, contactAttempts(횟수), reportedAt, failedAt | 고객 반품 회수 실패 확정(2026-11-17, CL-1 — 라이더 실패 등록 → 지원센터 확정. 상품은 고객에게 그대로, 다시 보낼지는 claim — 2번 실패면 상담원 재연락) | ② |
| `RedeliveryPaymentRequired` | delivery-dispatch(2026-11-16, ADR-0065 — 고객 잘못 다시 배송, 잘못 둔 곳 ↔ 실제 집 직선 200m 이상) | order-orchestration(2026-11-16 4단계 — `redelivery_payment` 결제 시도 → 결제되면 dispatch 배차 풀기, 30분·고객 거절이면 회수 + 상품값 − 9,000원 환불), notification(고객 안내 — N-3 요청, 구독 전) | deliveryId, orderId, caseId, originDeliveryId, straightDistanceM, fee, requiredAt | 다시 배송 결제 필요 | ① |
| `DeliveryStatusCorrected` | delivery-execution(2026-11-17 구현 — RIDER_OPS·OPERATOR_ADMIN 정정, 픽업 전 배정↔가게 도착·픽업 뒤 픽업↔고객 도착만) | delivery-tracking(진행 단계 맞춤 — 2026-11-17), order·audit·notification(구독 전) | deliveryId, from, to, operatorId, basis | 관리자 정정 | ① |
| `RiderEarningRecorded` | delivery-execution | BI, (회계 어댑터) | deliveryId, riderId, earningId, feeBasis, rateVersion, status, calculable, payoutDate | 수행료 근거(환불과 독립) — 완료 때 확정 대기·5시간 뒤 확정, 실패·취소는 산정 불가(2026-11-13) | ① |
| `SettlementStateChanged` | delivery-execution | notification | riderId, periodId, state | 정산 상태 | ② |
| `DeliveryEtaUpdated` | delivery-tracking(2026-11-16 TR-1 구현 — `pmart.tracking.eta.*` 설정 있을 때만) | order(고객 조회), mfc-fulfillment(점원), notification(지연) — 모두 구독 전 | deliveryId, etaPickup?, etaDrop, basis(RIDER_TO_PICKUP+PICKUP_TO_DROP / RIDER_TO_DROP), staleness, computedAt | ETA 갱신(처음·min-change 넘게 바뀔 때) | ① |
| `RiderApproachingCustomer` | delivery-tracking(2026-11-19 TR-4 — 픽업 뒤 전달지 직선 approach-distance-m(300m) 안 처음, 배송당 한 번, 설정 없으면 안 냄) | notification(고객 "곧 도착") | deliveryId, orderId, purpose, distanceM, at | 라이더 좌표 없음 | ① |
| `DeliveryReceiptCheckRequested` | delivery-tracking(2026-11-17 — 사용자 "도착예정 시간보다 20분이 지나면 고객에게 받았는지 물어보고") | notification(고객 알림함 "상품을 받으셨나요?" — 바인딩 trackingEvents, PC 1 설정) | deliveryId, orderId, mfcId, expectedAt, basis(ETA·ARRIVAL_WINDOW), askedAt | 픽업 뒤·전달 전 배송이 도착 예정(없으면 약속 시간대 끝) + 20분, 배송마다 한 번. 고객이 누르면 execution `receipt-confirmations` → DeliveryCompleted(CUSTOMER_CONFIRMED) | ① |
| `RiderLocationStale` | delivery-tracking(2026-11-14 구현, 토픽 delivery.tracking.v1) | case(지원센터 알림 RIDER_LOCATION_STALE — R-6 구현 2026-11-16, case 0.4.0). dispatch 는 받지 않는다(사용자 2026-11-15: 자동 배정 해제 없음) | riderId, deliveryId, lastSeenAt, detectedAt | 위치 단절 — 진행 중 배송(배정 ~ 완료 전)이 있는 라이더 위치가 3분 넘게 없음, 끊김 한 번에 한 번(사용자 2026-11-14) | ① |

## 8. Platform & Support

| 이벤트 | 발행자 | 구독자 | 핵심 payload | 발생 조건 | 단계 |
|---|---|---|---|---|---|
| `SubjectBlocked` | (2026-11-13 세션 단위 `sessionId` — 로그아웃·refresh 재사용: employee-auth·auth·rider-auth) auth(운영자 비활성·비밀번호 초기화, Keycloak 직원 관리 기록 중계; 나중에 고객 정지·rider-service) — employee-auth(직원 퇴사·정지 — 2026-11-13 구현, ADR-0056), Keycloak 관리 기록 중계는 2026-11-13 끔(ADR-0056 7단계) | api-gateway(Redis 차단 목록) | issuer, subject, userType, blockedBefore, reason | 주체 차단 — 그 전에 발급된 토큰 거부(토픽 `platform.subject-block.v1`, 키 = 주체, ADR-0054 ⑤) | ① |
| `EmployeeRegistered` | hr (ADR-0056, 2026-11-13 계약·발행 구현, 토픽 platform.employee.v1) | employee-auth, mfc-capacity(인사 사본 — 작업자 상태, 2026-11-13) | operatorId, employeeNo, keycloakUserId, mailAddress, employmentType, affiliation, startDate, endDate? | 직원 등록·계정 발급 완료 → 연결·ACTIVE | ① |
| `EmployeeSuspended` / `EmployeeReinstated` | hr (ADR-0056, 2026-11-13 계약·발행 구현, 토픽 platform.employee.v1) | employee-auth, mfc-capacity(인사 사본 — 작업자 상태, 2026-11-13) | operatorId, reason(SUSPENDED/CONTRACT_ENDED/…), effectiveAt | 정지(계약 종료 자동 포함)·복귀 | ① |
| `EmployeeLeft` | hr (ADR-0056, 2026-11-13 계약·발행 구현, 토픽 platform.employee.v1) | employee-auth, mfc-capacity(인사 사본 — 작업자 상태, 2026-11-13) | operatorId, effectiveAt | 퇴사 → 상태 LEFT·refresh 무효·`SubjectBlocked` | ① |
| `EmployeeAffiliationChanged` | hr (2026-11-16 BO-01, 토픽 platform.employee.v1 — 발급 끝난 직원·소속이 실제로 바뀔 때) | employee-auth | operatorId, previous{type,code}, affiliation{type,code}, changedAt, previousCenterAccessUntil(이전이 센터일 때 — 다음 날 00:00 Asia/Seoul) | 이전 센터 grant 효력 끝 = previousCenterAccessUntil(새 센터 권한은 요청 → 승인) | ① |
| `CustomerAuthenticated` | auth | customer | accountId, provider, at, (email, emailVerified, name, pictureUrl — ADR-0024) | 소셜 인증 성공(이용 가능≠) | ① |
| `GrantRequested` | hr (ADR-0056 5-4, 2026-11-13 구현, 토픽 platform.employee.v1) | approval | requestId, operatorId, requestedBy, affiliation, lines[{lineKey, role, centers, validFrom, validTo, reason}] | 등록 화면의 요청 권한 → 권한 요청(자동 승인 규칙 또는 심사) | ① |
| `GrantApplied` | employee-auth (ADR-0056 5-4, 2026-11-13 구현, 토픽 platform.auth.v1 — 승인 행마다) | audit(구독 미구현), mfc-capacity(`MFC_WORKER` 만 — 작업자 생성·권한 기간, 2026-11-13) | grantId(<approvalId>:<lineId>), approvalRef, operatorId, roles[], centerScope[], effective | 권한 반영 확인. approval 실행 축은 `ApplicationReceiptRecorded` 로 | ① |
| `GrantEnded` | employee-auth (2026-11-17 EA-1, 토픽 platform.auth.v1 — grant 마다, aggregate Grant v2) | mfc-capacity(예정 — `MFC_WORKER` 작업자 권한 기간 끝을 endedAt 으로) | grantId, approvalRef, operatorId, roles[], centerScope[], endedAt, reason(REVOKED/AFFILIATION_CHANGED) — 회수 사유 글 없음 | 권한이 승인 기간보다 일찍 끝남(회수 = 즉시, 소속 변경 = 이전 센터 권한 다음 날 00:00). 기간대로 끝나는 것·퇴사·정지는 내지 않는다(퇴사·정지는 `EmployeeLeft`·`EmployeeSuspended`) | ① |
| `DelegationStarted` / `DelegationEnded` | auth → ADR-0056 전환 뒤 employee-auth | approval, audit | delegationId, from, to, scope(교집합), until | 대행 | ① |
| `ChangeRequestSubmitted` | approval | 해당 정본(검증용), notification(승인자) | changeRequestId, type, targetRef, expectedVersion, payloadRef | 초안 제출 | ① |
| `ApprovalDecided` | approval | product, pricing, storefront, product-content, center-master, delivery-serviceability, employee-auth·hr(type GRANT, 2026-11-13), search(type SEARCH_SYNONYM, 2026-11-20 ADR-0073), promotion(type PROMOTION, 2026-11-20 ADR-0082), replenishment, claim, cdc-quality, mfc-quality, mfc-capacity, rider, cdc-inventory, mfc-inventory, notification, audit | approvalId, type, decision(APPROVED/PARTIALLY_APPROVED/REJECTED), targetRef, lines[{qty/amount}], conditions, effectiveFrom, expiresAt, approverId, dossierVersion | 심사 확정(정본 반영은 별도 Receipt) | ① |
| `ApplicationReceiptRecorded` | pricing(1차), employee-auth(GRANT, 2026-11-13), delivery-serviceability(SERVICEABILITY, 2026-11-17 RC-1), product(PRODUCT)·product-content(DISPLAY)·center-master(CENTER_MASTER)(2026-11-19 RC-1; 남은 것 mfc-inbound 초기 재고), search(SEARCH_SYNONYM, 2026-11-20 — DB 없음, outbox 대신 Kafka 로 바로), storefront(DISPLAY 진열 편성, 2026-11-20 ADR-0075), promotion(PROMOTION 행사 발효·조기 종료, 2026-11-20 ADR-0082) | approval | approvalId, receiverService, receiptSeq, result(ACCEPTED/APPLIED/PARTIAL/REJECTED/UNKNOWN_RESULT), lines[{lineKey, result, reasonCode}] | 승인 반영 결과 회신(토픽 `platform.approval-receipt.v1`, ADR-0039) | ① |
| `ApprovalWithdrawn` / `Expired` / `Superseded` | approval | 해당 정본, audit | approvalId, reason | 철회·만료·대체 | ① |
| `PolicyVersionApproved` | approval | 정책 소유 서비스(replenishment, mfc-capacity, delivery-capacity, cdc-*, claim 등) | policyRef, ownerService, version, effectiveFrom | 정책 승인(소유 서비스가 발효 후 결과 회신) | ① |
| `OperationRestrictionRequested` / `ResumeRequested` | approval | cdc-quality, mfc-capacity, rider, delivery-dispatch | centerId/riderId, restrictionType, scope, approvalRef | 제한/재개 요청(적용은 정본) | ① |
| `BatchRowResultRecorded` | approval | backoffice-bff, audit | batchId, fileHash, rowNo, result | 대량 변경 행별 결과 | ② |
| `CaseOpened` / `CaseAssigned` / `CaseTransferred` / `CaseClosed` / `CaseReopened` | case(2026-11-16 Opened·Assigned·Closed 발행 구현 — 사건함 0.4.0, Reopened 0.6.0, Transferred 0.7.0) | notification, audit, backoffice-bff, delivery-execution(도움요청 caseId·상태 연결 — Opened·Closed·Reopened, 2026-11-16 DE-CASE), mfc-quality(Opened 의 type MFC_QUALITY_IMPACT 만 — 품질 사고 caseId, 2026-11-20 MQ-2) | caseId, type, refs[{refType, refId}](incidentId·deliveryId·orderId·riderId …), assignee, priority, outcome(Closed) | 사건 생명주기 | ① |
| `MisdeliveryConfirmed` | case(2026-11-16, R-7 — 직원 오배송 확인 `POST /cases` kind MISDELIVERY, 토픽 platform.case.v1, 키 caseId) | delivery-dispatch(회수 배차 DD-2 — 2026-11-16 구현: 회수 배송 RETURN·MISDELIVERY_RECOVERY + 원래 라이더 뺀 회수지 근처 라이더 제안), order-orchestration(재발송 사가 — 2026-11-16: 원장 RESHIPMENT → 재고 예약 → MFC 재발송 작업 → 못 보내는 품목 부분 환불) | caseId, deliveryId, riderId, confirmedBy, confirmedAt, evidenceRefs | 오배송 확인됨 → 인근 다른 라이더로 회수 배차 | ① |
| `ObligationCreated` / `ObligationResolved` | case(2026-11-16 발행 구현 — 계약 0.8.0) | backoffice-bff | obligationId, caseId, kind, qty/amount, ownerService | 의무 등록·해소 | ① |
| `ShiftHandoverCompleted` | case(2026-11-16 발행 구현 — 계약 0.9.0, BO-27) | audit | handoverId, fromOperatorId, toOperatorId, openCases[](받은 건), notReceivedCases[], group | 교대(후임 수락 시) | ② |
| `NotificationDelivered` / `NotificationFailed` | notification | audit, BI | notificationId, channel, recipientRef, result | 발송 결과(읽음≠동의) | ① |
| `AuditEntryRecorded` | 전 서비스 | audit | actor, action, targetRef, before/after ref, reason, result | 운영자 변경 명령 | ① |
| `CenterMasterChanged` / `CenterCalendarChanged` | center-master | order, replenishment, transport-planning, delivery-serviceability, mfc-location, mfc-capacity, cdc-location | centerId, version, changedFields / calendarVersion, windows[] | 센터 기준정보·캘린더 발효 | ① |

---

## 9. 멱등·순서·역순 처리 규칙 요약

| 상황 | 처리 |
|---|---|
| 같은 `(sourceSystem, eventId)` 재수신 | inbox 조회 후 무시(원결과 유지) |
| 다른 eventId, 같은 업무 사실(예: 같은 departureId, 같은 handoverId) | 업무 유일성 제약으로 1회 효과. 수량·차량이 다르면 **충돌 사건**(덮어쓰기 금지) |
| 같은 aggregate의 낮은 version이 늦게 도착 | 상태 전이는 폐기. 단 실물 사실(TransferDeparted, PackageHandedOver, MfcReceiptPosted, DeliveryCompleted)은 폐기하지 않고 발생시각·수신시각 보존 후 대사 |
| 선행 사건 누락(수령은 왔는데 출발이 없음) | 수령을 버리지 않고 원출발 조회·대사. `ReconciliationRequired` |
| consumer 처리 실패 | 재시도(멱등 전제). 소진 시 DLQ + `case-service` 상신. 성공한 다른 consumer의 효과는 유지 |
| 발행자 Outbox 릴레이 지연 | aggregateVersion으로 최신성 판단. 시간 경과로 성공/실패 확정 금지 |
