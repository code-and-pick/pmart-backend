# 회신 — MFC 반납 인증번호

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-18
- 대상: pmart-clients `apps/rider_app`(요청 `docs/handoff/rider-app/2026-11-18-backend-return-handover-code.md`), PDA 표시는 PC 3(mfc-app)
- 상태: **구현(execution 0.27.0, V20) — PR 올림, PC 1 병합·배포 전**. 사용자 2026-11-18 "여기서 구현하고 바로 PR 올려".

## 계약
- 라이더 `POST /deliveries/{id}/returns/store-deliveries` 본문에 `handoverCode`(숫자 4자리).
  - 틀림 → 422 `RETURN_HANDOVER_CODE_MISMATCH`(상태 그대로, details `attemptsLeft`)
  - 5번 틀림 → 422 `RETURN_HANDOVER_LOCKED`(맞는 번호도 거절 — 직원이 새로 내면 풀림)
  - 번호 없음 → 설정 `pmart.execution.return-handover.required` true 면 422 `RETURN_HANDOVER_CODE_REQUIRED`, **false(기본)면 지금처럼 반납** — 앱이 입력 창을 내보낸 뒤 PC 1 이 켠다.
  - 같은 Idempotency-Key 재시도는 같은 결과. 라이더 응답에는 번호가 없다.
- 직원 `GET /return-handovers?mfcId=` — 그 센터 운영자(작업자 포함)·OPERATOR_ADMIN·RIDER_OPS·CS_AGENT. 줄: deliveryId·deliveryNumber·purpose·state·riderId·code·failedAttempts·locked·issuedAt.
- 직원 `POST /return-handovers/{deliveryId}/code-reissues` — 새 번호, 틀린 횟수 0, 잠김 풀림.

## 정한 것
- 발급: 회수·반납 배송이 반납을 기다리는 동안(회수품 받음 RETURN_PICKED_UP, 또는 전달 실패 반납 배정) 처음 조회될 때 만든다. **유효: 반납이 끝날 때까지(시간 제한 없음)**, 반납되면 목록에서 빠진다.
- 잠금: 5번. 번호는 로그·사건에 남기지 않는다.
- receivedByStaffRef 자동 채움(요청 5)은 이번에 안 함 — mfc-inbound 반입과 묶는 일은 PC 3 와 정한 뒤.
