# 요청 — 통합 조회에 MFC 현장 칸 (backoffice-bff 0.9.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(통합 조회 — 상담원·지원센터·관리자)
상태: bff PR 대기(PC 3 #97 mfc-fulfillment 0.10.0 뒤 병합·배포, PC 1).

## 바뀐 것
`GET /api/v1/backoffice/views/unified-lookup` 이 주문(또는 배송·접수 건으로 이어진 주문)을 찾으면 `matches` 에 MFC 현장 칸이 더 붙는다(원천 `mfc-fulfillment-service`):
- `kind: "FULFILLMENT"`, `id` = fulfillmentId — summary: `kind`(ORIGINAL·RESHIPMENT), `mfcId`, `workVersion`, `acceptance`(ACCEPTED·REJECTED)·`rejectReason`·`rejectMessage`·`decidedAt`, `preparationStartedAt`, `createdAt`, `shipments[]`:
  - `shipmentId`·`state`·`readyAt`·`dispatchRequestedAt`
  - `tasks[]` — taskType(PICK·PACK·RETURN·DISPATCH)·state·workerId·startedAt·endedAt·**blockReason**·planned/completed/cancelled/remainingQty
  - `shortages[]` — 점원 결품 보고(sku·qty·reason·reportedAt·executionState). 결품 "처리"는 tasks 의 cancelledQty·BLOCKED 로 본다(원천에 따로 없음)
  - `packages[]` — state·sealedAt·fenceState·handoverAuthorizedAt·handoverStatus·handoverRejectReason·handedOverAt
- `kind: "MFC_STOP"`, `id` = stopRequestId — summary: scope·state(PENDING·RESOLVED)·lastError·requestedAt·failedAt·resolvedAt. lastError 가 있으면 처리 대기에도 있다.
- MFC 장애·403 이면 `unavailableSources` 에 `mfc-fulfillment-service`(0건으로 바꾸지 않음), 404 면 그 주문의 현장 기록 없음(칸 없음).

## 화면 제안
- 주문 결과 아래 "현장" 칸: 작업 지시(원래·재발송) → 출고별 진행(피킹 → 포장 → 인계) 한 줄 타임라인, BLOCKED 면 사유 빨강, 결품 보고 수, 인계 거절이면 사유.
- 중지 요청이 PENDING + lastError 면 "처리 대기에서 다시 시도" 링크(관리자).
- 작업자는 ID 그대로(LAN 에서는 운영자 ID `OP-…` — 사번이 아님, 이름 없음). 웹은 "작업자 ID" 로 표기(2026-11-17 웹 세션 확인).
