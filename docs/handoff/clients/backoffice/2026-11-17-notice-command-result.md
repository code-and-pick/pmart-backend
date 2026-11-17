# [pmart-backend → pmart-clients] 알림: 처리 대기 명령 결과 다시 확인 (2026-11-17)

- 보낸 영역: backoffice(PC 2)
- 받는 앱: backoffice(처리 대기 화면)
- 근거: 사용자 2026-11-17 "할 수 있는건 다 진행하자", 계약 `backoffice-bff.yaml` 0.12.0. **백엔드 PR 단계 — LAN 배포 전**(PC 1 병합·배포 뒤 확인).

## 무엇이 생겼나
처리 대기에서 버튼(결제 결과 다시 확인 · 해결 표시 · MFC 중지 다시 시도)을 눌렀는데 응답이 `UNKNOWN_RESULT`(결과 모름)나 `ACCEPTED`(아직 진행 중)이면, 원천을 다시 읽어 결과를 확인할 수 있다.

| 호출 | 설명 |
|---|---|
| `GET /api/v1/backoffice/commands/{targetService}/{command}/{requestId}?targetRef=` | `requestId` = 명령 응답의 requestId(보낸 Idempotency-Key), `targetRef` = 명령 때 보낸 것(사가 ID·중지 요청 ID, 필수) |

- 응답: `status`(APPLIED·REJECTED·ACCEPTED·UNKNOWN_RESULT), `nextAction`(QUERY_RESULT = 잠시 뒤 다시 확인, FIX_AND_RETRY = 원인을 고치고 다시 시도), `detail`(사가: action·state·resultSummary·requestedAt·finishedAt / 중지: state·lastError), `source`(원천·기준시각 — 원천이 응답하지 않으면 confidence UNKNOWN).
- 되는 명령: `order-orchestration-service/saga-requery`·`saga-resolve`, `mfc-fulfillment-service/stop-retry`.
- **인계 다시 보내기(`handover-retry`)는 422** — 원천에 단건 조회가 없다. 같은 Idempotency-Key 로 다시 보내면 저장된 결과를 받는다(또는 목록 새로고침).
- 404 = 원천에 그 명령 기록이 없음(닿지 않음) → 같은 Idempotency-Key 로 다시 보내면 된다. 403 = 관리자(OPERATOR_ADMIN) 아님. 503 = 원천 연결 안 됨.
- 화면 권장: 결과 모름이면 "결과 확인" 버튼 → 이 호출. 자동 반복 조회는 짧게 몇 번만(원천 부하).
