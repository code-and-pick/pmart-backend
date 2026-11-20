# claim-service 에 대한 요청 (MFC 회수품 쪽에서)

## CL-1. 반품·인계 뒤 취소의 회수·환불 (2026-11-08, 사용자 결정 D-7 + "환불 격리로 뒀다가 품질 검수, 적격이면 재배치")

- **결정(D-7)**: 픽업·배달 시작 뒤 고객 취소는 **고객센터를 통해서만**(R-2), **배송·회수비 9,000원 고객 부담**(Delivery 381행), 회사 잘못(오배송·지연 등)이면 면제(사유 목록은 고객센터 서비스 때). 회수비는 상품 환불액과 **상계하지 않고 따로 결제**, 상품 환불은 **회수품 검수 뒤**(OMS·Commerce: 회수비 납부 ≠ 반품 승인·환불 완료).
- **MFC 쪽(구현됨, 2026-11-08)**:
  1. mfc-inbound `POST /return-receipts` — 회수품을 반품 위치에 **격리**로 반입(재고 H+q·Q+q, 판매 불가) → `ReturnItemReceived`(claimId·returnReceiptId·줄별 lot·반입자).
  2. mfc-quality `POST /return-inspections` — 반입자가 아닌 센터 관리자가 검수: RESALE(상온·미개봉·신선 아님만) → 격리 해제, DISPOSE → 폐기 승인 요청 → `ReturnInspected`(claimId·줄별 disposition RESALE/DISPOSE).
  3. mfc-inbound 가 `ReturnInspected` 의 RESALE 분 → 적치(PUTAWAY) 작업 — 진열 규칙대로 선반에 **재배치**.
- **claim-service 가 할 것(아직 없음 — 반품 클레임 RETURN 이 미구현)**
  1. 반품·인계 뒤 취소 클레임(type RETURN) 접수: 고객센터 채널, 회수 수량·사유·회수비 여부(면제 사유) 기록.
  2. 회수비 9,000원 결제 요청(payment) — 상품 환불과 별도 거래. 면제면 없음.
  3. 회수 요청 → delivery 회수(ReturnPickupRequested) → MFC 반입.
  4. `ReturnItemReceived` 구독 → 클레임 "회수 수령"(계약 `/internal/claims/{id}/return-receipts` 와 같은 의미 — 미수령 수량 남김).
  5. `ReturnInspected` 구독 → **검수 뒤 상품 환불**: 원단가 × 회수 수량(할인 안분은 OMS 규칙), RESALE·DISPOSE 모두 환불(고객 책임 파손 시 감액 여부는 **사용자 결정 필요**). payment 부분 취소 → RefundResult → 클레임 CLOSED.
- **결정이 더 필요한 것**: 고객 책임 파손·개봉 시 환불 감액 여부, 회수비 면제 사유 목록, 회수 실패(고객 부재) 처리.
- **결정(2026-11-17 사용자 "추천대로")**: 1차 감액 없음(전액 환불, 회수비로 갈음) · 회수비 면제 사유 = 오배송·상품 불량·배송 중 파손·약속 시간 크게 지연·잘못된 대체 · 회수 실패 = 2번 시도 뒤 상담원 재연락, 회수비 한 번만. 구현 claim(PC 1), 고객 반품 회수 배송은 delivery(PC 5) 요청.
- **구현(2026-11-17, PC 1 — claim 계약 0.2.0·V4, payment 계약 0.1.1, 커밋만 — 미배포)**: 흐름 정본 `docs/business/flows/return-exchange-refund.md` §6.
  1. 접수: `POST /claims` type RETURN — 직원 CS_AGENT·OPERATOR_ADMIN 만, 본인 확인 방법(case CS 접수와 같은 값) 필수, 반품 가능 = 인계 수량 − 진행·완료 반품. faultCode(위 다섯)면 회수비 면제.
  2. 회수비: 접수 때 payment 결제 시도(`RTF-<claimId>`, 9,000원) → 고객 앱 결제(`GET /claims/{id}` 의 feePayment, 실패 뒤 `POST /claims/{id}/fee-payment-attempts`) → **결제된 뒤에만 회수 요청**. 기한(`pmart.claim.return-fee-payment-wait`, 24시간 — 사용자 확정 2026-11-17 추가 결정 1) 지나면 반품 취소, 그 뒤 승인은 보상 취소. 면제면 바로 회수 요청.
  3. 회수: `ReturnPickupRequested`(시도마다 `<claimId>-P<n>`) → delivery 회수 배송(DD-3, PC 5 — 구현 전). 첫 실패 자동 재요청, 두 번째 실패 RECOVERY_FAILED + `ReturnRecoveryFailed`(case R-8 요청) → 상담원 `POST /claims/{id}/recovery-actions` RETRY_PICKUP·CANCEL.
  4. `ReturnItemReceived` → 수령 수량. 5. `ReturnInspected` → 검수 수량 × 원단가 − 할인 안분 부분 환불(RESALE·DISPOSE 모두) → RefundResult → 모든 행 검수·환불되면 CLOSED.
  - 남은 것: delivery DD-3(회수 배송·DeliveryFailed 에 claimId), case R-8(재연락 사건), notification(결제 필요·회수 실패 안내), 클라이언트(고객 앱 회수비 결제·백오피스 반품 접수 — handoff `docs/handoff/clients/all/2026-11-17-request-return-recovery.md`), 접수 기한 판정. order O-7(returnedQty)·받은 수량 부족 시 닫기는 아래 추가 결정 구현에서 했다.
- **추가 결정(2026-11-17 사용자 "추천대로")**:
  1. 회수비 결제 기한 **24시간**(`return-fee-payment-wait: PT24H` 확정).
  2. 회수 실패로 반품을 취소할 때 낸 회수비: **고객 사유**(CUSTOMER_ABSENT·UNREACHABLE·ADDRESS_ERROR·REFUSED — `ReturnPickupFailed.customerReason`) → 돌려주지 않음, **라이더·우리 잘못**(RIDER_INCIDENT) → 돌려줌, **파손·기타**(DAMAGED·OTHER) → 상담원 판단(기본 돌려주지 않음).
  3. 회수 시도 **전체 3번**(자동 2번 + 상담원 재시도 1번) — 넘으면 상담원은 반품 취소만.
  4. 우리 쪽 실패(RIDER_INCIDENT)는 **횟수에서 빼고** 바로 다시 회수 요청.
  5. 반품 전체 기한 **접수 뒤 7일** — 그 안에 회수가 안 되면 자동 취소(회수비는 2번 규칙대로).
  6. MFC 가 요청보다 적게 받으면 상담원이 **"받은 만큼만 끝내기"**(남은 수량 환불 없음·클레임 완료).
  7. 반품 가능 기간(7일·신선 24시간)은 지금처럼 상담원 판단 — 배송 완료 시각을 받게 되면 자동 검사.
  8. MFC 검수 결과(`ReturnInspected`)에 주문 줄 번호 — PC 3 요청(선택).
  9. 반품 수량은 order-service 가 claim 사건을 받아 기록(O-7).
- **추가 결정 구현(2026-11-17, PC 1 — claim 계약 0.3.0·V5, order 계약 0.11.2·V15, 커밋만 — 미배포)**: 흐름 정본 `docs/business/flows/return-exchange-refund.md` §6.
  1. `return-fee-payment-wait: PT24H` 확정(설정 주석·문서에서 "제안값" 지움).
  2. 회수 실패마다 종류를 남긴다(`return_pickup.failure_kind` — CUSTOMER: `customerReason` true 또는 CUSTOMER_ABSENT·UNREACHABLE·ADDRESS_ERROR·REFUSED / OURS: RIDER_INCIDENT / OTHER: DAMAGED·OTHER·그 밖). 반품을 취소할 때(상담원 CANCEL·기한 자동 취소) **결제된** 회수비: 고객 사유가 하나라도 있으면 돌려주지 않음(KEEP), 아니고 파손·기타가 있으면 상담원 판단(`recovery-actions` CANCEL 의 `feeRefund`, 없으면 돌려주지 않음 — 자동 취소도), 우리 잘못뿐이면 돌려줌(REFUND). 규칙과 다른 `feeRefund` 는 422 FEE_REFUND_NOT_ALLOWED. 돌려주면 환불 의도(reason RETURN_FEE·scope FULL, 회수비 결제 시도의 전액 취소 — payment 사유 RETURN, 기존 환불 실행·sweep·RefundResult 그대로) — `feePayment.refundDecision`·`refundIntentId`, `refunds[].kind` RETURN_FEE, `RefundIntentCreated.kind`.
  3. 회수 시도 전체 3번: 횟수에 드는 실패 1번째 → 자동 재요청, 2번째 → RECOVERY_FAILED(상담원 재연락), 상담원 RETRY_PICKUP 은 횟수에 드는 실패가 3번 미만일 때만(아니면 409 PICKUP_ATTEMPTS_EXHAUSTED). `pickup.countedFailures`·`retryAllowed`, `ReturnRecoveryFailed.countedAttempts`·`retryAllowed`·`returnDeadlineAt`.
  4. RIDER_INCIDENT 실패는 횟수에 넣지 않고 바로 새 회수 요청(`<claimId>-P<n>` 번호는 계속 늘어남).
  5. `claim.return_deadline_at` = 접수 + `pmart.claim.return-deadline`(P7D — 없으면 반품 접수 422 CONFIG_REQUIRED, V5 가 기존 반품은 접수 + 7일로 채움). 점검 `ReturnService.expireOverdue`(`return-deadline-sweep-interval`, 기본 5분): 기한이 지났고 수거·반입·검수된 것이 없고 진행 중 회수 요청이 없는 FEE_PENDING·IN_PROGRESS·RECOVERY_FAILED 반품 → CANCELLED(reject_reason RETURN_DEADLINE) + ClaimClosed **RETURN_EXPIRED**, 회수비는 2번 규칙. 진행 중 회수는 결과를 기다리고, 기한 뒤 실패면 다시 요청하지 않고 그때 취소. 기한 뒤 상담원 다시 회수 409 RETURN_DEADLINE_PASSED.
  6. `recovery-actions` action **CLOSE_AS_RECEIVED**(IN_PROGRESS·RECOVERY_FAILED, 진행 중 회수 요청 없을 때): 행마다 정산 수량(`approvedQty`) = 받은 수량(반입·검수 중 큰 값) → 받은 만큼 검수·환불이 끝나면 CLOSED + ClaimClosed **RETURN_SHORT_CLOSED**(남은 수량 환불 없음, 반품 가능 수량은 요청 수량으로 계속 점유). 받은 것 없음 409 NOTHING_RECEIVED, 다 받음 409 NOT_SHORT, 회수 진행 중 409 PICKUP_IN_PROGRESS. 상담원 CANCEL 은 이제 수거된 회수품이 있어도 막는다(받은 만큼만 끝내기로).
  7. 코드 변경 없음(반품 가능 기간은 상담원 판단 그대로).
  8. claim 은 `ReturnInspected` 줄의 `orderLineId` 가 있으면 그 주문 행에, 없으면 지금처럼 SKU 로 맞춘다(이미 그렇게 짜여 있었음 — 같은 SKU 두 행 시험 추가). MFC 쪽 요청은 `mfc-quality-service.md` **MQ-1**(PC 3).
  9. 반품 종결 ClaimClosed(RETURN_REFUNDED·RETURN_SHORT_CLOSED)에 `type`·`returnedLines`(행별 orderLineId·qty·resaleQty·disposeQty) → order 가 구독해 `returnedQty` 기록(O-7).
  - 배포 전: pmart-config `claim-service.yml` 에 `return-deadline: P7D`(없으면 반품 접수가 422), `order-service.yml` 에 `claimEvents` 바인딩(order.claim.v1). 배포 순서 order(구독) → claim.

## CL-D1. 고객센터 취소 없음 — ADR-0052 2번 바꾸기 (2026-11-13, 보낸 영역 delivery — 사용자 결정, platform 할 일)

> **처리(2026-11-13, PC 1)**: ADR-0060(범위 = 상품준비 뒤 ~ 인계 전, 인계 뒤 회수 D-7 그대로 — 사용자 확인). 코드 경로는 원래 없었다 → 코드 변경 없음. case R-2 거둠, 고객 안내(customer-faq 02·03) 고침.
- **사용자 결정(2026-11-13, PC 1 검토 H1 답)**: "고객센터 취소 못하는 걸로 정할 거야." — ADR-0052 2번(상품준비 뒤 고객센터 경유 취소·미인계분)을 하지 않는다. 고객 취소는 자율 취소(피킹 시작 전)만.
- 할 것(platform): 새 ADR 로 ADR-0052 2번 대체, claim·order·orchestration 의 고객센터 취소 경로 정리, 고객앱·고객센터 안내 문구.
- 남는 중지 경로: 시스템 전체 결품 취소(PREPARING 주문). delivery-dispatch 는 포장 전 중지를 전체 중지로 보고 배차 취소·차단(2026-11-13, PR #8).

## CL-1 배송 쪽 구현 (2026-11-17, 보낸 영역 delivery — PC 5, 브랜치 `delivery/claim-return-pickup`)
- 고객 반품 회수 배송은 delivery 가 받는다: `ReturnPickupRequested` → 바로 배차 → `ReturnPickedUp`(claimId, lines = 요청 줄) → `ReturnDelivered`(MFC 반납). 자세히 `delivery-dispatch-service.md` DD-3.
- **claim 이 할 것**: ① `ReturnPickupRequested` 에 `mfcId` 싣기(선택 — 없으면 원래 배송의 MFC) ② 다시 회수할 때 새 사건으로(같은 사건 ID 를 다시 내면 delivery 는 무시한다) ③ 새 사건 `ReturnPickupFailed`(delivery.delivery.v1 — claimId·reason·customerReason·contactAttempts 횟수) 구독 → 몇 번째 실패인지 세기, 2번이면 상담원 재연락(사용자 결정 2026-11-17) ④ 진행 중인 회수 배송이 있는 동안 새 요청을 내지 않기(내도 delivery 가 무시하고 경고만).
- **PC 1 회신(2026-11-17)**: ① `ReturnPickupRequested` 에 `mfcId` 이미 실음(원주문 이행 센터) ② 다시 회수는 새 회수 요청 ID·attempt 로 새 사건 ③ **`ReturnPickupFailed` 구독 추가**(claim `ReturnService` — 회수 줄이 REQUESTED 일 때만 실패로 셈, 2번이면 RECOVERY_FAILED + `ReturnRecoveryFailed`; `DeliveryFailed` 에 claimId 가 있으면 같은 처리) ④ 실패한 회수 뒤에만 다음 요청을 낸다(진행 중엔 안 냄).
- **delivery 추가 요청(2026-11-17, PC 5 — 사용자 결정 "배달 중이나 전달 완료 전에는 반품 불가")**: 원래 배송이 전달 완료 전(배달 중·실패)이면 RETURN 클레임 접수를 거절해 주세요. delivery 는 그런 `ReturnPickupRequested` 를 받으면 회수 배송을 만들지 않고 경고만 남긴다(claim 에 알리는 사건은 없음 — 접수에서 막는 것이 정본).
- **delivery 점검 후속(2026-11-17, PC 5 — 브랜치 `delivery/return-after-delivery`, PC 1 검토 필요)**: claim 접수가 인계 수량(handedOverQty)으로 심사해 **배달 중 주문도 반품 접수**되고, 회수 요청에 원래 배송 ID 가 없어 delivery 의 "전달 전 거절"도 안 걸렸다(그 경우 반품이 REQUESTED 에서 멈춤). 이 PR 이 claim `ReturnRules` 를 **전달 완료 수량(deliveredQty)** 으로 바꿈(없으면 422 NOT_DELIVERED_YET — 계약에 있던 코드). delivery 쪽 안전장치는 `ReturnPickupFailed`(ORIGIN_NOT_DELIVERED).

## CL-2. 반품 취소 때 회수비 돌려주기 — 화면이 고를 수 있는지 알 수 있게 (2026-11-18, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: backoffice 웹(고객센터 반품 상세), 같은 날 웹 세션 보고.
- **공백**: claim 0.3.0 `recovery-actions` CANCEL 의 `feeRefund` 는 회수 실패 사유로 정해진다. 고객 사유(부재·연락 두절·주소 오류·거부) → 안 돌려줌(true 면 422), 라이더·우리 잘못뿐 → 돌려줌(false 면 422), 파손·기타 → 상담원이 고름. 그런데 `Claim`(`GET /claims/{id}`)에는 회수 실패 사유가 없다(`pickup` 은 state·attempts·failedAttempts·countedFailures·retryAllowed 만). 그래서 화면은 "파손·기타일 때만 고르기"를 보일 수 없다. 지금은 낸 회수비(PAID)가 있으면 늘 "낸 회수비 돌려주기"를 보이고, 고를 때만 `feeRefund: true` 를 보낸다. 규칙과 다르면 422 안내로 막는다.
- **제안(추천 1)**:
  1. `pickup.feeRefundOnCancel`: `REFUND`(자동 돌려줌)·`NO_REFUND`(안 돌려줌)·`AGENT_CHOOSES`(상담원이 고름)·null(낸 회수비 없음). 판정은 claim 이 이미 하고 있으니 결과만 내보낸다. 화면이 규칙을 따라 만들지 않아도 된다.
  2. (또는) `pickup.failures[]`: `{reason, customerReason, countedInLimit, failedAt}`. 화면이 규칙을 다시 짜야 해서 1보다 낫지 않다. 다만 상담원이 사유 이력을 보는 데는 쓸모가 있다.
- 필드 추가뿐이라 하위 호환. 정해지면 backoffice 웹이 AGENT_CHOOSES 일 때만 고르기를 보인다.
- **상태**: 요청(PC 1 결정·구현 대기).

## CL-3. 기간 환불 금액 합 — 백오피스 대시보드 D2 (2026-11-18 기록, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: 백오피스 대시보드 D2(주문 요약 O-8 과 나란히). 사용자 결정(2026-11-18): 매출에서 환불을 빼지 않고 **환불 금액을 따로** 보인다.
- **필요한 것**: 직원(OPERATOR_ADMIN) 조회 — 기간(환불 **완료 시각 `refund_intent.completed_at` 의 한국 날짜**, 포함) 안 완료된 환불의 금액 합·건수(정수 KRW), 선택 mfcId. 예 `GET /claims/metrics/refunds?from&to`. 종류(취소·반품)별로 나눠 주면 좋음.
- **지금(임시)**: 없음.
- **구현(2026-11-18, PC 1 — claim 0.5.0)**: OPERATOR_ADMIN 만 `GET /claims/metrics/refunds?from&to` — 완료 환불 합·건수 + 사유 묶음 CANCEL·SHORTAGE·RETURN·RETURN_FEE·OTHER. 환불 완료 시각 한국 날짜, 93일 이하. **센터 거르기 없음**(사용자 동의 — 출고 전 취소 클레임에 센터 없음).


## CL-4. 클레임 화면 표기 — 주문별 순번 `claimSeq` (2026-11-19, 보낸 영역 backoffice — PC 2, 받는 쪽 platform PC 1)

- **왜**: ADR-0070 덧붙임(2026-11-19, PR #219 — 추천안 그대로): 클레임은 `CLM-…` 대신 **주문번호 + 종류 + 그 주문의 몇 번째 클레임** — `74939255 반품 1`, `74939255 교환 1`. 하위(환불 `RFI-` 등)도 같은 방식 — `74939255 환불 1`.
- **필요한 것**:
  1. `claimSeq` — 같은 주문 안에서 1부터(종류와 상관없이 주문마다 하나의 순번인지, 종류마다 따로인지는 claim 이 정해 적어 줌 — 예시는 종류마다 1). 겹치지 않게 DB 가 정함(주문마다 잠금 또는 유일 제약). 기존 클레임도 생성순으로 채움.
  2. 환불 등 하위도 주문별 순번(`refundSeq` 등 — 이름은 claim 이 정함).
  3. 응답: `GET /claims`·`GET /claims/{id}`·환불 목록에 순번. 주문번호(8자리)는 order 가 정본 — claim 이 사본을 둘지는 claim 판단(bff `views/claims` 는 이미 order 에서 붙인다, 0.18.0).
  4. 찾기: 주문번호로 클레임 찾기(직원) — 있으면 좋음.
- **받는 쪽(backoffice)**: bff `views/claims` 가 `claimLabel`(주문번호 + 종류 + 순번)을 만들어 준다(종류 이름은 화면이). → **바뀜(2026-11-20 bff 0.23.0)**: 종류 이름이 화면 몫이라 bff 는 `claimLabel` 을 만들지 않고 줄마다 `claimSeq`·`orderNumber` 를 싣는다(화면이 조합). 주문번호로 찾기는 bff `views/claims?orderNumber=`(order 로 orderId).
- **처리(2026-11-20 PC 1 — 사용자 "진행해라", claim 0.10.0, LAN 배포)**: `claimSeq` = **같은 주문·같은 종류 안** 순번(예시대로 `74939255 반품 1`·`74939255 교환 1`), `refundSeq` = 같은 주문의 환불(상품·회수비 모두) 순번. V7 INSERT 트리거(주문마다 advisory lock, 유일 인덱스 (order_id, type, claim_seq)·(order_id, refund_seq)), 기존 행은 생성순으로 채움. 응답: `Claim`·`ClaimSummary.claimSeq`, `Refund.refundSeq`(상세 refunds·`GET /claims/{id}/refunds`). 4(주문번호로 찾기): claim 은 8자리 주문번호를 갖지 않는다 — bff 가 order 에서 orderId 를 얻어 `GET /claims?orderId=` 로.

## CL-5. 배차 불가 취소 — 전액 환불 + 2,000원 쿠폰 (2026-11-19, 보낸 영역 delivery — 사용자 "1,2,3 전부", "쿠폰 금액은 2000원", "취소는 주문 취소 및 전액 환불")
- 근거: 원문 Delivery §3.6 "배차 불가로 B마트에서 취소해야 할 시 환불 및 쿠폰으로 보상". 지금 직원 취소(0.4.0)는 CS_AGENT·OPERATOR_ADMIN·**상품준비 전**만이라, 포장까지 끝나 라이더를 기다리는 주문(배차 지연이 생기는 때)은 취소할 길이 없다.
- 요청:
  1. 사유 `DISPATCH_UNAVAILABLE`(배차 불가) 직원 전체 취소 — **픽업(인계) 전이면 상품준비 뒤에도**(PREPARING·포장 끝). 누가: 그 센터 `MFC_SUPERVISOR`(MFC 백오피스)·CS_AGENT·OPERATOR_ADMIN. 흐름은 시스템 취소와 같게(CancellationRequested → S3 현장 중지·배차 취소 → OrderCancelled → **원 승인액 전액 환불** — 봉투값·배달팁 포함). 이미 인계됐으면 422(회수는 반품 흐름).
  2. 취소가 확정되면 **2,000원 쿠폰** 발급(promotion — 고객 계정, 회사 사유 보상. 유효기간·최소 주문금액은 promotion 이 정하되 사용자에게 확인). 같은 주문 한 번.
  3. 고객 알림: 기존 주문 취소·환불 알림 + 쿠폰 안내(notification — 사유 DISPATCH_UNAVAILABLE 이면 "배달 기사를 찾지 못해 주문을 취소했어요. 전액 환불되고 2,000원 쿠폰을 드렸어요").
- 찾기: dispatch `GET /dispatch-delays?mfcId=`(0.12.0, PC 5 — 지연 안내가 난 픽업 전 배송) 에서 MFC 백오피스가 고르고 이 취소를 부른다.

- **자동 취소 추가(2026-11-20, PC 5 — 사용자 "쿠폰 안 되면 자동취소만"·"약속+30분")**: 내부 시스템 취소 `POST /internal/claims/system-cancellations` 사유에 `DISPATCH_UNAVAILABLE`(계약 0.9.1) — delivery-dispatch 가 약속 + 30분 라이더 없는 고객 배송의 주문을 자동 취소. claim 코드는 계약 enum 만(범위·흐름은 시스템 취소 그대로), 시험 `CancelClaimFlowIT.systemCancellationForDispatchUnavailable`. **상태: PR 올림(PC 5, platform 영역 — PC 1 검토)**.

## CL-6. 클레임 목록의 상태별 수 — 목록 맨 위 숫자 띠 (2026-11-19, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: 백오피스 웹(pmart-clients `docs/handoff/backoffice/2026-11-19-backend-list-status-counts.md`).
- **필요한 것**: `GET /claims/counts?<목록과 같은 거르기>` — `GET /claims` 와 같은 거르기(orderId 등, `state` 제외)로 `state`(ClaimState)별 수, 가능하면 `type`(ClaimType)별도(예 `?by=type` 또는 응답에 두 묶음).
- 응답 모양(웹 요청 그대로, case 0.26.0 `StatusCounts` 와 같게): `{"counts":[{"key":"<상태>","count":12,"capped":false}],"asOf":"…"}` — **모든 상태 값을 0 까지 빠짐없이**, 셀 상한을 넘으면 `capped=true`(화면 "N+"), 원천을 못 읽으면 503(화면 "?").
- **받는 쪽**: 웹 클레임 목록은 bff `views/claims`(0.18.0)를 쓰지만 수는 claim 이 정확히 센다 — 웹이 claim 을 직접 부른다(bff 로 묶지 않음).
- **처리(2026-11-19 PC 1 — 사용자 "추천대로 일단 해줘")**: 1·3 구현, **2(쿠폰)는 미룸** — promotion-service 가 "3단계 1차 비활성"으로 코드가 없어 쿠폰 발급·보관·견적 적용·사용·복원을 새로 만들어야 함, promotion 을 만들 때 같이(쿠폰 종류별 유효기간·최소 주문금액은 그때 사용자 결정).
  - claim 0.7.0: `POST /claims` 직원 토큰 type CANCEL·reason `DISPATCH_UNAVAILABLE` → `ClaimService.createDispatchUnavailableCancel` — 그 센터 `MFC_SUPERVISOR`(센터 권한, 남의 센터 404)·`CS_AGENT`·`OPERATOR_ADMIN`, 채널 CS·OPERATOR, **본인 확인 없음**, `checkFullCancel(…, system=true)`(PREPARING 도, 인계 뒤·미이행 0 은 422). 고객 접수에서 이 사유는 400. `CancellationRequested.reason` 에 그대로.
  - orchestration S3 → order 수량 변경 `cancelReason` → order 0.14.2 `OrderCancelled.cancelReason` → notification 취소 알림 "배달 기사를 찾지 못해 주문이 취소되었어요 / …결제 금액은 전액 환불돼요."(쿠폰 문구는 쿠폰이 생기면). 덤: 품절 전체 취소 문구도 이제 나간다(전에는 reason 이 늘 CANCEL_BEFORE_SHIP 이라 못 나감).
  - dispatch 는 원래대로 주문 전체 취소(FULL)로 픽업 전 배송을 거둔다(코드 변경 없음).
- **처리(2026-11-20 PC 1, claim 0.10.0, LAN 배포)**: `GET /claims/counts?orderId=&customerId=&type=&by=state|type` → `StatusCounts`(모든 값 0 까지, 정확히 세므로 `capped` 늘 false). 고객 토큰은 본인만.

## CL-7. 직원 클레임 목록 고객 거르기 — 백오피스 "고객" 상세 (2026-11-19, 보낸 영역 backoffice — PC 2, 받는 쪽 platform PC 1)

- **왜**: 백오피스 "고객" 메뉴(customer CU-4, 사용자 결정 2026-11-19 — 조회만)의 고객 상세에 그 고객의 클레임을 보인다. 지금 직원 `GET /claims` 는 orderId·type·state 로만 거른다.
- **필요한 것**: 직원 `GET /claims?customerId=`(완전 일치 — 클레임 계정 = 주문 고객, CS·OPERATOR 접수 포함), 최근 접수 순·커서. 권한은 지금 목록 그대로.
- **받는 쪽**: bff `views/customer`(BF-2)·`views/claims`(0.18.0 — 같은 거르기를 넘기게).
- **처리(2026-11-20 PC 1, claim 0.10.0, LAN 배포)**: 직원·서비스 토큰의 `GET /claims?customerId=`(완전 일치, 최근 접수 순·커서 그대로). 고객 토큰은 무시하고 늘 본인. 덤: 빈 목록이 `items: null` 이던 것을 `[]` 로.
