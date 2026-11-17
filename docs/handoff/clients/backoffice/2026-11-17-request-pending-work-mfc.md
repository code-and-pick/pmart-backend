# 요청 — 처리 대기 MFC 묶음 켜짐 (backoffice-bff 0.8.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(처리 대기 — 본사 관리자)
상태: bff PR 대기(PC 3 #95 mfc-fulfillment 0.9.0 뒤 병합·배포, PC 1).

## 바뀐 것
- `GET /api/v1/backoffice/views/pending-work` 에 MFC 항목이 실제로 나온다(지금까지 설정으로 꺼져 있었음):
  - `MFC_STOP_FAILED` — 실패로 멈춘 집품 뒤 중지(refId = stopRequestId). 고객 취소·환불이 이것 때문에 멈춰 있을 수 있다.
  - `MFC_HANDOVER_REJECTED` — 재고가 거절한 인계(refId = handoverId).
  - 둘 다 `allowedActions: ["RETRY"]`, `reasonCode`·`reasonMessage`(MFC lastError·rejectReason "코드: 상세"), `since`.
- 새 칸 `lastRetry: { operatorId, reason, retriedAt }` — 최근 운영자 재시도(없으면 null).
- "다시 시도" `POST /commands/mfc-fulfillment-service/stop-retry`·`handover-retry` `{ targetRef, reason }` — **사유 필수·500자**. 운영자 ID 와 사유가 MFC 에 재시도 기록으로 남는다(전에는 저장 안 됨).
- MFC 목록 중 하나라도 실패하면 MFC 항목 전체를 빼고 `unavailableSources` 에 `mfc-fulfillment-service`(반만 보이지 않게).

## 화면 제안
- 항목에 "최근 재시도: 누가·언제·사유" 한 줄(lastRetry).
- 인계 재시도 버튼 옆 안내(BO-12 결정 그대로): "실물 확인 → 재고 정정 → 다시 시도". 사유 칸 500자.
