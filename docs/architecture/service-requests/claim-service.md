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
  2. 회수비: 접수 때 payment 결제 시도(`RTF-<claimId>`, 9,000원) → 고객 앱 결제(`GET /claims/{id}` 의 feePayment, 실패 뒤 `POST /claims/{id}/fee-payment-attempts`) → **결제된 뒤에만 회수 요청**. 기한(`pmart.claim.return-fee-payment-wait`, LAN 제안 24시간 — 사용자 확인 전) 지나면 반품 취소, 그 뒤 승인은 보상 취소. 면제면 바로 회수 요청.
  3. 회수: `ReturnPickupRequested`(시도마다 `<claimId>-P<n>`) → delivery 회수 배송(DD-3, PC 5 — 구현 전). 첫 실패 자동 재요청, 두 번째 실패 RECOVERY_FAILED + `ReturnRecoveryFailed`(case R-8 요청) → 상담원 `POST /claims/{id}/recovery-actions` RETRY_PICKUP·CANCEL.
  4. `ReturnItemReceived` → 수령 수량. 5. `ReturnInspected` → 검수 수량 × 원단가 − 할인 안분 부분 환불(RESALE·DISPOSE 모두) → RefundResult → 모든 행 검수·환불되면 CLOSED.
  - 남은 것: delivery DD-3(회수 배송·DeliveryFailed 에 claimId), case R-8(재연락 사건), order O-7(returnedQty), notification(결제 필요·회수 실패 안내), 클라이언트(고객 앱 회수비 결제·백오피스 반품 접수 — handoff `docs/handoff/clients/all/2026-11-17-request-return-recovery.md`), 접수 기한 판정·받은 수량 부족 시 닫기.

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
