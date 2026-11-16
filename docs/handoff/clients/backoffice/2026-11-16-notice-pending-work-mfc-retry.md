# 알림 — 처리 대기에 MFC 중지 실패·인계 거절, "다시 시도" 명령 (backoffice-bff 0.4.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(처리 대기 화면, OPERATOR_ADMIN)
상태: bff PR 대기(병합·배포는 PC 1). PR #27(0.3.1) 뒤에 병합.

## 1. 바뀐 것
- `GET /api/v1/backoffice/views/pending-work` 의 `items[].kind` 에 둘이 늘어난다.
  - `MFC_STOP_FAILED` — 주문 취소(피킹 뒤) 중 MFC 중지가 실패로 멈춤. 이대로면 고객 취소·환불이 진행되지 않는다. `refId` = 중지 요청 ID.
  - `MFC_HANDOVER_REJECTED` — 라이더 인계를 재고가 거절(D-8). 재인계·취소가 막힌다. `refId` = 인계 ID.
  - 둘 다 `allowedActions: ["RETRY"]`, `reasonCode`(사유 코드)·`reasonMessage`(MFC 사유 원문 — 개인정보 없음), `source.system = mfc-fulfillment-service`.
- **목록은 아직 비어 있다** — MFC 쪽 목록 API(PC 3 요청 F-4)가 생긴 뒤 설정으로 켠다. 그때까지 `sources` 에 `mfc-fulfillment-service` 가 없다(장애가 아님).
- 명령 둘(지금 바로 쓸 수 있음): `POST /api/v1/backoffice/commands/mfc-fulfillment-service/stop-retry`·`/handover-retry`, 본문 `{targetRef: refId, reason, payload: {}}`, `Idempotency-Key` 필수. **reason 필수**(400). OPERATOR_ADMIN 만(403).
  - 응답 `status`: `APPLIED`(풀림 — 목록에서 빠짐) / `REJECTED`(다시 실패, `nextAction: FIX_AND_RETRY` — 원인 고친 뒤 다시) / `ACCEPTED`·`UNKNOWN_RESULT`(진행 중 — 목록을 다시 읽어 확인). 404 = 그런 ID 없음, 그 밖 대상 오류 코드 그대로.

## 2. 화면에 바라는 것
- 두 종류에 "다시 시도" 버튼(메모 입력 필수). 인계 거절은 버튼 옆에 **"실물 확인 → 재고 정정 → 다시 시도"** 안내(BO-12 결정).
- "처리 완료로 표시"는 MFC 두 종류에는 없다(사가만).
- 메모는 지금 MFC 에 저장되지 않는다(F-4 에 요청) — 화면에서 "기록됨"이라고 쓰지 않는다.

## 3. 계약
`contracts/openapi/backoffice-bff.yaml` 0.4.0 — PendingWorkItem.kind enum, `/commands/{targetService}/{command}` 설명.
