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
