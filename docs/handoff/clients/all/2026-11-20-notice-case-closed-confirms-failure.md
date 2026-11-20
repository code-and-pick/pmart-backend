# 알림 — 지원센터 사건 "처리완료" = 전달 실패 확정

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-20
- 대상: **라이더 백오피스**(지원센터 사건 처리), **백오피스 사건함**(사건 닫기), **라이더 앱**(전달 불가 뒤 화면)
- 근거: 사용자 2026-11-20 "지원센터에서 처리완료하면 아예 배차 취소되게 하고, 회수배차 바로 요청오고 진행하게 해"
- 상태: **구현 — PR 올림(PC 1 병합·배포 전)**. delivery-execution, API·사건 계약 변경 없음.

## 바뀌는 것
- 라이더가 전달 불가를 등록해 결정을 기다리는 배송(`FAILURE_REPORTED`)의 지원센터 사건(DELIVERY_INCIDENT)을 **사건함에서 닫으면(처리완료)**, execution 이 그 배송을 **실패 확정**한다 — `failure-decisions` CONFIRM 과 같음.
  - 배송 `FAILED` + `DeliveryFailed` → dispatch 배차 종료, 상품이 라이더에게 있으면 **같은 라이더에게 반납 배송**(FAILED_DELIVERY_RETURN)이 바로 생긴다.
  - 상품 위치는 라이더가 등록한 값, 수행료 규칙은 직접 확정과 같다(고객 사유면 수락 당시 금액, 사고는 거리 비율, 그 밖 산정 불가 → 조정).
  - 고객 반품 회수 배송이면 `ReturnPickupFailed`(지금 확정과 같음).
- 배송 조회 `failure.decision` = CONFIRM, `failure.decidedBy` = 사건을 닫은 직원.

## 화면이 할 것
- **라이더 백오피스·사건함**: 라이더를 **계속 전달하게 하려면 사건을 닫기 전에 "반려"**(`failure-decisions` REJECT)를 먼저. 반려 없이 닫으면 확정된다 — 닫기 버튼 옆에 "닫으면 전달 실패로 확정되고 반납 배송이 만들어져요" 안내를 권한다. 다른 라이더 반납·상품 위치 지정이 필요하면 닫기 전에 직접 확정(`returnBy`·`custody`).
- **라이더 앱**: 확정 뒤 `failures/resume-requests` 는 409(실패 등록 상태가 아님). 배송 상태가 FAILED 로 바뀌고 반납 배송이 배정되니 지금의 실패 확정 화면 흐름 그대로.
