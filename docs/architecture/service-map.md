# 서비스 책임 경계 (Service Map)

문서 버전: 0.1 · 2026-10-23 · 설계안. 전체 구조는 `architecture-design.md`, 이벤트 상세는 `events.md`.

> 각 서비스에 대해 **소유(정본)** / **소유하지 않음** / **제공 API** / **발행 이벤트** / **구독 이벤트** / **동기 의존** / **단계**를 적는다.
> "로직이 어느 서비스 책임인지 불명확하면 이 문서를 확인한다"(CLAUDE.md). 여기 없는 책임은 질문한다.
> 단계: 1=핵심 판매 루프, 2=공급 루프, 3=확장 (architecture-design §8).

---

## 1. Commerce (9)

### product-service `[1단계]`
- **소유**: 기초상품·상품·SKU(`E-`/`B-` 8자리 발급·재사용 금지), 구성 낱개 수, 카테고리(4단계 상위>하위>칩1>칩2·노드별 필터 패널, 대표 1개 — ADR-0015), 바코드↔SKU, 판매 단위·중량(g)·물류 규격(BOX/PLT 환산·Ti/Hi), 전사 판매 상태(판매중/일시품절/판매중지), 승인 버전.
- **소유하지 않음**: 센터별 취급 ON/OFF·재고(각 inventory), 가격(pricing), 상세 콘텐츠(product-content), 승인 워크플로(approval).
- **API**: 상품/SKU 조회(버전 포함), 구매 상한 검증(`Σ 수량×구성 낱개 수 ≤ 10` per 기초상품), 바코드 해석, SKU 발급 명령(approval 승인 결과 참조), 카테고리 트리.
- **발행**: `ProductVersionPublished`, `ProductSaleStateChanged`, `CategoryChanged`, `SkuIssued`, `ApplicationReceiptRecorded`(PRODUCT 승인 반영 회신, RC-1 2026-11-19).
- **구독**: `ApprovalDecided`(상품/SKU/환산 승인).
- **의존**: auth(행위 검증).

### product-content-service `[1단계]`
- **소유**: 고객용 상품명·사진·설명·보관/반품 안내·판매 옵션 해석, 평점·리뷰 수 집계 투영.
- **소유하지 않음**: 상품 정본·판매 상태(product), 리뷰 원문(review).
- **API**: 상품 상세 조회(승인 변경 5분 내 반영 목표). `mfcId` 를 주면 옵션별 취급·가용 사본(2026-10-31).
- **의존**: mfc-inventory(담당 센터 가용 표시 조회 — 내부 API, 조회 실패≠0).
- **발행**: `ProductContentPublished`, `ApplicationReceiptRecorded`(DISPLAY 승인 반영 회신, RC-1 2026-11-19).
- **구독**: `ProductVersionPublished`, `ReviewPublished/Deleted`, `ApprovalDecided`(콘텐츠 승인 BO-05).

### customer-service `[1단계]`
- **소유**: 고객 계정·프로필·이용 상태(정상/WITHDRAW_PENDING/WITHDRAW_COMPLETED), 약관·마케팅 동의(버전·시점·철회), 주소록·현재 쇼핑 주소(`shoppingContextVersion`), 비회원 배송지, 첫 구매 중복혜택 대조값(소셜 식별, 5년).
- **휴대폰 확인 번호(2026-11-20 ADR-0074)**: 계정마다 확인된 번호 정본(`customer_identity`, 한 번호 한 계정·탈퇴 30일 재가입 막기). auth 가 로그인 단계(AUTH_IDENTITY)에서 내부 API 로 시작·확인을 대신 부른다.
- **소유하지 않음**: 인증·세션·토큰(auth), 주문 이력(order), 권역 판정(delivery-serviceability).
- **찜(2026-11-20 ADR-0081)**: 회원·상품 단위 찜 목록(최대 200 — 정책값), 표시 값은 조회 때 product-content 카드. 재입고 알림은 없음.
- **API**: 프로필/동의/주소록 CRUD, 탈퇴 접수·철회·완료(2026-11-20 ADR-0077 — 같은 소셜 재인증은 auth 내부 API, 완료 전 order·claim 미결 의무 조회), 계정 상태 조회(구매 가능 = ACTIVE, 스위치 `consents.purchase-gate` 가 켜지면 + 필수 약관 현행 버전 동의 — LAN 은 고객 앱 반영 때까지 꺼짐), auth 용 내부 동의 상태·로그인 중 동의 반영(CU-1, 2026-11-17 — 필수 3 + 선택 마케팅 1, 현행 버전은 설정).
- **발행**: `CustomerConsentChanged`, `CustomerWithdrawalRequested`, `CustomerWithdrawalCompleted`, `ShoppingAddressChanged`, `CustomerIdentityVerified`(휴대폰 번호 확정 — 가입 쿠폰 키, 2026-11-21 ADR-0082 5단계).
- **구독**: `CustomerAuthenticated`(auth), `OrderConfirmed`(order — 첫 구매 무료 사용 기록, 2026-10-31), `OrderCancelled`(order — 첫 구매 주문의 전체 취소면 사용 기록 복원, 2026-10-31).
- **의존**: order(탈퇴 가능 여부), delivery-serviceability(주소 판정), S3 버킷 pmart-customer(프로필 사진 사본), 로그인 제공자 사진 호스트(googleusercontent.com — 로그인 뒤 사본 한 번, ADR-0024).

### cart-service `[1단계]`
- **소유**: 장바구니·품목·선택·`cartVersion`(30일 보존), 비회원 장바구니, 로그인 병합 이관(`MERGE_PENDING/PARTIAL/CONFIRMED`), 찜(3차).
- **소유하지 않음**: 재고 예약(장바구니는 예약하지 않음), 견적 확정(order), 가격 정본(pricing).
- **API**: `PATCH /api/v1/carts/{cartId}/items/{itemId}` 등(`Idempotency-Key`, `expectedVersion`; 절대값 설정 명령으로 통일 `[제안]`), 병합, 조회.
- **발행**: `CartItemChangeApplied`.
- **구독**: `OrderConfirmed`(확정 포함분만 1회 정리), `ProductSaleStateChanged`.
- **의존**: product(구매 상한·판매 상태 — 내부 API), mfc-inventory(장바구니 담당 MFC 의 가용 A 표시 조회 — 내부 API, 조회 실패≠0, 2026-10-31 연결), pricing(표시 가격), product-content(표시 이름·이미지). 비회원은 auth 비회원 토큰(ADR-0022).

### review-service `[2단계]`
- **소유**: 리뷰·사진·평점, 작성/수정/삭제 이력(`REV_ELIGIBLE→SUBMIT_PENDING→PUBLISHED→LOCKED(3일)` / `DELETED`), 자격(품목별 배송완료 후 14일 1건).
- **소유하지 않음**: 배송완료 사실(delivery-execution/order), 신고·차단 정책(`[팀 확인 필요]`).
- **API**: 리뷰 CRUD, 자격 조회.
- **발행**: `ReviewPublished`, `ReviewDeleted`.
- **구독**: `DeliveryCompleted`(자격 시작, 분할배송은 품목별 실제 수령 시각).
- **의존**(2026-11-16 1차 구현, ADR-0063): order `GET /internal/orders/{id}`(자격 사본·반품 수량), customer `GET /internal/accounts/{id}/review-author`(가린 별명), S3 버킷 pmart-review(사진). 운영자 숨김·신고 없음.

### search-service `[1단계 기본]`
- **소유**: 검색 색인, 키워드·자동완성·필터 속성, 최근 검색어, 동의어 규칙·오타 사전(ADR-0073 — 색인 `search-synonym-rules`·`search-terms`, DB 없음).
- **소유하지 않음**: 판매 가능 판단(색인 지연≠재고 있음).
- **API**: 검색/필터, 운영 결과 0건 검색어·동의어 규칙 조회(MD·OPERATOR_ADMIN). 동의어 바꾸기는 approval-service 승인(`SEARCH_SYNONYM`)으로만.
- **동의어 승인 반영(ADR-0073 단계 B, 2026-11-20)**: 구독 `ApprovalDecided`(platform.approval.v1, 바인딩 `approvalEvents` — SEARCH_SYNONYM 만, inbox 대신 승인 ID·규칙 버전으로 멱등) → ES 동의어 집합 갱신(재색인 없음) → 발행 `ApplicationReceiptRecorded`(outbox 없이 Kafka 로 바로).
- **구독**: `ProductVersionPublished`, `ProductContentPublished`, `ProductSaleStateChanged`, `PriceVersionEffective`(표시 가격·가격순), `SkuHandlingChanged`(센터 취급 — 상품 carriedMfcIds, 2026-11-05), `SkuDisplayDiscountChanged`(표시 할인 → 색인 `sku-discounts`, 결과 대표 SKU 에 붙임 — 2026-11-21 ADR-0082 4단계) — **search 소유 Logstash 파이프라인**(`services/search-service/src/main/logstash/search-index.conf`, 소비 그룹은 `deploy/lan/elastic/search-index.env`)이 받아 색인한다. 서비스 자체는 조회만(ADR-0031). `CategoryChanged` 는 아직 쓰지 않는다(이름 경로는 공개 트리 조회).
- **의존**: Elasticsearch 8.18 + nori(색인, ADR-0031), Logstash(색인 주체), product-content(공개 카드 API — Logstash 가 이름·이미지 보강), product(공개 카테고리 트리). 취급 여부는 동기 조회가 아니라 `SkuHandlingChanged` 색인 사본(2026-11-05). 표시사항 값 nutrition·allergens(products-v9 계약 0.11.0 — 카드 사본, 영양 필터·"땅콩 함유" 검색, 2026-11-23). 목록 판매 가능 여부 `availability`(계약 0.10.0, 2026-11-23)는 mfcId 를 줄 때 검색 요청마다 product-content 카드 일괄 조회(`GET /product-contents?productIds=&mfcId=` — 그 뒤 mfc-inventory 가용, search 기준 2 hop) — 응답 1초 넘거나 실패면 UNKNOWN.

### storefront-service `[1단계 기본]`
- **소유**: 홈 진열·배너·테마·랭킹/신상품 탭 구성과 발효 버전, 추천(3차).
- **소유하지 않음**: 상품·가격·재고 정본.
- **발행**: `ProductRankingSignalsChanged`(ADR-0072), `ApplicationReceiptRecorded`(진열 편성 DISPLAY 승인 반영 회신, 2026-11-20 ADR-0075).
- **구독**: `ApprovalDecided`(전시 승인 BO-05 — DISPLAY·targetService storefront-service 만, 2026-11-20 ADR-0075 구현), `ProductVersionPublished`, `ProductContentPublished`, `ProductSaleStateChanged`, `PriceVersionEffective`, `SkuDisplayDiscountChanged`(진열 할인가, 2026-11-21 ADR-0082 4단계).
- **의존**: product-content(공개 카드 API — 콘텐츠 사건에 없는 이름·이미지 보강, ADR-0021. 2026-11-23 계약 0.13.0 부터 mfcId 를 준 홈·탭·섹션 응답마다 카드 일괄 조회로 목록 판매 가능 여부 `availability` — 실패면 UNKNOWN), S3 호환 저장소(배너 이미지, 버킷 pmart-storefront — ADR-0016).

### pricing-service `[1단계]`
- **소유**: SKU별 기본 판매가격 버전(전사 동일, 센터 예외 없음), 효력일, 견적 계산(기본가·선택 오퍼→기간 프로모션→상품쿠폰→장바구니쿠폰 순서; 2026-11-21 ADR-0082 부터 기본가→기간 할인→장바구니 쿠폰, 오퍼·상품쿠폰은 아직).
- **소유하지 않음**: 최종 청구액·동결(order 초안), 배달팁·봉투값·최소주문(order), 할인 권리(promotion).
- **API**: 가격 버전 조회, 견적 계산(품목 금액·안분), 가격 일괄 등록(seed-v1 basePrice → 운영자 설정 → PRICE 승인 요청 본문, 2026-11-03). 원천 키 → SKU 는 product-service `/internal/source-link-lookups` 로 조회한다.
- **발행**: `PriceVersionEffective`, `ApplicationReceiptRecorded`(가격 승인 반영 회신, ADR-0039).
- **구독**: `ApprovalDecided`(가격 승인 BO-04: MD팀장 발의→가격/영업총괄 승인).

### promotion-service `[활성 — 2026-11-20 ADR-0083]`
- **소유**: 행사(기간 할인 PRICE_DISCOUNT·장바구니 쿠폰 COUPON)·대상(SKU·카테고리·진열 테마 THEME — 2026-11-24)·승인 반영, 지정 발급 쿠폰의 받을 고객(만들 때 정함, 시작 때 발급 — 2026-11-24), 쿠폰 발급(내려받기·지정·가입 축하)·점유·사용 확정·해제·복원, 행사 수량(총 수량·1인 한도·발급/사용 수).
- **소유하지 않음**: 견적 계산·안분(pricing), 주문 금액 확정·스냅샷(order), 점유·확정·복원의 사가 조율(orchestration), 승인 심사(approval).
- **API**: 직원 행사 만들기·고치기·제출·조기 종료 제출·지정 발급·쿠폰 발급 기록(고객·행사 기준 — 2026-11-24), 고객 쿠폰 행사 목록·내려받기·내 쿠폰함, 내부 SKU 기간 할인 조회·쿠폰 계산·점유/확정/해제/복원.
- **발행**: `ApplicationReceiptRecorded`(PROMOTION 승인 반영 회신), `SkuDisplayDiscountChanged`(표시 할인 — 토픽 `commerce.sku-display-discount.v1` compact, 2026-11-21 ADR-0082 4단계), `CouponIssued`(서버가 준 쿠폰 — notification 쿠폰 도착 알림, 2026-11-21 5단계).
- **동기 조회**: storefront `GET /internal/storefront/themes`(1분마다 — 지금 게시 진열 테마 사본, 기간 할인 THEME 대상, 2026-11-24). 진열 편성의 정본은 storefront.
- **구독**: `ProductVersionPublished`(SKU → 카테고리 사본), `PriceVersionEffective`(기본가 사본 — 표시 할인 고르기, 2026-11-21), `ApprovalDecided`(PROMOTION), `CustomerIdentityVerified`(가입 축하 쿠폰, 2026-11-21 5단계), `OrderCancelled`(DISPATCH_UNAVAILABLE → 보상 쿠폰 CL-5, 2026-11-21).
- **아직**: pricing·order·orchestration 연결, 화면 할인가 표시, 가입 쿠폰 자동 발급(ADR-0083 나누어 만들기 2~5). 첫 구매 무료배달 자격은 그대로 order-service.
---

## 2. Order & Payment (5)

### order-service `[1단계]`
- **소유**: 구매 의도·주문 초안(`draftId`, `quoteVersion`, `quoteExpiresAt` 10분), 주문·주문행·불변 스냅샷(상품명·규격·단가·할인 안분·중량·배송지·연락처·요청·정책 버전·품절 처리 선택·동의), 청구액 확정(최소 15,000원 판정, 배달팁 4,000/3,000/0, 봉투값 100원, 첫 구매 자격 점유 1차), 주문 상태·생명주기, 주문행 수량 대사(원수량 = 미이행 + 유효 인계 누계 + 출고 전 취소 누계), 이행 계획·배송 단위(10,000g 초과 분할), 예약 시간 변경 버전, 고객/운영자 조회 모델, SCM 제공용 확정 수요.
- **소유하지 않음**: 사가 진행 상태(orchestration), PG 거래(payment), 재고·슬롯 점유(mfc/delivery), 현장 사실(mfc-fulfillment), 배송 사실(delivery-execution), 클레임 심사(claim).
- **API**: 초안 생성(`purchaseIntentId`+`requestId` 멱등; 같은 키 다른 본문=충돌), 초안/주문 조회, 결제 진입 요청(orchestration 위임), 예약 시간 변경, 탈퇴 가능 여부, 리뷰 자격 근거(품목별 배송완료 시각), 확정 수요 조회(SCM).
- **발행**: `OrderDraftCreated`, `OrderConfirmed`, `OrderLineQuantityChanged`, `OrderPreparationStarted`(MFC 결과 반영), `OrderPartiallyDelivered`, `OrderCompleted`, `OrderCancelled`, `OrderExpired`, `ReservationTimeChanged`, `CustomerCommitmentChanged`(SCM용).
- **구독**: `PackageHandedOver`, `DeliveryCompleted/Failed`, `ClaimDecided`, `RefundResult`, `PriceVersionEffective`(초안 재견적 안내), `OperationRestrictionApplied`. 2026-11-17 실제 구독 추가: `ClaimClosed`(order.claim.v1 — 반품 종결의 행별 반품 수량 → 주문 행 `returnedQty`, O-7).
- **의존**: product, pricing, customer, delivery-serviceability, fulfillment-routing, center-master, auth.

### order-orchestration-service `[1단계]`
- **소유**: 사가 인스턴스·단계·타임아웃·재시도 횟수·보상 로그(이번 시도 신규 확보 자원 ID), 결과 불명 추적(1분×5회→상신→15분), 대사 불일치 사건, 운영자 수동 개입 기록.
- **소유하지 않음**: 주문 상태(order), 결제·재고·슬롯·작업의 성공 확정(각 정본).
- **사가**: S1 결제 진입·확정, S2 이행·결품·부분취소, S3 취소 vs 인계, S4 예약 시간 변경, 오배송 재발송(2026-11-16 — `misdelivery-recovery-reshipment.md`) (`docs/business/flows/`).
- **API**: 사가 조회(원요청 ID), 수동 재처리(승인 필요: 원결과 재조회/미전달 재전송/보상 실행 — 각각 분리).
- **발행**: `CheckoutSagaStateChanged`, `CompensationExecuted`, `ReconciliationRequired`.
- **구독**: `OrderConfirmed`(S2 시작, 2026-11-07), `MisdeliveryConfirmed`(case — 재발송 사가, 2026-11-16), `PaymentApproved/Failed/Unknown`, `InventoryHeld/Released/Expired/Blocked`, `CapacityHoldResult`, `SlotHoldResult`, `WorkAccepted/Rejected`, `LineShortageReported`, `PackageHandedOver`, `WorkStopResult`.
- **의존(동기)**: mfc-inventory, mfc-capacity, delivery-capacity, payment, mfc-fulfillment, order, claim, delivery-dispatch.

### fulfillment-routing-service `[1단계 얇게 / 3단계 완성]`
- **소유**: 이행 후보 MFC 평가 결과·배정 계획·재배정 계획(3차). 1차는 "배송지→담당 MFC 1곳 + 운영 제한·취급 적격 사유" 판정 함수.
- **소유하지 않음**: 권역 매핑 정본(delivery-serviceability), 재고(mfc-inventory).
- **API**: 이행 MFC 판정.
- **의존**: delivery-serviceability, center-master, mfc-inventory(취급·제한 조회).

### claim-service `[1단계: 취소·반품 접수 / 2단계: 교환·회수 완성]`
- **소유**: 취소·반품·교환 신청·심사·승인 범위, 자격 판정(일반 7일, 신선 24h, 하자·오배송·광고상이 교환 3개월 또는 인지 30일; Asia/Seoul 실제 수령시각 기준, 달력 월 계산), 신청 가능량(전달량 − 완료/진행 점유), 회수 지시·검수 결과 연결, 환불 의도·금액(원가격·원안분, 봉투값 전체 취소만, 교환 왕복 9,000원), 환불 상태(접수→심사/승인→실행 대기→PG 결과 확인→완료/실패/확인 중), 쿠폰 복원 요청(3차), 철회.
- **소유하지 않음**: PG 실행(payment), 회수 수행(delivery), 검수·재입고(mfc), 주문 수량 정본(order).
- **API**: 취소 접수(→orchestration S3), 반품/교환 접수·심사, 환불 상태 조회, 운영자 심사(BO-12~14). **인계 뒤 회수 반품(2026-11-17, CL-1·D-7)**: 고객센터 접수(`POST /claims` type RETURN — CS_AGENT·OPERATOR_ADMIN), 회수비 9,000원 결제 시도(고객 `…/fee-payment-attempts`, 회사 잘못 사유면 면제), 회수 실패 뒤 상담원 조치(`…/recovery-actions` — 다시 회수(전체 3번)·취소(낸 회수비 돌려줄지 규칙·상담원 판단)·받은 만큼만 끝내기, 2026-11-17 추가 결정), 반품 기한(접수 + 7일 — 지나면 자동 취소). 회수비는 상품 환불과 별개 결제(payment 결제 시도 `RTF-<claimId>`) — claim 이 소유(금액·면제·결제 대기·기한), 결제 실행은 payment.
- **발행**: `CancellationRequested`, `ClaimOpened`, `ClaimDecided`, `ReturnPickupRequested`, `RefundIntentCreated`, `ExchangeReshipmentApproved`, `ClaimClosed`, `ReturnFeePaymentRequested`·`ReturnRecoveryFailed`(2026-11-17).
- **구독**: `WorkStopResult`, `PackageHandedOver`, `ReturnPickedUp/Delivered`, `ReturnInspected`, `RefundResult`, `OrderLineQuantityChanged`. 2026-11-17 실제 구독 추가: `DeliveryCreated`(purpose CLAIM_RETURN)·`ReturnPickedUp/Delivered`·`DeliveryFailed`(delivery.delivery.v1), `ReturnItemReceived`(mfc.inbound.v1), `ReturnInspected`(mfc.quality.v1), `PaymentApproved`(회수비 결제 시도만).
- **의존**: order, payment, delivery-execution(전달 시각), auth.

### payment-service `[1단계]`
- **소유**: 결제 시도(`paymentAttemptId`), PG 거래(`pgTransactionId`)·승인액·통화·상태, 전체/부분 취소·환불 거래·멱등키, 성공 누적·진행 중·불명 점유, 추가 환불 가능 한도, 원거래 조회 이력, 보상 취소, 토스페이먼츠 어댑터(주문서형, 결제 인증=주류 인증 통합), 콜백 검증, PG 대사.
- **소유하지 않음**: 환불 의도·금액 산정(claim/order), 주문 확정(order).
- **API**: 결제 시작(원시도 반환), 승인 결과 확인·원거래 조회, 전체/부분 취소(원거래·금액·근거·멱등키; 한도 초과 거절), 보상 취소, 거래 조회.
- **발행**: `PaymentApproved`, `PaymentFailed`, `PaymentResultUnknown`, `PaymentMismatch`, `RefundResult`(성공/실패/불명), `CompensationCancelResult`.
- **구독**: 없음(명령 수신형). 콜백은 외부 webhook.
- **의존**: 토스페이먼츠, auth. PG 비밀 로그 금지.

---

## 3. SCM (4)

### replenishment-service `[2단계]`
- **소유**: 공급 경로·정책(보호기간 목표 기본, Min/Max 선택, 우선순위 `센터+SKU→그룹→전역`), planRun 스냅샷(`asOf`·원천 버전·`CALCULABLE/REFERENCE_ONLY/INPUT_PENDING`), 수량 사전 H/C/I/P/F/SS/S/IP/N, 보호기간·시간대별 부족, 배수·상한·추천(W/B/K/Q), 요청 접수·버전·중복 검사, 배분 후보(수동/요청순/위험/비례), **사업승인 TRF 정본**(승인량 Qa·조건·버전·승인자), 실행 추적(확보/편성/출발/수령/준비 각 상태), 보존식 `A0 = D + Cpre + Uclosed + Uopen`, 미출발 잔량 종료, 추가 공급 판단(SCM-D11), 실적 지표 원천.
- **소유하지 않음**: 재고 수량(cdc/mfc inventory), 운송 편성(transport), 현장 수령(mfc-inbound), 예측 모델(demand-planning), 승인 워크플로 UI(approval), 공급사·PO(3차).
- **API**: 보충 현황 조회, 추천 조회, 공급 요청 접수(`SupplyRequest`), 심사 후보 조회, 사업승인 확정(approval 결과 수신), 변경/취소/잔량 종료, 공급 상태 조회(OMS A.2.4).
- **발행**: `ReplenishmentRecommended`, `SupplyRequestReceived`, `TransferApproved`(=ApprovedTransfer), `TransferChanged`, `TransferCancelled`, `TransferResidualClosed`, `SupplyShortfallAlerted`, `SupplyStatusChanged`(OMS용).
- **구독**: `InventorySnapshotPublished`(cdc/mfc), `CustomerCommitmentChanged`(order), `ReceivingCapabilityChanged`(mfc-capacity), `ForecastVersionPublished`, `AllocationResult`, `TransportFeasibilityResult`, `TransferDeparted`, `MfcReceiptPosted`, `TransitReconciled`, `TransportIncidentRaised`, `QualityBlocked`, `ApprovalDecided`.
- **의존(동기)**: cdc-inventory(공급가능량), transport-planning(운송 가능성), mfc-capacity(수령창), center-master.

### demand-planning-service `[2단계]`
- **소유**: 예측 버전·버킷·기준(주문확정/실제인계/전달 중 선택)·신뢰도·품절 구간 표시, 승인 수동 수요, 이동평균 등 초기 모델.
- **소유하지 않음**: 확정 고객 약속 C(order), 보충량(replenishment).
- **API**: 예측 조회, 수동 수요 입력.
- **발행**: `ForecastVersionPublished`.
- **구독**: `OrderConfirmed`, `OrderLineQuantityChanged`, `PackageHandedOver`(실적), `ProductSaleStateChanged`.

### supplier-service / procurement-service `[3단계 — 1차 제외]`
- 문서 범위 밖(SCM 0.1 제외 항목). 경계·계약명만 예약: `SupplierRegistered`, `PurchaseOrderIssued`, `AsnReceived`. CDC 실입고 확정은 SCM이 하지 않는다(cdc-inbound 2차+).

---

## 4. CDC WMS (5)

### cdc-inventory-service `[2단계]` — CDC 재고 **단일 쓰기 주체**
- **소유**: 위치·LOT·품질상태별 On-hand, 이관 예약 R(`0≤R≤E≤장부`), Held, 안전량 S, 가용 `max(0,E−R−S)`, LOT(식별 UNVERIFIED/VERIFIED/INVALID, 소비기한 T/D, FEFO 최소 잔여일수), 초기 보유 INITIAL_BALANCE(승인 1회), 승인 TRF 다LOT FEFO 할당/해제(`allocationId`), **TOUT 1회 전기**(현물−q·예약−q·Transit+q 한 거래), 원출발 Transit·대사(`q = r + l + t`), TIN 확인·정정 δr, 운송 손실·폐기·ADJ 집행(승인 참조), 실사 차이, 일일 수불 대사(`시작 + 조정증가 − 출발 − 폐기 − 조정감소 = 종료`), 공급 판단용 사실 조회, 센터 취급 활성.
- **소유하지 않음**: LOC 마스터·공간(location), 작업(inbound/outbound), 품질 판단(quality), MFC 재고, 상품 정본(product), 승인 자체(replenishment/approval).
- **API**: 공급가능량·적격 현물·예약·Transit 조회(SCM), 할당/해제(outbound), 출발 전기(outbound 검증 후), 이동 반영(inbound), 상태 전환/폐기 차감(quality), 조정 집행, 원결과 조회(`commitId`).
- **발행**: `AllocationResult`, `TransferDeparted`(TOUT), `TransitReconciled`, `TransferAckStatus`, `QuantityPostingResult`, `InventorySnapshotPublished`, `LotBlocked`(quality 요청 반영 후), `InventoryAdjustmentPosted`.
- **구독**: `ProductVersionPublished`, `MfcReceiptPosted`, `ReceiptCorrectionPosted`, `HoldRequested/Released/DisposalApproved`(quality), `TruckDepartureValidated`(outbound).

### cdc-inbound-service `[2단계: 적치·MOV·보충 / 3단계: ASN·실수령·검수]`
- **구현 상태(2026-11-26, 0.2.0)**: 적치 지시·자동 보충(최소 이하)·가상 설비 다음 작업 받기·결과 반영(재고 move-postings·location 공간 예약 소비). 흐름 `docs/business/flows/cdc-putaway-replenishment.md`. 적치 후보 자동 산출·출발 점유·사건 발행·ASN 은 다음.
- **소유(1~2차 재정의 `[제안]`)**: 적치 지시(CREATED→RESERVED→DISPATCHED→IN_PROGRESS→PARTIALLY_COMPLETED→COMPLETED / FAILED→RETRY_READY / CANCELLED), 내부 이동 MOV, 피킹 위치 최소 이하 자동 보충(필요량·지시량 산식), 적치 예외·취소 경합, 가상 설비 어댑터 결과 검증(`WorkResult` 증가분 멱등). 3차에 ASN·실수령·검수·입고 차이 추가.
- **소유하지 않음**: 수량(inventory), 공간 예약 정본(location), 품질 판정(quality).
- **사가 S7**: location 공간 선점 → inventory 이동 반영 → 작업 완료. 실패 시 이번 선점분만 해제.
- **API**: 적치/보충 작업 CRUD, 작업자 실행 결과 등록.
- **발행**: `PutawayTaskStateChanged`, `ReplenishmentMoveCreated`.
- **구독**: `QuantityPostingResult`, `CapacityReservationResult`(location), `WorkResult`(어댑터), `LocStateChanged`.

### cdc-location-service `[2단계]`
- **소유**: 센터 시설 수용조건, 7구간 LOC 마스터·상태(DRAFT/ACTIVE/BLOCKED/INACTIVE/RETIRED)·온도·사용 제한, 적치 후보·회전배치 적격(NO_ELIGIBLE_LOCATION 등 사유), 공간 용량 예약(`점유 + 미완료 예약 + 신규 ≤ 허용량`), 도크·상차 위치 자원.
- **소유하지 않음**: 수량(inventory), 작업(inbound/outbound).
- **API**: LOC 조회/등록, 적치 후보 산출, 공간 예약/해제, 도크 예약(TMS).
- **발행**: `LocStateChanged`, `CapacityReservationResult`, `DockReservationResult`.
- **구독**: `ApprovalDecided`(센터 기준정보 BO-06 → LOC 생성은 별도 현장 설정).

### cdc-outbound-service `[2단계]`
- **소유**: 승인 TRF 인수(1요청1이관1출발, 같은 버전 다른 본문=충돌), Wave(DRAFT→PLANNED→RELEASED→IN_PROGRESS→WORK_DONE→CLOSED)·Task(CREATED→ASSIGNED→ACCEPTED→IN_PROGRESS→PARTIAL/DONE/EXCEPTION/CANCELLED), 실피킹·부족 보고·재할당, 분류·검품(BOX SEALED/PLT READY), **LPN 구성·계보** `[제안]`, 출발 준비 적격(TMS/MFC 조건 소비), 가상 상차 LOADED(≠출발), 출발 사실 검증(`TruckActuallyDeparted` 대조 → inventory 전기 요청), 미출발 취소·축소·실물/권리 정리, 출발 매니페스트(`shipmentManifestId`). 불변식 `상차 ≤ 검품합격 ≤ 분류 ≤ 실피킹 ≤ 유효할당 ≤ 승인`.
- **소유하지 않음**: 수량 전기(inventory), 차량·운행(TMS), 승인(replenishment).
- **API**: Wave/Task, 피킹·검품·상차 결과, 출발 매니페스트 조회(MFC/TMS), 미출발 취소.
- **발행**: `WaveStateChanged`, `PickShortageReported`, `TruckDepartureValidated`(→inventory), `TransferShipmentManifestIssued`, `OutboundDiscrepancyRaised`.
- **구독**: `TransferApproved/Changed/Cancelled`, `AllocationResult`, `TripAssigned/Updated`, `TruckActuallyDeparted`, `QuantityPostingResult`, `LotBlocked`.
- **의존**: cdc-inventory(할당/해제/전기), cdc-location, auth.

### cdc-quality-service `[2단계]`
- **구현 상태(2026-11-26, 0.2.0)**: 보류·해제 요청/결정·폐기 요청/결정/실행만(경로 `/cdcs/{cdcId}/quality-holds`·`/cdcs/{cdcId}/disposal-approvals` — MFC 품질 경로와 겹치지 않게). 수량은 cdc-inventory `quality-postings`. 사건 발행은 아직(재고 사건이 사실을 알림), 리콜·FEFO 예외·운영 제한·LOT 계보는 다음.
- **소유**: 품질 보류·격리·해제·리콜 판단과 승인 기록(본사 품질 책임자), 폐기 승인(실행은 inventory), FEFO 예외 이중 승인, 온도 이탈 판정, LOT 계보 추적, CDC 운영 제한(CDC-F-031) `[제안]`.
- **소유하지 않음**: 수량 변경(inventory), 상품 마스터(product).
- **API**: 보류/격리/해제/폐기 승인 명령, 운영 제한 설정/재개(approval 승인 결과 참조).
- **발행**: `HoldRequested`, `HoldReleased`, `DisposalApproved`, `RecallIssued`, `OperationRestrictionApplied/Lifted`(CDC).
- **구독**: `TemperatureExcursion`(cold-chain), `ApprovalDecided`(품질 승인 BO-15), `TransportIncidentRaised`.

---

## 5. MFC (6)

### mfc-inventory-service `[1단계]` — MFC 재고 **단일 쓰기 주체**
- **소유**: Balance(센터·위치·SKU·LOT·품질상태 PENDING_INSPECTION/NORMAL/QUARANTINED/DISPOSAL_PENDING·업무상태 UNSTOWED/STORED/PICKED/PACKED/CANCEL_RETURN_PENDING·수량·버전), LedgerEntry, ReservationPart(HAND/FUTURE; HELD→CONFIRMED/RELEASED/EXPIRED/BLOCKED, CONFIRMED→CONSUMED/RELEASED/BLOCKED), 수량 기호 H/Q/E/R/A(`0≤R≤E≤H, A=E−R`), MoveAllocation(물량 M + **공간 V 잔여량**), TIN 전기(`H+q`, E 제외→검수 후 E+), 검수·적치 결과 반영, **인계 전기**(`H−q, E−q, R−q` 한 사건), 피킹 전/후 취소 복귀, 품질 격리 반영(Q+q, E−q, R→BLOCKED, 이행부족 D), 회수품 인수(H+q, Q+q), 폐기 실행(승인 잔량 내), 실사 CNT/ADJ, 모든 변경 `commit_id`, `inventory_apply_status`(CAPTURED→SUBMITTED→PROCESSING→APPLIED/REJECTED/UNKNOWN_RESULT), 센터 취급 ON/OFF, 운영 제한(센터 재고 측).
- **소유하지 않음**: 작업·포장·인계 fence(fulfillment), 수령 사실(inbound), 품질 판정(quality), 위치 마스터(location), 주문 취소 판단(order/orchestration), CDC Transit(cdc-inventory).
- **API**: `ReserveInventory`(결제 직전, 행별 `요청량≤A`, 동일 SKU 합산, 10분 TTL `[Q-04 결정 필요]`), 예약 확정/해제/조회, `ConfirmReceipt`(inbound), `CommitTaskExecution`(검수/적치/피킹/패킹 위치 변경), `CommitPackageHandover`(fulfillment), 격리/해제/폐기 반영(quality), 조정 집행, 가용 조회(cart/commerce), 스냅샷(SCM), 원결과 조회(`commit_id`/`request_id`).
- **발행**: `InventoryHeld`, `InventoryReleased`, `InventoryHoldExpired`, `ReservationBlocked`(품질, 영향 주문 포함), `MfcReceiptPosted`(TIN COMMITTED 후), `ReceiptCorrectionPosted`, `PackageHandedOverPosted`(재고 측 사실; fulfillment의 `PackageHandedOver`와 commit_id로 연결), `InventoryExecutionApplied`, `InventorySnapshotPublished`, `StockAdjusted`, `StockHoldResolved`(수령장 분실 조사 보류 정리, 2026-11-20), `MfcStockAvailable`(재입고 알림용, 3차), `SkuHandlingChanged`(센터 취급 ON/OFF → search, 2026-11-05), `MfcLotStockChanged`(LOT 지금 모습 → search 운영자 재고 검색, 토픽 mfc.lot-stock.v1, 2026-11-25).
- **구독**: `ProductVersionPublished`, `TransferDeparted`(InboundPromise 투영은 inbound가 보유; inventory는 T 참조), `QualityDecisionMade`(quality).
- **오류 코드**: `INSUFFICIENT_STOCK, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT, INVALID_STATE, CONFIG_REQUIRED, UNKNOWN_RESULT` + 응답 `applied_qty, commit_id, retryable, next_action`.

### mfc-fulfillment-service `[1단계]`
- **소유**: PICK/PACK/DISPATCH Task(WAITING→READY→ASSIGNED→IN_PROGRESS→COMPLETED / BLOCKED / FAILED / CANCELLED / CLOSED_PARTIAL; `계획 = 완료 + 취소 + 잔량`), Shipment(SHP: PREPARING→READY→PARTIALLY_HANDED_OVER→HANDED_OVER / CANCELLED / CLOSED_PARTIAL, `hold_reason`), Package(PKG: OPEN→SEALED→HANDED_OVER / INVALIDATED / VOIDED), PackageContent, HandoverFact(HOV), **인계 fence**(`AuthorizePackageHandover` → `HANDOVER_IN_PROGRESS` + `handover_authorization_id/fence_version`; `CommitPackageHandover` → inventory 커밋 후 HANDED_OVER), 포장 QR, 결품·파손 보고(EXC), 작업 중지 요청 처리(포장별 중지 가능/인계 진행/인계 확정 판정 회신), 준비 시작 확정(→order `상품준비`), 패킹 소요시간·봉투 수·주류 여부·부피/무게(배차 요청 정보), 점원 화면용 ETA 투영.
- **소유하지 않음**: 수량(inventory), 취소 판단(orchestration/claim), 배차(delivery-dispatch), 작업자 배정 정본(capacity), 주문 상태(order).
- **API**: `ConfirmOrder`(작업 요청 수락/거절; 재전달 시 중복 생성 없음), `StopWork`(포장별 판정; 작업 지시 ID 를 모를 때는 주문 단위 `/internal/stop-requests` — 작업 지시 전이면 전량 STOPPED·중지 기록, 2026-10-31), `AuthorizePackageHandover`/`CommitPackageHandover`(라이더 QR; 배정 라이더·배송번호·포장번호 일치, 중복 스캔·타 주문·타 라이더·기인계 차단), 피킹/패킹 결과 등록, `QueryHandoverResolution`, 재포장(새 버전·구 라벨 무효).
- **발행**: `WorkAccepted`, `WorkRejected`, `PreparationStarted`, `LineShortageReported`, `PackingFinished`, `ShipmentReady`, `PackageHandedOver`, `WorkStopResult`, `PackageInvalidated`, `TaskProgressed`.
- **구독**: `OrderConfirmed`(또는 동기 ConfirmOrder), `InventoryExecutionApplied`, `DeliveryAssigned/AssignmentChanged`, `ReservationBlocked`, `OperationRestrictionApplied`.
- **의존(동기)**: mfc-inventory(커밋), mfc-capacity(배정 조회), auth.

### mfc-inbound-service `[1단계 최소 / 2단계 완성]`
- **소유**: RECEIVE/INSPECT/PUTAWAY/QUARANTINE_MOVE Task(격리 이동 2026-11-20), InboundPromise(TRF·TOUT·P·ETA 투영, EXPECTED→RECEIVING→TIN_POSTED), 도착·인계 증빙 연결(TMS), `ConfirmReceipt` 요청(정상/격리/미판정 수량 → inventory TIN), 수령 차이(미수령·과부족), 회수품 실물 반입(→inventory H+/Q+, quality 검수 대기), 초기 보유 반입(1차), **로컬 공급 요청 WK-14**(DRAFT→SUBMITTED→ACKNOWLEDGED; 추천 참조·사유·희망 사용준비 시각) `[제안]`.
- **소유하지 않음**: 수량(inventory), 수령창·수용량 정본(capacity), 품질 판정(quality), 공급 승인(replenishment). **임시(2026-11-24, MI-4 — 사용자 결정 "B")**: SCM 이 생기기 전까지 로컬 공급 요청의 승인·거절(센터 관리자 → 입고 예정 EXPECTED)과 발주 수량 추천(인계 실적 이동평균 + 정책 표 `supply_policy`)을 이 서비스가 한다 — replenishment·demand-planning 이 생기면 그쪽으로 옮긴다.
- **API**: 입고 예정 조회, 도착 등록, 수령 등록(`ConfirmReceipt`), 공급 요청 제출/변경/철회·(임시) 승인/거절, (임시) 발주 수량 추천 조회, 회수품 반입 등록.
- **발행**: `SupplyRequestSubmitted`, `InboundArrivalRecorded`, `ReceiptDiscrepancyReported`, `ReturnItemReceived`.
- **구독**: (임시, MI-4) `PackageHandedOverPosted`(인계 실적 일별 합), `TransferDeparted`, `TransferShipmentManifestIssued`, `TripAssigned/Updated`, `TransportHandoverEvidenceRecorded`, `TransportEtaUpdated`, `MfcReceiptPosted`(자기 요청 결과), `ReturnDelivered`(delivery), `ReplenishmentRecommended`, `TransferApproved/Changed/Cancelled`, `ReturnInspected`·`QualityDecisionMade`(RELEASE)·`DisposalApproved`(mfc-quality — 재판매·해제분 적치, 폐기분 격리 이동, 2026-11-20).

### mfc-location-service `[1단계]`
- **소유**: Center 내부 Area/Location 마스터, 온도구역, 격리·출하대기 구역, 로케이션 코드 문법 `[Q-03 결정 필요]`, 위치 상태·폐쇄(실사 잠금 협업), 공간 잔여 계산 입력(치수·슬롯 수).
- **소유하지 않음**: 수량·공간 예약 상태(inventory가 V 보유), 센터 마스터(center-master).
- **API**: 위치 조회/등록/상태 변경, 적치 후보.
- **발행**: `MfcLocationStateChanged`.
- **구독**: `ApprovalDecided`(센터 기준정보), `CenterMasterChanged`.

### mfc-capacity-service `[1단계]`
- **소유**: 작업자·장비·근무·자격·위임 설정, CapacitySnapshot(단계별 능력을 같은 수용 단위로 환산한 최소값: 피킹·패킹대(기본 4·최대 8)·보관 공간·인력), CapacityHold(HELD→CONFIRMED/RELEASED/EXPIRED; CONFIRMED 해제는 근거 필수), 작업 배정(assignment_version, 활성 담당 1명)·OR-Tools 계획(PROPOSED→COMMITTED / NO_SOLUTION / STALE / RECOVERY_REQUIRED)·발행(WK-02/09/10) `[제안]`, 수령창·수용량(ReceivingCapability, 1회 입고 상한 `[가정]`), 운영 제한(접수 중지·용량 축소) 설정.
- **소유하지 않음**: 재고 R(inventory; CapacityHold와 독립), 고객 배송 슬롯(delivery-capacity), 작업 실행(fulfillment/inbound).
- **API**: `CapacitySnapshot`/`HoldCapacity`/확정/해제(orchestration), 수령창·수용량 조회(SCM/TMS), 하차면 예약(TMS), 배정 조회(fulfillment), 운영 제한.
- **발행**: `CapacityHoldResult`, `CapacityReduced`(영향 주문 포함), `ReceivingCapabilityChanged`, `AssignmentPublished`, `OperationRestrictionApplied/Lifted`(MFC).
- **구독**: `TaskProgressed`, `ApprovalDecided`(운영 중지·재개 BO-08), `CenterMasterChanged`, 직원 사건 `EmployeeRegistered·Suspended·Reinstated·Left`(인사 사본)·`GrantApplied`(`MFC_WORKER` — 작업자는 그 센터 작업자 권한이 붙을 때 생성, 센터 관리자 ≠ 작업자, 2026-11-13).
- **의존**: OR-Tools Java 바인딩(서비스 내부 `infrastructure` 어댑터).

### mfc-quality-service `[1단계 최소 / 2단계 완성]`
- **소유**: 품질 판정(PENDING→NORMAL/QUARANTINED), 격리 해제 승인, 회수품 검수(상온 미개봉 적격만 재판매, 신선·냉장·냉동 재판매 금지), 폐기 승인(REQUESTED→APPROVED/REJECTED→부분 실행→COMPLETED; 요청자/승인자 분리), 품질 사건, 리콜 영향 LOT 추적(MFC 측). 품질 사고 LOT 판매 보류를 걸고 푸는 판단(IV-09 범위 불명 보류 — 수량 반영은 inventory `lot-sale-holds`, 2026-11-20).
- **소유하지 않음**: 수량 반영(inventory), 손실 회계 승인(approval/본사).
- **API**: 판정/승인 명령, 검수 결과 등록.
- **발행**: `QualityDecisionMade`(격리/해제), `ReturnInspected`(재판매/폐기 판정), `DisposalApproved`, `MfcRecallApplied`.
- **구독**: `ReturnItemReceived`, `TemperatureExcursion`, `RecallIssued`(cdc-quality), `ApprovalDecided`(BO-15), `LineShortageReported`(파손 사유), `CaseOpened`(case — type MFC_QUALITY_IMPACT 만, 품질 사고에 첫 고객 사건 번호, MQ-2 2026-11-20).

---

## 6. Middle-mile TMS (5)

> **1단계(2026-11-26, 사용자 "planning·execution·fleet"·"TMS 2.3 에 맞춰" — 설계 `docs/cdc/design/2026-11-26-tms-first-stage.md`, 계약 0.2.0)**: transport-planning(8701 — 고정 노선 × 온도대 정규차 자동 배차·이관요청서 검증·운송지시서), transport-execution(8702 — 입차·상차·출차·정차·인계·예외·지연), fleet(8703 — 운송사·차량·기사·점유)를 cdc 세션이 만든다. 이관요청서는 cdc-outbound `transport-feed` 에서 가져오고(pull), 출차는 cdc-outbound 출발 검증을 부른다. 아래 소유 목록 중 후보 계산·S8 자원 사가·도크·하차면·운임·routing·cold-chain 은 뒤로.

### transport-planning-service `[2단계]`
- **소유**: 운송 요청(TRF당 1건; RECEIVED/DATA_PENDING/PLAN_READY/ASSIGNED/IN_PROGRESS/OBLIGATION_PENDING/CLOSED/CANCELLED_BEFORE_DEPARTURE), 보류 사유 분류(주소 불명≠차량 부족, TIME_INFEASIBLE, MODEL_INPUT_PENDING), 직송/순회/회차 후보, 적재 계획(실차 총한도+구획별 한도 동시, 혼적·하차 접근·반입 적합성, 상위PLT/하위BOX 중복 금지), 정차 시간표(§10.1: A_i, S_i, H_i, D_i, R_i), 배정(PROPOSED→RESERVED→CONFIRMED→SUPERSEDED/CANCELLED, 버전), 지시서·수신 상태, 출발 전 변경(TMS-D06), 운송 가능성 회신(TransportFeasibility), 미편성 목록.
- **소유하지 않음**: 보충량·승인(replenishment), 차량·기사 정본(fleet), 도로시간(routing), 실제 출발·인계(execution), 도크/하차면 정본(cdc-location/mfc-capacity), 화물 구성 정본(cdc-outbound).
- **사가 S8**: fleet 점유 → cdc-location 도크 → mfc-capacity 하차면 → CONFIRMED. 일부 성공은 RESERVED/PENDING, 이번 시도 신규 예약만 보상.
- **API**: 운송 요청 접수(SCM), 후보 계산·채택, 배정 확정/변경, 운송 가능성 조회, 지시서 조회(기사).
- **발행**: `TransportRequestReceived`, `TransportFeasibilityResult`, `TripAssigned`, `TripUpdated`, `TripInstructionIssued`, `TransportUnplannedReported`.
- **구독**: `TransferApproved/Changed/Cancelled`, `OutboundReadinessChanged`(cdc-outbound), `FleetReservationResult`, `DockReservationResult`, `ReceivingCapabilityChanged`, `CenterMasterChanged`, `TripStateChanged`(execution).
- **의존**: transport-routing, fleet, cdc-location, mfc-capacity, center-master.

### transport-routing-service `[2단계]`
- **소유**: 시간대·차종별 도로시간/거리 행렬(외부 경로 API 어댑터, 갱신 주기·신선도), OR-Tools VRPTW 후보 계산(시간창·구획·단일 출발 제약), 계산 상태(해 없음/제한시간 종료/불가 확정/입력 부족 구분).
- **소유하지 않음**: 후보 채택·배정(planning).
- **API**: 행렬 조회, 후보 계산.
- **발행**: `RoadMatrixRefreshed`.
- **의존**: 외부 경로 API, OR-Tools Java 바인딩(서비스 내부).

### fleet-service `[2단계]`
- **소유**: 실차(ID·위치·가용·정비·중량·부피·구획·예냉·진입 제한), 간선기사(ID·자격·가용·근무/휴게 조건 승인값), 점유 예약(`[시작,종료)` 겹침; 차량·기사 분리 검증), 다음 가용 시각.
- **소유하지 않음**: 라이더(rider-service), 운행 실행(execution).
- **API**: 적격 차량/기사 조회, 점유 예약/해제/조회.
- **발행**: `FleetReservationResult`, `VehicleAvailabilityChanged`.
- **구독**: `TripPhysicallyEnded`(재가용 계산 입력).

### transport-execution-service `[2단계]`
- **소유**: 운행(DRAFT→VALIDATING→CONFIRMED→LOADING→READY→IN_TRANSIT→PHYSICAL_ENDED→CLOSED), 정차(PLANNED→ARRIVED→WAITING→UNLOADING→HANDOVER_PENDING→HANDOVER_RECORDED→DEPARTED), 출발 전 실상차 대조 결과, **실제 출발 사실**(`departureId`, 실차·기사·시각·증빙·`SIMULATED` 출처, 이관별 출발 구성), 인계 증빙(정차·TRF·LPN·수량·인계자/인수자·사진·봉인), 운행 사건(지연·고장·위치 단절·온도·수량 차이; OPEN→ASSIGNED→ACTION_PENDING→RESOLVED→CLOSED), 최신 ETA(최초 약속 보존), 차량·기사 재가용 계산 입력, 비용 근거(요율 버전·대기·추가 정차; 미등록=산정 불가), 실적.
- **소유하지 않음**: TOUT 전기(cdc-inventory), TIN(mfc-inventory), 품질 판정, 재배정 계획(planning), 지급(회계).
- **API**: 출발 등록, 정차 도착/하차/인계/출차 등록, 사건 등록, 운행 조회, 비용 근거 조회.
- **발행**: `TruckActuallyDeparted`, `TripStateChanged`, `StopArrived`, `TransportHandoverEvidenceRecorded`, `TransportIncidentRaised/Resolved`, `TransportEtaUpdated`, `TripPhysicallyEnded`, `TripPerformanceRecorded`.
- **구독**: `TripAssigned/Updated`, `TripInstructionIssued`, `TruckDepartureValidated`(TOUT 결과 참조), `MfcReceiptPosted`(수량 의무 대사), `TemperatureExcursion`, `RoadMatrixRefreshed`.

### cold-chain-service `[2단계]`
- **소유**: 온도 데이터(차량 구획·센터 구역), 허용 범위(상품별 승인값 참조), 이탈 사건(구획·기간·영향 화물), 누적 이력(차량 교체로 초기화하지 않음).
- **소유하지 않음**: 품질 정상/폐기 판정(quality), 재고.
- **발행**: `TemperatureExcursion`, `TemperatureRestored`.
- **구독**: `TripAssigned`(감시 대상), `TripPhysicallyEnded`.

---

## 7. Last-mile Delivery (6)

### delivery-serviceability-service `[1단계]`
- **소유**: 배송 권역(폴리곤)·담당 MFC 매핑 **버전**(본사 BO-07 승인→발효; 시험 주소 검증; 기존 주문 불변), 주소 정규화·판정(담당 MFC/서비스 밖/입력 보완/정본 조회 불명/중첩 충돌), 배송 가능 조건(방식·수단·중량·부피·보냉·시간 제한 00:00~08:00 없음).
- **소유하지 않음**: 센터 마스터(center-master), 슬롯(delivery-capacity), 고객 주소록(customer).
- **API**: 주소 판정(`API-SERVICEAREA-001` — 판정 좌표는 roadAddress 를 서버가 변환한 값, 지도 핀은 쓰지 않음, ADR-0023), 주소 ↔ 좌표 변환(카카오 로컬, 고객·비회원), 매핑 버전 조회, 배송 가능 조건 조회.
- **의존**: 카카오 로컬 API(외부, REST API 키 서버 보관).
- **발행**: `ServiceabilityVersionApplied`.
- **구독**: `ApprovalDecided`(BO-07), `CenterMasterChanged`.

### delivery-capacity-service `[1단계]`
- **소유**: 시간대 슬롯 정의(1시간 단위, 07시 도착 없음, 08/09시 개시 `[결정 필요]`), 슬롯 정원(승인값; 입력=MFC 작업 여력·라이더 수급·날씨 등, 1차 수동 설정), 슬롯 점유(주문당 1건, 분할배송 1건, 마지막 정원 1주문; HELD→CONFIRMED/RELEASED/EXPIRED), 정원 축소 시 영향 주문 조사(기존 약속 삭제 금지), 조회 실패/미설정=확인 대기(정원 0/무제한 아님).
- **소유하지 않음**: MFC 작업 여력(mfc-capacity), 라이더 배정(dispatch).
- **API**: 슬롯 조회(`API-DELIVERY-OPTIONS-001` 입력), `HoldSlot`/확정/해제/변경(orchestration S1·S4).
- **발행**: `SlotHoldResult`, `SlotCapacityReduced`.
- **구독**: `CapacityReduced`(mfc), `RiderSupplyChanged`(rider, 참고), `ApprovalDecided`(정원 정책 BO-09).

### rider-service `[1단계]`
- **소유**: 라이더 프로필(전화·본인확인·면허번호 고유성·주민번호 고유성(검증 없음, 중복 가입 차단)·계좌·거주지·배달지역·수단), 운행 상태(운행 종료/운행 중·신규배차 허용/운행 중·신규배차 중지), 하나씩 배달하기 설정, 배차 모드(일반/AI추천), 계정 제한(정상/신규배차 제한/일시 중지/계약 종료; 증빙 기반, 후기만으로 자동 제재 없음), 수단 변경(배송 중 불가), 마지막 통신 시각, 탈퇴.
- **소유하지 않음**: 특정 배송의 배정 권리(dispatch), 수행·정산(execution), 로그인·인증 토큰(rider-auth-service — 라이더 주체 `RD-…` = 토큰 `sub` = riderId), 간선기사(fleet).
- **API**: 가입/프로필/상태 변경, 적격 라이더 조회(dispatch: 상태·권역·수단·제한·용량), 계정 제한 걸기·풀기·이력(운영자 — RS-1, 2026-11-14).
- **발행**: `RiderStateChanged`, `RiderRestricted`, `RiderSupplyChanged`.
- **구독**: `ApprovalDecided`(라이더 제한 BO-20 — 승인 화면 뒤, 지금은 운영자 API), `RiderComplaintRecorded`(case — 같은 종류 7일 안 3건 이상이면 제한 검토 대상, RS-3 2026-11-14). 반복 배차취소는 제재하지 않는다(dispatch 가 배차 알림 감소 — 사용자 2026-11-13).

### delivery-dispatch-service `[1단계]`
- **소유**: 배송(delivery; 주문 이행 계획→배송 단위, 독립 배송번호; 분할·회수·재배송 각각 별도 번호, 원주문 연결), 배차 요청(점원 배차요청 입력: 배송번호·점포·배송지·포장 소요시간·부피/무게/봉투 수·주류·예상 배달료), 배차 시도(`attemptId`)·제안(`offerId`)·응답(제안/수락·배정/거절/미응답 만료/전송 실패 구분), 반경 단계 탐색(1km→2km→3km `[외부 참고값]`), 응답 대기 1분, 동시 수락 1명 확정·나머지 "이미 배정", 재시도(최대 2회·10분 `[외부 참고값]`), 거절 5회 연속 안내, 추가·분할 배차(규격별 추가 라이더 기준), 배차 취소(픽업 전: 재배차 / 픽업 후: 지원센터 처리), 늦은 수락 분류(시간 만료/이미 배정/주문 취소/자격·용량 불충족), 취소 주문 신규 배차 차단, 회수·재배송 배차, AI 추천 배차 어댑터(3차), 배차 지연 임계 알림.
- **소유하지 않음**: 라이더 정본(rider), 수행 상태(execution), 인계 확정(mfc-fulfillment), 주문 취소(order/claim), 배달료 정산(execution).
- **API**: 배차 요청(점원만 — 2026-11-13 BR-51 자동 요청 뺌), 제안 응답(라이더), 배차 취소, 배송 조회, 회수 배차 요청(claim), 배차 상태 조회(orchestration S3).
- **발행**: `DeliveryCreated`, `DispatchOfferSent`, `DeliveryAssigned`, `DeliveryAssignmentChanged`, `DispatchFailed`(기사 없음/반복 거절/만료/장애 구분), `DispatchDelayed`, `DeliveryCancelledBeforePickup`.
- **구독**: `ShipmentReady`, `PackingFinished`, `OrderCancelled`, `ReturnPickupRequested`, `ExchangeReshipmentApproved`, `RiderStateChanged`, `RiderRestricted`, `PackageHandedOver`, `DeliveryCompleted`·`DeliveryFailed`(배송 CLOSED — 2026-11-13 H2·전달 실패 확정), `MisdeliveryConfirmed`(case — 오배송 회수 배송·배차, 2026-11-16 DD-2), `ReturnPickedUp`·`ReturnDelivered`(회수 배송 PICKED_UP·CLOSED).
- **의존**: rider, delivery-tracking(거리, 오배송 회수지 = 원래 라이더 완료 시각 위치 — 2026-11-16), mfc-fulfillment(포장 정보), order(이행 계획). AI 추천 배차 계산은 **Python AI 최적화 서버**(GreedRL·RouteFinder, 내부 계산 서버 — ADR-0058), 거리·시간은 카카오모빌리티 길찾기, 알뜰배달 묶음은 OR-Tools 또는 AI(ADR-0050 일부 대체).

### delivery-execution-service `[1단계]`
- **소유**: 배송 수행 상태(배정→가게 도착→픽업 완료(인계 확정 후)→고객 도착→전달 완료 / 실패 / 중단 / 회수 중→점포 반납), 전달 증빙(대면/비대면 사진 필수, 보관 장소, 촬영 시각; 업로드 실패 시 재시도 또는 지원센터 대체 증빙), 시간 경과 자동완료 없음 `[확정]`, 주류 전달은 PG 인증 통합이므로 현장 신분증 절차 없음 `[확정]`, 완료 후 사고 신고(기존 완료 유지·사건 별도), 상태 정정(처리자·근거), 도움요청(→case), 회수 수행(고객 수거→점포 반납→점원 수령 확인), 수행료 근거(수단·거리 구간 요율표 버전·할증·보상·공제·취소 시점별 기준; 요율 미등록=산정 불가), 정산 상태(예상/확정 대기/지급 확정/지급중/지급 완료/지급 실패/조정; 지급 실행은 외부 회계), 라이더 배송 이력·오늘 내역.
- **소유하지 않음**: 배정(dispatch), 인계 fence·재고(mfc), 주문 완료 판단(order), 환불(claim/payment), 위치(tracking).
- **API**: 상태 전이(라이더), 사진 업로드, 도움요청, 회수 등록, 수행료/정산 조회, 운영자 정정(승인 필요). 직원(백오피스 고객센터·라이더 운영) 배송 찾기·도움요청 대기 목록·처리함 닫기(2026-11-15 — 지원센터 사건함(case R-4)이 생기면 도움요청 처리 상태의 정본은 case).
- **발행**: `RiderArrivedAtStore`, `PickupCompleted`, `RiderArrivedAtCustomer`, `DeliveryCompleted`(배송 단위, 증빙 참조), `DeliveryFailed`, `DeliveryIncidentRaised`, `ReturnPickedUp`, `ReturnDelivered`(점포 반납), `DeliveryStatusCorrected`, `RiderEarningRecorded`, `SettlementStateChanged`.
- **구독**: `DeliveryCreated`, `DeliveryAssigned/AssignmentChanged`, `PackageHandedOver`(픽업 완료 근거), `ShipmentReady`·`PackingFinished`(출고 포장 목록 — 모든 포장 인계 판정, 2026-11-13), `DeliveryCancelledBeforePickup`, `ClaimDecided`(회수 지시).

### delivery-tracking-service `[1단계]`
- **소유**: 라이더 위치(좌표·갱신 시각·정확도), 이동 이력, 권역 내/외·GPS 오류·갱신 지연 기록, 거리·이동시간 행렬(카카오모빌리티 어댑터 `[결정 필요]`), ETA(점원: 픽업지·배달지 둘 다 / 고객: 본인 배송지만; 타 고객 주소·전체 동선 비노출), 지연 자동 알림 트리거, WebSocket/SSE 푸시, "위치 확인 중/ETA 확인 중" 상태.
- **소유하지 않음**: 배송 상태 확정(execution; GPS만으로 픽업·전달 처리 금지).
- **API**: 위치 수신, ETA 조회, 실시간 구독, 거리 행렬(dispatch), 정한 시각의 위치 점(dispatch — 오배송 회수지, 2026-11-16).
- **발행**: `DeliveryEtaUpdated`, `RiderLocationStale`.
- **구독**: `DeliveryAssigned`·`DeliveryAssignmentChanged`·`DeliveryCancelledBeforePickup`·`DeliveryCompleted`·`DeliveryFailed`·`ReturnDelivered`(진행 중 배송 → 위치 끊김 3분, 2026-11-14 구현 — 회수 반납 2026-11-16). 위치 기록 보존 30일(사용자 2026-11-14).

---

## 8. Platform & Support (6 + 추가 3 + 고객 RAG 1 + 라이더 auth 1 + 직원 인증 2)

### api-gateway `[1단계]`
- 라우팅, JWT 검증(auth 공개키), rate limit, `traceId` 발급·전파, 앱별(고객/라이더/점원/기사/관리자) 경로 분리. 비즈니스 로직 없음.
- **구독**: `SubjectBlocked`(Redis 주체 차단 목록 — 차단 전에 발급된 토큰 401, ADR-0054 ⑤).

### auth-service `[1단계 — 2026-11-13 고객·비회원 전용]`
- **소유**: 고객(Google ID 토큰 검증 — ADR-0019·0055, `AUTH_GUEST→PENDING→(CONSENT)→MEMBER`, `AUTH_UNKNOWN`, `AUTH_RESTRICTED` 15분 5회→10분), 운영자 신원(Keycloak 으로 옮기기 전까지 — ADR-0054)·비회원(서비스 간은 인증 없음 — ADR-0026), 토큰/세션(`[팀 확인 필요]` 쿠키/CSRF), **유효 RoleGrant·Delegation(교집합, 원권한보다 넓게 불가)·긴급권한 정본**(BackOffice 3장), 행위 시점 검증 API(역할·센터 범위·대행 기간·대상 소유권·자기승인 분리), 보안 기록 90일.
- **로그인 단계 휴대폰 확인(2026-11-20 ADR-0074, 0.6.0)**: 필수 동의 다음 `AUTH_IDENTITY` — 번호 정본은 customer(내부 API), auth 는 원인증 상태·막힘 안내(다른 계정의 제공자·가입일)·버린 가입 계정만.
- **소유하지 않음**: 권한 **부여 승인 흐름**(approval BO-01/02), 고객 프로필(customer), 라이더 로그인·토큰(rider-auth-service, ADR-0054).
- **ADR-0056(2026-11-12, 구현 전)**: 직원 신원·직원 토큰과 **직원 RoleGrant·Delegation·긴급권한 정본·행위 시점 검증 API·`GrantApplied`·직원 `SubjectBlocked`** 은 employee-auth-service 로 옮긴다. 전환이 끝나면 auth-service 는 고객·비회원·서비스 신원 토큰만 — 운영자 로그인·`operator_account`·`StaffBlockRelay` 를 내린다. 지금 코드에 RoleGrant·Delegation 기능은 없고 `operator_account.roles·centers` 만 있다.
- **API**: 고객 소셜 로그인·비회원 세션·토큰 갱신·로그아웃·현재 세션, JWKS. **운영자 로그인·계정 관리는 2026-11-13 없앴다**(ADR-0056 7단계 — 직원 토큰은 employee-auth-service). 행위 검증·grant 반영 경로는 구현 전(501)이고 정본은 employee-auth-service 로 옮긴다.
- **발행**: `CustomerAuthenticated`. (운영자 `SubjectBlocked`·Keycloak 중계는 2026-11-13 없앰. `GrantApplied`·`DelegationStarted/Ended` 는 employee-auth-service 로.)
- **구독**: `ApprovalDecided`(BO-01/02), `CustomerWithdrawalCompleted`(세션·소셜 연결 삭제).
- **의존**: customer-service(필수 약관 동의 판단·로그인 중 동의 반영 — 내부 경로, 스위치 `require-consent` 가 켜졌을 때 — 2026-11-17 CU-1, LAN 은 고객 앱 반영 때까지 꺼짐. 켜져 있고 응답이 없으면 고객 로그인 503).

### rider-auth-service `[1단계]` — 라이더 로그인·라이더 토큰 발급자 (ADR-0054 E·F, ADR-0055, 2026-11-10)
- **소유**: 라이더 Google 로그인(라이더 앱이 받은 Google ID 토큰을 Google 공개키로 검증 — `aud` = 라이더용 웹 클라이언트 ID, client secret·인가 코드 교환 없음), Google 계정(`sub`) ↔ 라이더 주체 `RD-…`, 로그인 세션·refresh token(회전·재사용 감지), 기기별 로그인 실패 제한(15분 5회→10분), 라이더 토큰(`typ=RIDER`) 서명키·JWKS.
- **소유하지 않음**: 라이더 프로필·가입 정보(전화번호·배달 지역·수단·약관)·승인·정지·운행 상태(rider-service), 고객·비회원 토큰(auth-service), 직원 토큰(Keycloak).
- **API**(계약 0.1.0): `POST /rider-auth/social-logins`, `GET /rider-auth/social-logins/{authRequestId}`, `POST /rider-auth/tokens`, `POST /rider-auth/logouts`, `GET /rider-auth/sessions/current`, `GET /internal/jwks`(게이트웨이·전 서비스가 발급자 표 rider 줄로 사용).
- **발행**: `SubjectBlocked`(로그아웃·refresh 재사용 — 그 세션 또는 모든 기기, 정지 라이더 — 주체 전체 RIDER_RESTRICTED, outbox). **구독**: `RiderRestricted`(rider, 2026-11-15 — SUSPENDED·TERMINATED 는 dispatch 에 맡은 배송이 없어진 뒤 모든 세션 끊기·새 로그인 거절, 사용자 "추천대로"). 가입은 받은 토큰으로 rider-service 에 한다(가입 여부 = `GET /riders/me`).
- **동기 의존**: delivery-dispatch `GET /internal/riders/{id}/active-deliveries`(로그인 끊기 시점, 1분마다).
- LAN: PC 5, 포트 8510, DB `rider_auth_service`(PC 5 postgres).

### employee-auth-service `[ADR-0056 — 2026-11-12 3단계 구현: 로그인·토큰·관리 경로. 권한 grant·대행·행위 검증·사건은 5단계]` — 직원 로그인·직원 토큰 발급자·직원 권한 정본
- **소유**: Keycloak 로그인 연동(Keycloak `pmart-staff` 의 **유일한 로그인 클라이언트** — 기밀, 인가 코드 + PKCE), Keycloak ID 토큰 검증, Keycloak `sub` ↔ 운영자 ID `OP-…`, 직원 로그인 상태(ACTIVE/DISABLED/LEFT), 1회용 로그인 티켓(60초), 직원 세션·refresh(회전), **PMart 직원 JWT(`typ=OPERATOR`, `aud=pmart-api`) 서명키·JWKS**, **직원 유효 RoleGrant·Delegation(교집합, 원권한보다 넓게 불가)·긴급권한 정본**(BackOffice 3장 — auth-service 에서 옮김), 행위 시점 검증 API(역할·센터 범위·대행 기간·대상 소유권·자기승인 분리), 직원 보안 기록.
- **소유하지 않음**: 직원 계정·비밀번호(Keycloak), 직원 인사·고용 정보·계정 발급(hr-service), 권한 **부여 승인 흐름**(approval BO-01/02 — 자동 승인 규칙 포함), MFC 작업 자격(mfc-capacity), 고객·비회원·서비스 토큰(auth-service).
- **API**(계약 `contracts/openapi/employee-auth-service.yaml`): `GET /employee-auth/logins`(시작 — 앱 code_challenge 를 시도에 묶음)·`GET /employee-auth/logins/callback`(Keycloak 콜백 → 앱 복귀 주소 `?ticket=`/`?error=`), `POST /employee-auth/tokens`(티켓 + code_verifier + 기기), `POST /employee-auth/tokens/refresh`, `POST /employee-auth/logouts`, `GET /employee-auth/sessions/current`, `GET /internal/jwks`, `GET·PUT /employee-auth/employees/{operatorId}`(OPERATOR_ADMIN — 상태·역할·센터, 5단계 연동 전 이전·보정용, 변경 기록), `POST /internal/dev-token-exchange`(LAN 전용), (5단계) grant 반영·조회, 행위 검증(`authorize(actor, action, target, centerScope)`), LAN 전용 시험 토큰 교환(설정으로만, prod 없음).
- **발행**: `SubjectBlocked`(직원 — 퇴사·정지, 2026-11-13 구현), `GrantApplied`·`ApplicationReceiptRecorded`(권한 승인 반영, 2026-11-13 5-4 구현), (계획) `DelegationStarted/Ended`.
- **구독**: `EmployeeRegistered`·`EmployeeSuspended`·`EmployeeReinstated`·`EmployeeLeft`(hr-service, 2026-11-13 구현), `ApprovalDecided`(type GRANT — BO-01 권한 승인 → `employee_grant`, 2026-11-13 5-4 구현; BO-02 대행은 계획).
- **동기 의존**: Keycloak(`/token`·certs, PC 5 내부).
- LAN: PC 5, 포트 8511, DB `employee_auth_service`(PC 5 postgres). 상세 `docs/architecture/auth/employee-auth-structure.md`.

### hr-service `[ADR-0056 — 2026-11-13 구현·LAN 배치(PC 5, 8512)]` — 직원 등록·인사 정보·계정 발급
- **소유**: 직원 신원·고용 정보(운영자 ID `OP-<UUID>` 매기기, 사번 `P<연도>-<5자리>`, 이름, 메일 ID `@pmart.com`(재사용 금지), 고용 형태 REGULAR·PART_TIME·DAILY, 소속(본사 부서·센터), 근무 시작일·계약 종료일, 재직 상태), 등록·소속 변경·재입사·정지·퇴사 업무 흐름(계약 종료일 자동 정지), **계정 발급 진행 상태**(사서함 → Keycloak 계정 → 안내 메일 — 재시도·보상), Keycloak Admin API 호출(서비스 계정 클라이언트 `hr-provisioner`, `manage-users`·`view-users`·`query-users` 만 — 역할·그룹 매핑은 하지 않음), 등록 화면에서 받은 요청 권한을 approval 에 제출.
- **소유하지 않음**: 직원 권한의 효력·정본(employee-auth-service — hr-service 는 조회만, BO-01 인사·권한 분리), 권한 심사(approval), 로그인·토큰(employee-auth-service), 비밀번호(Keycloak — 평문을 받지도 돌려주지도 않음), 사서함 저장(메일 서버).
- **API**(제안): `POST /hr/employees`(기본 정보 + 요청 권한), `GET /hr/employees`, `POST /hr/employees/{id}/affiliation-changes`, `POST /hr/employees/{id}/reinstatements`, `POST /hr/employees/{id}/suspend`, `POST /hr/employees/{id}/leave`, `POST /hr/employees/{id}/resend-invite`. 권한 `HR_ADMIN`.
- **발행**: `EmployeeRegistered`, `EmployeeSuspended`, `EmployeeReinstated`, `EmployeeLeft`, `GrantRequested`(등록 화면의 요청 권한 → approval, 2026-11-13 5-4).
- **구독**: `ApprovalDecided`(type GRANT — 요청 권한 상태 APPROVED·REJECTED 표시, 2026-11-13 5-4).
- **동기 의존**: Keycloak Admin API, mail-provisioner(사서함 만들기·비활성 — PC 5 내부), center-master(센터 코드, 계획). approval 제출은 동기 호출이 아니라 사건 `GrantRequested`(2026-11-13).
- LAN: PC 5, 포트 8512, DB `hr_service`(PC 5 postgres). 인프라: mailserver(docker-mailserver, `@pmart.com` 로컬 전용, LAN 포트 없음)·mail-provisioner(사이드카, LAN 포트 없음)·Roundcube(8190) — 서비스가 아니라 배치 구성요소.

### config-server `[LAN]` — 설정 배포만 (ADR-0027, 2026-11-02)
- Spring Cloud Config Server. LAN(compose)의 Spring 앱이 부팅할 때 GitHub `code-and-pick/pmart-config`(`lan/`)의 설정을 받는다. 비밀값은 `{cipher}` 를 서버가 복호화. 저장소 내용은 `deploy/lan/render.sh` 가 만든다.
- 실행 중 갱신 알림(ADR-0033): GitHub push 웹훅(smee.io 중계, `config-relay`)을 `POST /monitor` 로 받아 서명을 검사하고, 바뀐 파일의 앱에 Spring Cloud Bus(Kafka `springCloudBus`)로 "다시 받아 가라"를 보낸다.
- **소유하지 않음**: 업무 정책값(각 도메인 서비스 소유 — 설정 파일로 옮기지 않는다), 운영(Kubernetes) 설정(ConfigMap·Secret Manager).

### ~~discovery-service~~ — 제거 (2026-10-23)
- 운영은 Kubernetes Service DNS 로 대체(`.claude/rules/security-config.md`). local·LAN 은 아래 eureka-server(ADR-0032).

### eureka-server `[LAN]` — 서비스 등록·조회만 (ADR-0032, 2026-11-03)
- Spring Cloud Netflix Eureka Server(8761, Basic 인증). LAN 의 Spring 앱이 자기 PC IP + 포트로 등록하고, 게이트웨이(`lb://`)·서비스 간 호출(common-web)이 주소를 찾는다. 서비스당 인스턴스 1개.
- **소유하지 않음**: 업무 데이터 일체, 운영(Kubernetes) 서비스 검색(Service DNS). Kafka·DB 등 인프라 주소.

### backoffice-bff `[1단계]` — **상태 없음**
- 관리자 화면별 조회 조합(원천별 `asOf`·`postedAt`·신선도·직접확인/사본/불명 표시, 기준시각 다른 수량 합산 금지), 명령 전달(멱등키·expected version 부착, 응답 ACK≠성공 그대로 표시), 화면: 통합 조회, 승인함, 사건함, 보충 현황, 운행 관제, 실시간 배송 현황, 포장 인계 현황, 지표(분모 보존). 금지: 주문 상태 직접 변경, 잔고 덮어쓰기, 강제 성공, 검증 생략.
- **구현 현황(2026-11-17, 0.9.1 — PC 2)**: 승인함, 처리 대기(멈춘 사가·이행 확인 필요·MFC 중지 실패·인계 거절 — 명령 사가 다시 확인·처리 완료·MFC 재시도, 운영자·사유는 원천에 기록), 통합 조회(주문·배송·배차·클레임·사건·문의·MFC 현장), 실시간 배송 현황(배차·열린 사건·tracking 도착 예정), 포장 인계 현황(0.11.0 — MFC 센터 출고 F-9·배송, 단계는 bff 규칙), 사건함(연결 배송·주문 상태·도움요청 대화 시각). 모두 **직원 토큰 전달**, 원천 실패는 그 칸만 불명 + unavailableSources. 명령 결과 조회(0.12.0 — 사가 수동 조치·MFC 중지 재시도). 아직 501: 보충 현황·운행 관제(원천 replenishment·transport·cold-chain 서비스 없음)·지표(여러 서비스 비율 — 정의 승인 뒤). 접수 건 현황 건수는 case `GET /cases/metrics`. **지표(2026-11-18, bff 0.13.0)**: `views/metrics` CASE_SLA 만(case 0.24.0 `/cases/metrics/sla` — 닫힌 사건함 건 기한 준수, 기한 없던 건 unjudged), 그 밖 지표 501.

### notification-service `[1단계]`
- **소유**: 알림 요청·채널(앱 푸시·카카오 알림톡 `[결정 필요]`)·발송 이력·재시도·읽음(본인만), 고객 알림함, 라이더 배차 알림 전송 기록(제안 자체는 dispatch), 지연 안내.
- **구독**: `OrderConfirmed`, `PaymentFailed/Unknown`, `PreparationStarted`, `DeliveryAssigned`, `PickupCompleted`, `DeliveryEtaUpdated`(지연), `DeliveryCompleted`(분할은 전량 완료 1회), `ClaimDecided`, `RefundResult`, `DispatchOfferSent`, `DispatchDelayed`, `CaseAssigned`, `NotificationRequested`(범용).
- **발행**: `NotificationDelivered`, `NotificationFailed`.
- **1차 구현(2026-11-16, 계약 0.2.0, ADR-0064)**: 고객 앱 알림함(IN_APP)만 — 주문 확정·상품 준비·배송 출발(`PickupCompleted`)·완료·취소·결품 부분 취소(`OrderLineQuantityChanged` SHORTAGE)·환불 결과(`RefundIntentCreated` 로 주문과 이음)·고객센터 답변(`InquiryAnswered`). 받는 고객은 사건의 accountId, 없으면 order 내부 주문 조회. 푸시·라이더·운영자·발행은 아직.
- **휴대폰 푸시(2026-11-17, ADR-0067)**: 알림함에 저장된 알림을 FCM 으로도 — 라이더 알림 전부(`RiderPush`), 고객은 정한 종류(배송 출발·완료·결품·주문 취소·환불 결과 — `CustomerPush`, 설정 `pmart.notification.fcm.customer-templates`). 서버 키는 앱의 Firebase 프로젝트마다(라이더 `pmart-123`, 고객 오늘 `pmart-42b37` — 내일 하나로). 알림함이 정본, 실패는 다시 보내지 않음.

### audit-service `[1단계]`
- **소유**: 감사 사건(운영자 명령·사유·전후 값·대상·실행·보상 결과; 추가만, 삭제 없음), 개인정보 열람·다운로드 기록(BO-25), 보존 정책 `[결정 필요]`.
- **구독**: `AuditEntryRecorded`(전 서비스), `ApprovalDecided`, `GrantApplied`, `CompensationExecuted`, `DeliveryStatusCorrected`, `InventoryAdjustmentPosted`.
- **개인정보 열람 기록 구현(2026-11-15, 계약 0.2.0, ADR-0062)**: `POST/GET /internal/pii-access-logs` — 동기 기록(멱등, 추가 전용 트리거). 첫 호출자는 order-service 직원 배송지 원문 열람(`pii-reveals`): 기록이 안 되면 원문을 주지 않는다. 직원용 기록 조회 API·보존기간은 `[결정 필요]`.

### ai-assistant-service `[3단계 — 1차 앞당김(ADR-0036)]`
- RAG(정책 문서), 정책 설명, 권한 있는 도메인 API 호출(읽기 우선). 비즈니스 문서에 요구 없음.
- **1차(2026-11-04, ADR-0036)**: 내부 운영자의 정책·업무 절차 질의응답 + 근거 표시만. 실시간 조회·명령 위임은 하지 않는다(`REALTIME_DATA_REQUIRED`·`OUT_OF_SCOPE`).
- **소유**: 명시 목록 문서의 사본 색인(`index_snapshot`·`doc_chunk`·`embedding_cache`)과 답변 기록(`policy_answer` — 선점·lease·인용). 업무 정본은 없다.
- **API**: `POST/GET /assistant/policy-questions`, `GET /assistant/policy-questions/{answerId}`(운영자만). 채팅 세션(2026-11-21, ADR-0084): `GET /assistant/chat-sessions`, `GET·DELETE /assistant/chat-sessions/{sessionId}` — 본인만, 마지막 질문 뒤 14일 보존.
- **발행·구독**: 없음. **의존(동기)**: search-service `GET /search/products`(공개 작업, 토큰 없이 — 2026-11-20 ADR-0083 상품 질문을 정해진 문장으로, 실패하면 문서로). 그 밖의 서비스 DB·API 는 부르지 않는다.
- **운영자(백오피스) 전용**(2026-11-09, ADR-0051): 고객앱 질문은 아래 `customer-assistant-service` 가 받는다. DB 는 RAG 전용 pgvector 컨테이너의 `ai_assistant_service`. 모델은 LAN Ollama(ADR-0051 결정 6).

### customer-assistant-service `[ADR-0051 — 2026-11-09 추가·1차 구현]`
- 고객앱 고객센터 AI 상담(RAG): 승인된 고객 안내 문서(`docs/business/customer-faq/`)로 답하고 근거 표시. 취소·환불 같은 실행은 하지 않는다(OUT_OF_SCOPE → 고객센터 문의 연결).
- 내 주문 상태(C6): 주문을 고르지 않았으면 최근 주문을 보여 고르게 하고(REALTIME_DATA_REQUIRED), 고른 주문은 order-service 에서 읽은 상태를 **정해진 문장**으로 답한다(ORDER_STATUS — 모델에 주문 정보를 넘기지 않음).
- **소유**: 고객 안내 문서의 사본 색인과 고객 답변 기록(DB `customer_assistant_service`, RAG 전용 pgvector — 운영자 DB 와 계정 분리). 질문 원문은 저장하지 않는다(C5, 해시만). 업무 정본은 없다.
- **소유하지 않음**: 운영자 문서·답변(ai-assistant-service), 고객 안내 문서의 작성·승인(Claude 초안·사용자 승인, C1), 주문(order), 문의 접수(case `/inquiries` — 앱이 handoff 를 받아 직접 연다).
- **API**(계약 0.1.0): `POST/GET /customer-assistant/questions`, `GET /customer-assistant/questions/{answerId}`, `GET /customer-assistant/recent-orders`. 회원 고객만(C2), 고객별 1분 5개·하루 50개(C3, 429).
- **발행·구독**: 없음. **의존(동기)**: order-service `GET /orders`·`GET /orders/{id}`(고객 토큰 전달, 1 hop), search-service `GET /search/products`(공개 작업, 토큰 없이 — 2026-11-20 ADR-0083 상품 질문, 센터 없이 전체 기준). 모델 서버 Ollama 는 인프라.

### approval-service `[1단계 — 추가 제안, ADR-0004]`
- **소유**: ChangeRequest(초안; 승인 아님), ApprovalDossier(원도메인 승인 ID 참조, 이중 발급 금지), 승인 단위(업무 종류+원객체/행+범위+예상 버전+허용 행위+수량/금액+조건+효력/만료), 5축 상태(요청 DRAFT/SUBMITTED/WITHDRAW_REQUESTED/WITHDRAWN · 심사 REVIEW_PENDING/EVIDENCE_REQUIRED/APPROVED/PARTIALLY_APPROVED/REJECTED/EXPIRED/SUPERSEDED · 실행 NOT_REQUESTED/VALIDATING/ACCEPTED/IN_PROGRESS/PARTIAL/APPLIED/REJECTED/UNKNOWN_RESULT · 전달 PENDING/DELIVERED/ACKNOWLEDGED/RETRY_PENDING/FAILED · 사건), 부분 집행 불변식(`승인량 = 누적 유효 집행 + 승인된 미집행 취소 + 열린 잔량`), 재심사(새 버전+계보), 통합 승인함(BO-10, 종류별 결정 범위), 대량 변경(BO-26: 파일 해시 고정·행별 멱등·결과 분리), PolicyReference 레지스트리(BO-09: 원정책 ID·버전·반영 상태 연결; 계산 엔진 복제 없음), ApplicationReceipt(수신자별 반영 확인).
- **소유하지 않음**: 승인 결과의 업무 정본(product/pricing/replenishment/claim/quality/serviceability/auth), 사건(case).
- **승인 종류 → 정본**: 상품/SKU/환산→product · 가격→pricing · 전시→storefront/product-content · 센터 기준정보→center-master · 권역→delivery-serviceability · 운영 중지/재개→cdc-quality/mfc-capacity/rider · 정책→각 소유 서비스 · 공급 사업승인→replenishment · 환불/클레임→claim · 품질/리콜→cdc-quality/mfc-quality · 재고 조정/손실→cdc-inventory/mfc-inventory(승인 참조 집행) · 직원 권한(GRANT)→employee-auth-service(ADR-0056) · 검색 동의어(SEARCH_SYNONYM)→search-service(ADR-0073, 요청 MD·OPERATOR_ADMIN, 승인 OPERATOR_ADMIN).
- **API**: 변경 요청 제출/철회, 심사·승인·부분승인·반려, 승인함 조회, 반영 상태 조회, 정책 참조 등록, 승인 번호 확인·한 번 사용(`/internal/approval-ref-checks`·`/internal/approval-ref-uses` — 승인 번호를 명령에 실어 집행하는 정본용, MFC 종류 `MFC_STOCK_ADJUSTMENT`·`MFC_OPERATION_RESTRICTION`, AP-1 2026-11-17).
- **발행**: `ChangeRequestSubmitted`, `ApprovalDecided`, `ApprovalWithdrawn/Expired/Superseded`, `PolicyVersionApproved`, `OperationRestrictionRequested/ResumeRequested`, `BatchRowResultRecorded`.
- **구독**: `ApplicationReceiptRecorded`(정본의 반영 회신 → 실행·전달 축, ADR-0039 — pricing, employee-auth-service, delivery-serviceability, product·product-content·center-master(2026-11-19), search(SEARCH_SYNONYM, 2026-11-20)), `GrantRequested`(hr-service 직원 권한 요청 → 변경 요청·승인, 자동 승인 규칙 — ADR-0056 5-4, 2026-11-13). 계획: 각 정본의 반영 결과(`ProductVersionPublished`, `PriceVersionEffective`, `ServiceabilityVersionApplied`, `TransferApproved`, `GrantApplied`, `OperationRestrictionApplied/Lifted`, `InventoryAdjustmentPosted` 등 → 실행/전달 축 갱신).
- **의존(동기)**: auth(승인자 권한·대행), 각 정본(명령 전달, 2 hop 초과 시 이벤트).

### case-service `[1단계 — 추가 제안, ADR-0004]`
- **소유**: Case/SubCase(총괄·하위, 병합 시 원ID 보존), Obligation(종류별 수량/금액 분리), ActionRequest(상대 commit/거래 ID 연결), EvidenceReference, ShiftHandover/CloseSnapshot(BO-27), 사건 상태(OPEN/ASSIGNED/ACTION_PENDING/MONITORING/RESOLUTION_REVIEW/CLOSED/REOPENED; 시간 경과 자동 종결 없음), 우선순위·SLA(승인 입력), CS 접수(접수≠명령, 동의 별도 기록), MFC 문제함(WK-12), 라이더 도움요청·고객 연락 기록(안심번호 `[외부 참고]`), 귀책·비용 결정 기록(지급은 회계).
- **소유하지 않음**: 도메인 사고·클레임·수불 정본(각 서비스), 알림(notification).
- **API**: 사건 생성/배정/이관/종결, 의무 등록/해소, CS 접수, 도움요청.
- **구현 현황(2026-11-17, 0.19.0 — PC 2)**: 사건함 `/cases`(도움요청·전달 실패·위치 끊김·오배송·배차 실패·사가/결제 이상·반품 회수 실패(상담원 팀, 0.22.0) — 집기·처리 끝내기·다시 처리하기·넘기기·메모·후속 처리·교대 인계(팀 역할 0.13.1)·행위 요청 **기록만** 0.17.0·현황 건수 0.18.0), 고객센터 문의 `/inquiries`(고객·라이더 0.13.0, 사진 0.14.0 — 버킷 pmart-case, 만족도 0.15.0 — 상담원 평가에 안 씀, 배송 신고 0.16.0, 닫기 0.19.0 — 다시 열기 없음), CS 접수 `/cs-intakes`(전화·채팅·메일 기록, 본인 확인 방법·대리 표시 0.12.0), 라이더 컴플레인. 상담 종료·행위 요청 기록은 거래 의무를 끝내지 않는다.
- **발행**: `CaseOpened/Assigned/Transferred/Closed/Reopened`, `ObligationCreated/Resolved`, `ShiftHandoverCompleted`, `RiderComplaintRecorded`(라이더 컴플레인 — 신고·오배송·파손, rider 제한 검토 집계, R-5 2026-11-14 요청·구현 전).
- **구독**: `ReturnRecoveryFailed`(claim order.claim.v1 — 반품 회수 실패 → 상담원 팀 사건 RETURN_RECOVERY_FAILED, R-8 구현 2026-11-17 0.22.0, CS_AGENT), `ReconciliationRequired`, `CompensationExecuted`(성공 아닌 것만), `PaymentMismatch`, `PaymentResultUnknown`(상신된 것만) — 사가·결제 이상 사건(R-3 구현 2026-11-16, OPERATOR_ADMIN), `DispatchFailed`, `DeliveryIncidentRaised`, `TransportIncidentRaised`, `LineShortageReported`(동의 없음), `ReceiptDiscrepancyReported`, `OutboundDiscrepancyRaised`, `QualityDecisionMade`(리콜), `IncidentResolution`(도메인 회신). `ClaimClosed`(같은 토픽, 2026-11-18 — 열린 반품 회수 실패 사건에 이력 CLAIM_CLOSED 만, 닫지 않음).

### center-master-service `[1단계 — 추가 제안, ADR-0004]`
- **소유**: 센터 코드·유형(CDC/MFC)·명칭·주소·좌표·진입/하차/인계 위치·층수·반입 조건·취급 온도대·영업 캘린더(고객 주문 운영/직원 근무/차량 진입/하차·인계/검수·적치 창 — 다섯 시간표 분리, 휴일·임시 제한·버전·승인자), 휴점/폐점 의무 목록(BO-06).
- **소유하지 않음**: 센터 내부 LOC(cdc/mfc-location), 현장 용량(mfc-capacity), 권역(serviceability).
- **API**: 센터 조회(버전), 캘린더 조회(일자·시간표 종류).
- **발행**: `CenterMasterChanged`, `CenterCalendarChanged`, `ApplicationReceiptRecorded`(CENTER_MASTER 승인 반영 회신, RC-1 2026-11-19).
- **구독**: `ApprovalDecided`(BO-06).

---

## 9. 외부 시스템 (서비스 아님)

| 외부 | 어댑터 위치 | 비고 |
|---|---|---|
| 토스페이먼츠 | payment-service | 거래 사실 정본. 비밀 로그 금지 |
| 소셜 OAuth 제공자 | auth-service | 제공자 인증 성공≠계정 이용 가능 |
| 카카오 로컬(주소 검색·좌표→주소) | delivery-serviceability | 확정(2026-10-31, ADR-0023). REST API 키 서버 전용, 주소 원문 로그 금지 |
| 카카오 로컬(주소 검색·좌표→주소) | delivery-serviceability | 확정(2026-10-31, ADR-0023). REST API 키 서버 전용, 주소 원문 로그 금지 |
| 카카오모빌리티 길찾기 / 카카오맵 | delivery-tracking(거리·경로), transport-routing(간선 행렬) | 결정(ADR-0058, 2026-11-13 — 도로 거리·시간, 직선거리 대체 없음) |
| OR-Tools(Java) | transport-routing, mfc-capacity, delivery-dispatch(라이더 배정), 피킹 순서 담당(구현 때 정함) 내부 라이브러리 | 확정(2026-10-23), 범위 확대 ADR-0050(2026-11-09). JGraphT 도입 안 함. GreedRL·RouteFinder(Python)는 3단계 별도 승인 |
| 알림 채널(푸시·알림톡) | notification-service | |
| 회계·지급 | 없음(1차 수동) | 라이더 수행료·운송비 지급, BO-21 `[결정 필요]` |
| 가상 설비·센서 | cdc-inbound/outbound, cold-chain | `source=SIMULATED` |
| BI | 이벤트 구독자 | 집계값으로 원장 수정 금지 |
