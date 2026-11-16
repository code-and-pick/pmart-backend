# order-service 에 대한 요청 (MFC 쪽에서)

## O-1. `PackageHandedOver` 구독 → 인계 수량 반영 (2026-11-09 기록)

- **요청한 곳**: mfc-fulfillment(`docs/business/flows/package-handover-contract.md` §5).
- **지금**: order 의 `mfc.fulfillment.v1` 구독은 `PreparationStarted` 만 처리. 줄 수량 변경은 CANCEL_BEFORE_SHIP·SHORTAGE 외 사유를 422 로 막는다(`OrderService`).
- **order-service 가 할 것**
  1. `PackageHandedOver` 구독 → 줄마다 `handedOverQty +q`, `unfulfilledQty −q`(사유 `HANDOVER`, sourceRef = handoverId, 멱등).
  2. 주문 상태 PARTIALLY_HANDED_OVER / IN_DELIVERY.
  3. 배송 사실(`DeliveryCompleted`·`DeliveryFailed`)은 delivery-execution(DE-1) 이 생긴 뒤 같은 방식으로.
- **사용자 결정**: 필요 없음(계약 문서에 규칙 있음).

## O-2. 고객 자율 취소 마감 = 상품준비 전 (2026-11-09, ADR-0052)
- 고객 앱(주문 내역)의 취소는 주문이 `PREPARING` 이 되기 전까지만 받는다. 그 뒤는 고객센터 문의 접수 → 출고 중지 확인된 미인계 수량만 취소.
- 할 일: 지금 취소 가능 판정(order·orchestration S3)과 대조해 고객 토큰 요청은 `PREPARING` 이후 거절(코드 예: `CANCEL_WINDOW_CLOSED`), 운영자(고객센터) 경로는 지금처럼. 고객앱 취소 버튼 노출 기준도 같이 — 클라이언트 공지.

## O-3. 확인된 오배송 — 새 발송 (2026-11-09, ADR-0052)
- 오배송이 확인되면 같은 상품·수량을 MFC 에서 다시 확보·피킹·포장해 원주소로 보낸다(구매자 비용 0원). 재고 부족 품목은 그 품목만 부분 취소·환불(ADR-0049 방식).
- 같은 피해 수량을 새 발송과 환불로 겹치지 않게 주문 수량 원장에 기록. 회수(DD-2)와 동시에 시작.
- **2026-11-16**: 회수 쪽(DD-2)은 구현 — dispatch 가 case `MisdeliveryConfirmed` 로 회수 배송을 만든다. **새 발송은 아직 아무도 시작하지 않는다** — order(또는 orchestration)가 같은 사건을 받아 새 발송을 낼지 정해야 한다(platform·mfc).

## O-4. 배차용 배송지 조회 (2026-11-13, delivery DD-1 — **구현해 PR 에 넣음, platform 검토 필요**)
- **요청한 곳**: delivery-dispatch(MFC → 고객 도로 거리·예상 배달료·알뜰배달 묶음 — ADR-0058, 원문 §2.1 ②).
- **필요한 것**: `GET /internal/orders/{orderId}/delivery-destination`(토큰 없는 내부 호출) — 주문 당시 사본(BR-37)의 도로명주소·지도 핀, 배송 방식(SINGLE·BUNDLED·SCHEDULED), 예약 창, 상태. **수령인·연락처·출입 정보는 주지 않는다.** dispatch 는 도로명주소를 serviceability `/internal/address-geocodes`(정확 일치, ADR-0023)로 좌표로 바꾸고 좌표·동 이름만 저장한다(주소 원문 저장·로그 금지).
- **한 것**: delivery 영역 PC 5 가 계약 0.2.2·`InternalController.getOrderDeliveryDestination`·`DraftFlowIT` 확인을 PR 에 넣었다(급해서 — `pc-split.md` §1 "급하면 PR 을 열되 그 영역 담당이 리뷰"). 표·사건 변경 없음.
- **버그 고침(2026-11-14, PC 5)**: 구현이 `OrderService.internal`(claim 용 — 배송지 사본을 싣지 않음)을 써서 **roadAddress 가 늘 null** 이었다 → dispatch 가 모든 배송을 "주소 없음" SYSTEM_FAILURE(DISPATCH_FAILED)로 멈췄을 것(dispatch 시험은 order 를 WireMock 으로 고정해 못 잡음). `internalWithShipping` 으로 고치고 `DraftFlowIT` 에 도로명주소 확인 추가. **LAN 실배차 전에 배포 필요.**

## O-5. 라이더용 전달지 (2026-11-14, delivery — 클라이언트 BR-24, **구현해 PR 에 넣음, platform 검토 필요**)
- **요청한 곳**: delivery-execution(픽업 뒤 라이더에게 주소·상세·수령인·연락처·출입·요청사항 — 원문 수행 흐름 "픽업 후: 주소·상세주소·연락").
- **결정(사용자 2026-11-14)**: 연락처는 **안심번호가 아니라 고객 전화번호**.
- **호출자 제한 없음(사용자 2026-11-14, PC 1 PR #15 검토 낮음 2 답)**: "주문, 배송정보관리, 배송 서비스 다 전화번호랑 공동현관 출입문 정보를 볼 수 있겠지. 애초에 고객앱에서 넘어온 정보니까" — `X-Source-System == delivery-execution-service` 같은 호출 서비스 검사는 넣지 않는다(내부 호출 신뢰 규칙 ADR-0026 그대로). 라이더에게 보이는 범위는 delivery-execution 이 판정.
- **필요한 것**: `GET /internal/orders/{orderId}/drop-info`(내부 호출) — 주문 당시 사본(BR-37) 전부 + `deliveryNote`. 공개 범위(배정 라이더 본인·픽업 뒤 ~ 완료 전)는 delivery-execution 이 판정, 게이트웨이에 열지 않는다. 사본 없으면 404.
- **한 것**: PC 5 가 계약 0.2.4·`InternalController.getOrderDropInfo`·`DraftFlowIT` 확인을 PR 에 넣었다. 표·사건 변경 없음.

## O-6. 직원용 주문 검색 (2026-11-15, platform — 백오피스 고객센터·라이더 운영, **구현, 커밋만 — 미배포**)
- **요청한 곳**: 직원 웹 백오피스(고객센터·라이더 운영 메뉴). 지금 `GET /orders*` 는 고객 본인 것만.
- **결정(사용자 2026-11-15)**: backoffice-bff 가 아니라 order-service 에 둔다.
- **한 것(계약 0.3.0, V9)**
  - `GET /orders/lookups` — 조건 orderId·customerId(완전 일치)·status·mfcId·receiverPhone·createdFrom/createdTo(확정 시각, from 포함·to 미포함), page(0~)·size(최대 50, 기본 20), 최근 확정 순, `page.hasNext`(전체 건수는 세지 않음). **orderId·customerId 가 없으면 기간 필수·31일 이하**(아니면 400). receiverPhone 은 주문 당시 배송지 사본(BR-37)의 연락처와 숫자만 비교한 **완전 일치**(부분·이름·주소 검색 없음, 사본 없는 옛 주문은 못 찾음). 목록 연락처는 가림(`receiverPhoneMasked`, 예 010-****-1234), 이름·주소는 목록에 없음.
  - `GET /orders/lookups/{orderId}` — 주문행 수량 누계·금액 구성(+PG 승인액)·결제 시도/PG 거래 ID·배송지 사본 **전체**(수령인·연락처·주소·출입)·이행 확인 필요 기록. 상태 이력·클레임 목록은 order 에 저장돼 있지 않아 없음(클레임은 claim-service `GET /claims?orderId=`).
  - 권한: OPERATOR 토큰 + 역할 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS(`StaffOrderRules.ROLES`), 그 밖·고객·라이더 403. 다른 서비스 호출 없음, 읽기 전용. 검색어·배송지는 로그 금지.
  - 색인(V9): `customer_order(confirmed_at DESC, order_id DESC)`, `customer_order(mfc_id, confirmed_at DESC, order_id DESC)`, `customer_order_shipping(regexp_replace(receiver_phone,'[^0-9]','','g'))`.
  - 게이트웨이: 경로가 이미 라우팅된 `/api/v1/orders/**` 아래라 라우트 변경 없음. 허용 목록(`user-type-allowlist.txt`)만 다시 생성(OPERATOR 2줄).
- **추가(2026-11-15, 사용자 "기록 남긴다" — 계약 order 0.4.0·audit 0.2.0, 구현, 커밋만 — 미배포)**: 상세 배송지는 **가림**(`shippingAddressMasked=true`: 이름 홍*동, 연락처 010-****-1234, 상세주소 `***`, 출입 메모·핀 없음, 도로명·우편번호·출입 방법 그대로). 원문은 `POST /orders/lookups/{orderId}/pii-reveals`(`reason` 2~200자 필수, `fields` NAME·PHONE·ADDRESS·ENTRANCE 선택 — 없으면 전부, Idempotency-Key·X-Source-System) → audit-service `POST /internal/pii-access-logs` 에 동기 기록(행위자·역할·고객 accountId·`ORDER:<orderId>`·항목·사유)된 뒤에만 원문(요청 항목만). **audit 미응답·결과 불명 → 503, 원문 없음**(BO-25 §3). 가린 GET 은 기록하지 않음. 클라이언트는 "보기" 토글 대신 사유 입력 → pii-reveals 호출로 바꿔야 한다(handoff 는 PC 1).
  - 배포 전 할 일: pmart-config `order-service-lan.yml`·`-local.yml` 에 `pmart.order.audit-base-url`, LAN 에서 audit-service 기동 확인(Flyway V2).
- **남은 것**: 열람 기록을 누가 볼 수 있는지(직원용 조회 API)·보존기간 결정, 배송 요청사항(deliveryNote) 가림 여부. RIDER_OPS 역할의 Keycloak·employee-auth 쪽 정의는 별도.
