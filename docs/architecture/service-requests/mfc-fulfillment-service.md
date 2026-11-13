# mfc-fulfillment-service 에 대한 요청 (mfc-capacity 쪽에서)

## F-1. 운영 제한 사건 구독 (2026-11-09 기록)

- **요청한 곳**: mfc-capacity — `OperationRestrictionApplied`/`Lifted` 를 2026-11-08 부터 발행한다(BO-08).
- **지금**: fulfillment 는 `AssignmentPublished` 만 구독. 작업 거절 사유 `OPERATION_RESTRICTED` 는 계약에만 있고 내지 않는다(`WorkOrderService` 주석 "원천이 아직 없어" — 이제 원천 있음).
- **할 것**: 구독 → 제한 중 센터의 작업 접수 거절(`OPERATION_RESTRICTED`). 이미 진행 중인 작업을 어떻게 할지는 BO-08 규칙대로.
- **사용자 결정**: 진행 중 작업 처리가 문서에 없으면 질문.

## F-2. 배차 요청 시점 — 피킹 시작 전 포장소요시간 입력 (2026-11-13, 보낸 영역 delivery — 사용자 결정)
- **사용자 결정(2026-11-13, PC 1 검토 H1 답)**: "픽업 시작 전에 포장소요시간 입력해서 그 값이 배차요청할 때 넘어가게 해." — **"픽업"은 "피킹"(MFC 에서 상품 집기)의 오기, 피킹 시작 전이 맞다(사용자 확인 2026-11-13, PC 5 — PC 1 검토 질문)**. 점원이 **피킹을 시작하기 전에 예상 포장(준비) 소요시간을 입력**하고, 그 값이 `DispatchRequested.estimatedPackMinutes`·`estimatedReadyAt` 으로 배차에 넘어가게.
- 지금(0.4.11): `POST /shipments/{id}/dispatch-requests` 는 PACK IN_PROGRESS/COMPLETED 일 때만(포장 중 이후). 바뀔 것: 피킹 시작 전(또는 시작과 함께) 입력·요청 가능하게. 자동 요청 없음(BR-51)은 그대로.
- delivery 쪽: 받는 사건 모양은 그대로라 바꿀 것 없음. 배차 요청이 더 이르게 오면 라이더가 포장 끝날 즈음 도착하도록 `estimatedReadyAt` 을 비용에 이미 쓴다(`BundleProblem` 늦음/기다림).
- 함께 결정: **고객센터 취소는 하지 않는다**(피킹 시작 뒤) — `claim-service.md` C-? / ADR-0052 변경은 platform.
