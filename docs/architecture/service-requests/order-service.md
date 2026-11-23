# order-service 에 대한 요청 (MFC 쪽에서)

## O-1. `PackageHandedOver` 구독 → 인계 수량 반영 (2026-11-09 기록)

- **요청한 곳**: mfc-fulfillment(`docs/business/flows/package-handover-contract.md` §5).
- **지금**: order 의 `mfc.fulfillment.v1` 구독은 `PreparationStarted` 만 처리. 줄 수량 변경은 CANCEL_BEFORE_SHIP·SHORTAGE 외 사유를 422 로 막는다(`OrderService`).
- **order-service 가 할 것**
  1. `PackageHandedOver` 구독 → 줄마다 `handedOverQty +q`, `unfulfilledQty −q`(사유 `HANDOVER`, sourceRef = handoverId, 멱등).
  2. 주문 상태 PARTIALLY_HANDED_OVER / IN_DELIVERY.
  3. 배송 사실(`DeliveryCompleted`·`DeliveryFailed`)은 delivery-execution(DE-1) 이 생긴 뒤 같은 방식으로.
- **LAN 에서 본 것(2026-11-16, PC 5 라이더 E2E)**: 주문 `ORD-0e67de38-7acf-3e71-8a28-7aeadd986bb0` 이 포장 인계(`PackageHandedOver`)·픽업(`PickupCompleted`)·전달 완료(`DeliveryCompleted`)까지 갔는데 주문 상태는 **PREPARING 그대로**다. delivery-execution 은 이미 이 사건들을 낸다(DE-1 구현·배포) — 위 3 도 할 수 있다. 고객 앱 주문 상태·리뷰 작성 가능 여부가 이 사건들에 걸려 있으면 같이 확인 필요.
- **사용자 결정**: 필요 없음(계약 문서에 규칙 있음).
- **구현(2026-11-16, PC 1)**: `PackageHandedOver`(mfc.fulfillment.v1) → 행마다 미이행 −q·인계 +q(같은 행 LOT 여러 줄은 합), `OrderLineQuantityChanged`(reason `HANDOVER`, `handoverId`). `DeliveryCompleted`(delivery.delivery.v1, 새 바인딩 `deliveryEvents-in-0`) → 전달 +q, 다 전달되면 `OrderCompleted`(deliveryIds), 아니면 `OrderPartiallyDelivered`. 상태는 수량으로 정하고 뒤로 가지 않는다(PARTIALLY_HANDED_OVER → IN_DELIVERY → PARTIALLY_DELIVERED → COMPLETED, `OrderRules.progressStatus`). 같은 handoverId·deliveryId 는 한 번(`order_fulfillment_fact`, V10), 역순(전달 먼저)도 받는다, 수량이 맞지 않으면 반영하지 않고 MISMATCH 로 남긴다. 일부 인계 뒤 남은 미이행을 출고 전 취소·결품으로 지우면 진행 상태도 앞으로. **남은 것**: `DeliveryFailed` 반영(실패 수량·상태 — 규칙 미정, 자동 환불 없음), 반품 수량(returnedQty).
- **결정(2026-11-21 사용자 "추천대로" — (나) 주문 상세 배송 목록에 "전달 실패")**: 이미 그렇게 동작한다 — 2026-11-19 BR-61 배송 연결(`DeliveryLinkRules`·`DeliveryLinks`)이 `DeliveryFailed`(고객 부재 포함 모든 사유)를 그 배송의 `Shipment.state` FAILED 로 보인다(주문 상태·수량·돈은 그대로, 되돌리기 O-10 은 IN_DELIVERY 로). 시험 `FulfillmentProgressIT`. 코드 변경 없음.

## O-2. 고객 자율 취소 마감 = 상품준비 전 (2026-11-09, ADR-0052)
- 고객 앱(주문 내역)의 취소는 주문이 `PREPARING` 이 되기 전까지만 받는다. 그 뒤는 고객센터 문의 접수 → 출고 중지 확인된 미인계 수량만 취소.
- 할 일: 지금 취소 가능 판정(order·orchestration S3)과 대조해 고객 토큰 요청은 `PREPARING` 이후 거절(코드 예: `CANCEL_WINDOW_CLOSED`), 운영자(고객센터) 경로는 지금처럼. 고객앱 취소 버튼 노출 기준도 같이 — 클라이언트 공지.
- **확정·처리(2026-11-19, 사용자 "확정")**: 이미 그렇게 동작한다 — claim-service `ClaimRules.checkFullCancel` 이 고객 접수를 주문 `CONFIRMED`(상품준비 전)일 때만 받고, 그 뒤는 422 `ORDER_NOT_CANCELLABLE`(새 코드 CANCEL_WINDOW_CLOSED 는 만들지 않음 — 앱이 이미 이 코드를 처리). 운영자 경로도 ADR-0060 으로 같은 시점(상품준비 뒤 고객센터 취소 없음 — 반품 안내). 시스템 자동 취소만 PREPARING 도 받는다(현장 중지). 코드 변경 없음.

## O-3. 확인된 오배송 — 새 발송 (2026-11-09, ADR-0052)
- 오배송이 확인되면 같은 상품·수량을 MFC 에서 다시 확보·피킹·포장해 원주소로 보낸다(구매자 비용 0원). 재고 부족 품목은 그 품목만 부분 취소·환불(ADR-0049 방식).
- 같은 피해 수량을 새 발송과 환불로 겹치지 않게 주문 수량 원장에 기록. 회수(DD-2)와 동시에 시작.
- **2026-11-16**: 회수 쪽(DD-2)은 구현 — dispatch 가 case `MisdeliveryConfirmed` 로 회수 배송을 만든다.
- **구현(2026-11-16, 사용자 "새상품 재배차 서비스 넣어" → "전부 PR로" — PC 5 가 영역별 PR, PC 1·PC 3 검토)**: orchestration 재발송 사가가 같은 사건을 받아 order `RESHIPMENT` 원장 → 재고 예약(모자란 품목은 줄임) → MFC 재발송 작업 지시(kind RESHIPMENT) → 못 보내는 품목 SHORTAGE·부분 환불. 흐름 `docs/business/flows/misdelivery-recovery-reshipment.md`. 브랜치 `platform/misdelivery-reshipment-saga`(order·orchestration), `mfc/misdelivery-reshipment-work`(fulfillment), `delivery/misdelivery-reshipment`(dispatch 표시·약속).

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

## O-7. 반품 수량(returnedQty) 반영 (2026-11-17, platform — claim CL-1 뒤)
- **왜**: claim 이 인계 뒤 회수 반품(type RETURN)을 처리한다(2026-11-17, claim 0.2.0). 검수 뒤 환불까지 끝나도 주문 원장의 `returnedQty` 는 0 그대로다 — `line-quantity-changes` 가 reason RETURN 을 422 로 막는다. review-service 의 "받은 수량 전부 반품" 판정·고객 앱 주문 상세가 이 값을 본다.
- **할 것(order — PC 1)**: 둘 중 하나. (가) `line-quantity-changes` reason RETURN(행마다 returnedQty +q, 상태는 그대로, 한도 = 전달(또는 인계) − 이미 반품, claimId·sourceRef = inspectionId) — claim 이 검수 환불 성공 때 부른다. (나) `ClaimClosed`(outcome RETURN_REFUNDED)·`RefundIntentCreated`(reason RETURN 의 basis.lines)를 구독해 반영. 어느 쪽인지 정한 뒤 claim 쪽 호출을 붙인다(claim 은 지금 부르지 않는다).
- claim 의 반품 가능 수량 심사는 order 값이 아니라 claim 의 진행·완료 반품 기록으로 한다(이 값이 생겨도 이중으로 빼지 않게 그때 맞춘다).
- **결정(2026-11-17 사용자 "추천대로" — CL-1 추가 결정 9)**: (나) 사건 구독. claim 이 반품 종결 `ClaimClosed` 에 행별 반품 수량 `returnedLines` 를 싣고 order 가 받는다(검수 환불마다가 아니라 클레임이 끝날 때 한 번 — 받은 만큼만 끝낸 반품도 같은 길).
- **구현(2026-11-17, PC 1 — order 계약 0.11.2·V15, 커밋만 — 미배포)**: 구독 `claimEvents-in-0` ← order.claim.v1 `ClaimClosed`(outcome RETURN_REFUNDED·RETURN_SHORT_CLOSED만, 취소·기한 만료 반품은 수량 없음) → `OrderService.claimClosed`: 업무 키 `order_fulfillment_fact`(kind RETURN, source_ref claimId — V15 가 CHECK 넓힘)로 한 번, `OrderRules.returned`(행마다 +q, 한도 = 전달 − 이미 반품 — 넘거나 없는 행이면 반영 없이 MISMATCH), 주문 상태 그대로·버전 +1·행마다 `OrderLineQuantityChanged`(reason RETURN·returnedQty·claimId). 내부·고객 조회의 `returnedQty` 가 채워진다(API 모양 그대로). `line-quantity-changes` reason RETURN 은 그대로 422.
  - 배포 전: pmart-config `order-service.yml` 에 `claimEvents` 함수·바인딩(order.claim.v1). claim 이 `returnedLines` 를 싣기 시작하는 claim 0.3.0 과 같이(이전 claim 의 ClaimClosed 는 행이 없어 무시 — 로그 경고).

## O-8. 기간 주문 요약·상품별 판매 집계 — 백오피스 대시보드 D2·D3 (2026-11-18 기록, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: 백오피스 웹 대시보드(pmart-clients `docs/handoff/backoffice/2026-11-18-backend-dashboard-metrics.md` D2·D3) → backoffice-bff(PC 2)가 부른다. 화면은 금액·건수를 서버 값만 쓴다(목록을 받아 더하면 쪽 상한에 걸림).
- **사용자 결정(2026-11-18, PC 2 추천대로)** — 매출 정의 Q-19 가 정해지기 전의 대시보드 기준:
  - 기준 날짜 = **주문 확정 시각(`confirmed_at`)의 한국 날짜**(ADR-0010). 기준이 다른 숫자를 섞지 않는다(SCM 원문 "기준이 다른 숫자를 섞지 않는다").
  - D2 = 상태별 주문 수 + **결제 승인 금액 합·건수**(확정 주문의 `approved_amount`). 환불은 빼지 않는다 — 환불 금액은 claim 에서 따로(CL-3) 나란히 보인다(순매출을 만들지 않음).
  - D3 = 상품(SKU)별 **확정 수량 − 출고 전 취소 수량**과 그 금액(줄 금액 − 할인 분담, 같은 비율), 상위 N. 반품은 빼지 않고 "주문 확정 기준"이라 표시.
- **필요한 것**: 직원(OPERATOR_ADMIN) 조회 — 예 `GET /orders/metrics/summary?from&to&mfcId`(상태별 count, approvedAmount 합·건수) · `GET /orders/metrics/top-products?from&to&mfcId&limit`(sku·productId·qty·amount). from·to 한국 날짜 포함, 기간 상한(예 93일), 정수 KRW. 이름·형태는 order 가 정한다.
- **지금(임시)**: 없음 — 화면은 칸을 만들지 않는다. bff 는 받으면 `views/metrics` 또는 새 칸으로 잇는다.
- 근거 표: `customer_order`(status·mfc_id·approved_amount·confirmed_at, 색인 `ix_customer_order_confirmed`·`ix_customer_order_mfc`), `customer_order_line`(sku·quantity·cancelled_before_ship_qty·line_amount·discount_share).
- **구현(2026-11-18, PC 1 — order 0.13.0)**: OPERATOR_ADMIN 만 `GET /orders/metrics/summary?from&to&mfcId`(상태별 건수, 결제 승인 금액 합·건수)·`GET /orders/metrics/top-products?from&to&mfcId&limit`(SKU 별 확정 − 출고 전 취소 수량·금액, 기본 10·최대 50). 확정 시각 한국 날짜, 93일 이하, 환불 안 뺌. 시험 `OrderMetricsRulesTest`·`StaffOrderLookupIT`.


## O-9. 주문 조회 목록의 상태별 수 — 목록 맨 위 숫자 띠 (2026-11-19, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: 백오피스 웹(pmart-clients `docs/handoff/backoffice/2026-11-19-backend-list-status-counts.md`).
- **필요한 것**: `GET /orders/lookups/counts?<목록과 같은 거르기>` — `GET /orders/lookups` 와 같은 거르기(기간 createdFrom·createdTo·mfcId·customerId 등, `status` 제외)로 `status`(OrderStatus)별 수.
- 응답 모양(웹 요청 그대로, case 0.26.0 `StatusCounts` 와 같게): `{"counts":[{"key":"<상태>","count":12,"capped":false}],"asOf":"…"}` — **모든 상태 값을 0 까지 빠짐없이**, 셀 상한을 넘으면 `capped=true`(화면 "N+"), 원천을 못 읽으면 503(화면 "?").
- **웹 질문**: 주문 수에 셀 상한이 필요하면 그 값(화면 "N+") — order 가 정해 적어 줌(기간 31일 상한 안이면 정확히 세도 되는지).

## O-10. 전달 실패 확정 되돌리기 — order 배송 연결·orchestration 재발송 사가 (2026-11-19, 보낸 영역 delivery — 사용자 "확정 되돌리기 기능 만들기")
- 계기: LAN 28767701-1 — 백오피스가 반려하려다 "지원센터 직접 실패 처리"(사고·다른 라이더 반납)가 눌려 확정됨. 상품은 원래 라이더 손, 사용자는 그 라이더가 그대로 전달하길 원함.
- PC 5 구현(execution 0.32.0): `POST /deliveries/{id}/failure-reversals` — 확정 뒤 30분 안·상품 라이더 손·반납 받기 전·수행료 확정 전이면 배송을 실패 등록 전 상태(PICKED_UP 등)로 되돌리고 **`DeliveryFailureReverted`**(delivery.delivery.v1 — deliveryId·orderId·riderId·deliveryType·purpose·originalReason·restoredState·returnDeliveryId·revertedAt). dispatch(원래 배송 다시 열기·반납 배송 취소)·tracking·notification(재발송 안내 정정)은 PC 5 가 같은 PR 에서 맞춤.
- 요청(PC 1):
  1. **order-orchestration**: `DeliveryFailed`(RIDER_INCIDENT·DAMAGED)로 시작한 새 상품 재발송 사가 — 같은 배송의 `DeliveryFailureReverted` 가 오면 **MFC 재발송 작업 시작 전이면 취소**(작업 지시 취소·재고 복귀). 이미 시작·출고됐으면 어떻게 할지 정해 주세요(PC 5 추천: 이미 피킹이 시작됐으면 재발송은 그대로 두고 상담원 사건 — 고객이 두 번 받는 것은 막아야 함).
  2. **order**: 배송 연결(`DeliveryLinkRules` — DeliveryFailed 를 FAILED 단계 3) — `DeliveryFailureReverted` 면 그 배송을 다시 진행 중(PICKED_UP 단계)으로. 단계 순위로 막히지 않게.
- 순서 주의: 같은 배송 키라 DeliveryFailed → DeliveryFailureReverted 순서는 보장된다. 재발송 사가가 아직 시작 전이면 시작하지 않게(되돌림 먼저 본 경우).

## O-11. 센터 주문 수·매출 추이 — 시간·날짜별, 카테고리·상품별, 센터 관리자 범위 (2026-11-21, 보낸 영역 mfc — PC 3)
- **요청한 곳**: MFC 백오피스 대시보드(pmart-clients `docs/handoff/mfc-backoffice/2026-11-21-backend-center-order-metrics.md`). 사용자 2026-11-21 "주문량으로(매출이 좋은 시기·시간대를 알려고), 카테고리별·상품별 매출 추이".
- **필요한 것**
  1. 시간·날짜별 계열 — 예 `GET /orders/metrics/series?mfcId&from&to&bucket=HOUR|DAY&categoryId&sku` → 줄 `{bucket(칸 시작, Asia/Seoul), orderCount, approvedAmount, qty?}`, 0건 칸 없음. 기준 시각은 O-8 과 같은 `confirmed_at`. HOUR 7일, DAY 93일(또는 366일).
     - categoryId = 그 카테고리 상품이 든 주문 수·그 카테고리 줄 금액, sku = 그 상품 판매 수량·금액.
  2. **권한 — 사용자 결정 필요**: 센터 관리자(MFC_SUPERVISOR)도 **토큰 센터만**(mfcId 필수·검사). O-8(2026-11-18 사용자 결정)은 OPERATOR_ADMIN 만이라 summary·top-products 를 여는 것도 결정을 바꾸는 일이다.
  3. **카테고리**: top-products 줄에 `categoryId`·`categoryName`(주문 때 사본)과 카테고리 목록을 읽을 곳. 주문 줄에 카테고리 사본이 있는지 확인 필요 — 없으면 원천은 product/catalog(PC 1 확인).
  4. (있으면 좋음) 요일 × 시간대 평균 `bucket=WEEKDAY_HOUR`(1년 열지도를 한 번에).
- **정의**: 매출 = 승인 금액(환불 빼지 않음, Q-19 전) — O-8 과 같다.
- **지금(임시)**: 화면은 목 모드에서만 그리고, 실서버는 "센터 주문·매출 지표 API 없음"을 보인다.
- 같은 모양 참고: mfc-fulfillment 0.34.0 피킹 지표 칸 단위(`date`·`hour`, date_trunc Asia/Seoul).
- **권한 결정(2026-11-23 사용자, PC 3 전달 — pmart-clients `docs/handoff/mfc-backoffice/2026-11-23-backend-order-metrics-supervisor.md`)**: MFC_SUPERVISOR 도 연다 — **토큰 센터만**(mfcId 필수·검사, 다른 센터 403). 범위 = O-11 계열 + 지금 있는 `summary`·`top-products`(O-8 의 OPERATOR_ADMIN 만을 이만큼 바꿈). OPERATOR_ADMIN 은 그대로 모든 센터. 매출 정의·개인정보 없음 그대로.
- **카테고리(3번) 확인(PC 1, 2026-11-23)**: 주문 줄에 카테고리 사본이 없다(order 마이그레이션 V1~V9 에 category 열 없음) — 상품별 카테고리는 product 공개 트리(또는 search 색인)에서 붙여야 한다. 구현 때 정함.
- **구현(2026-11-23, PC 1 — order 0.20.0)**: 1 `GET /orders/metrics/series`(HOUR·DAY·WEEKDAY_HOUR, sku·categoryId) 2 권한(summary·top-products·series 모두 — 관리자 모든 센터, 센터 관리자 mfcId 필수·토큰 센터만) 3 top-products `categoryId`·`categoryName`·거르기(product 지금 값 5분 사본 — 주문 때 사본 아님) 4 WEEKDAY_HOUR 합·평균(`days` 분모, 366일). 카테고리 목록은 product 공개 `GET /categories/tree` 를 화면이 직접. 시험 order check 94 통과. 회신 `docs/handoff/clients/mfc-backoffice/2026-11-23-reply-order-metrics-supervisor.md`.

## O-12. 고객별 구매 요약 — 고객 분류용 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **왜**: 고객 상세·목록의 고객 분류와 "최근 주문일" 거르기.
- **사용자 결정(2026-11-23) 고객 분류**: 신규 = 가입 1달 이내 · 우수 = 3개월 안 구매금액 100만원 이상 · 휴면 = 1년 동안 구매 없음 · 위험 = 6개월 이상 구매 없음 · 그 밖 일반. (가입일은 customer, 구매는 order — bff 가 둘을 합쳐 이름을 붙인다)
- **확정(2026-11-23 사용자 "요청서대로 진행해")**: 휴면(1년 구매 없음)과 위험(6개월 이상 구매 없음)이 겹치면 **휴면** — 위험 = 마지막 구매가 6개월~1년 전.
- **필요한 것**: 고객 ID 여러 개(50개)로 `{customerId, lastOrderAt, orderCount, amount3m}`(구매 = 확정 주문, 금액 = 승인 금액 — O-8 과 같은 정의, 환불 빼는지 알려 주세요). 목록 거르기용으로 "마지막 구매일 기간" 으로 고객 ID 를 찾는 길도.

## O-13. 고객 분석 지표 — 구매 빈도·누적 구매액·재구매율·이탈률 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **사용자 결정(2026-11-23)**: **이탈률 = 한 번 사고 다시는 안 산 고객의 비율**.
- **필요한 것**: 기간(가입·첫 구매 기준) 안 고객의 분자·분모 — 이탈 = 주문 1건 고객 / 주문 1건 이상 고객, 재구매 = 주문 2건 이상 고객 / 주문 1건 이상 고객, 누적 구매액·평균 구매 횟수. 비율은 화면이 계산(BO-28 — 분자·분모 그대로).
- 고객 한 명 칸: 누적 구매액·구매 횟수·첫·마지막 구매일(O-12 와 같이 줘도 됨).
