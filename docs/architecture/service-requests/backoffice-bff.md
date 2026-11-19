# backoffice-bff 에 대한 요청 (주문 이행 쪽에서)

## BO-12. 운영자 수동 개입 화면 (2026-11-09 기록)

- **요청한 곳**: order-orchestration, mfc-fulfillment.
- **대상**
  1. ESCALATED 사가 처리.
  2. 재고가 거절한 인계 재시도(D-8) — API `POST /internal/package-handovers/{id}/retries` 있음.
  3. 중지 실패 재시도 — `POST /internal/stop-requests/{id}/retries` 있음.
- **지금**: 화면 없음 — API·로그로만. backoffice-bff CommandsController 501.
- **사용자 결정(2026-11-16 "너 추천대로")**:
  1. **누가**: `OPERATOR_ADMIN` 만 처리한다(상담원·센터 관리자는 목록을 보지 않음).
  2. **버튼 둘로 시작**: "재시도"(사가 조치 재시도·인계 재시도·중지 재시도)와 "처리 완료로 표시"(사람이 밖에서 해결 — **사유 필수**). 환불은 이 화면에서 하지 않고 기존 환불 절차(claim·payment)로.
  3. **근거**: 재시도·처리 완료 모두 메모 필수. 인계 재시도는 "실물 확인 → 재고 정정 → 재시도" 절차(D-8)를 화면에 안내.
  4. **알림**: 지금은 목록만(멈춘 건 수 표시). case 사건함(R-3)이 생기면 멈춘 건을 사건으로도 연다.
  - **구현(2026-11-16, PC 1 — 사용자 "PC 2 에 넘기기 전에", "MFC 쪽 요청 없이 내 쪽만")**:
    - order-orchestration 0.2.0: `GET /internal/stuck-sagas`(네 사가 표의 ESCALATED·RECONCILIATION_REQUIRED·UNKNOWN_TRACKING, 처리 완료 표시 전, allowedActions), `POST /internal/sagas/{id}/manual-actions` 의 REQUERY_RESULT(결제 확인 대기만 — 원결과 다시 확인)·**MARK_RESOLVED(새 — 밖에서 해결함 표시, 사가 상태·자원 그대로)** — 둘 다 승인 없이, operatorId·reason 필수. V7 `saga_manual_action`·`saga_manual_resolution`.
    - 재시도 판단: 업무 거절로 멈춘 사가(ESCALATED)는 같은 키로 다시 보내도 같은 거절이라 "다시 확인"을 주지 않고 "처리 완료"만.
    - backoffice-bff 0.2.0: `GET /views/pending-work`(멈춘 사가 + order 이행 확인 필요, OPERATOR_ADMIN, 원천 장애면 unavailableSources), `POST /commands/order-orchestration-service/saga-requery|saga-resolve`(targetRef = sagaId, reason 필수).
    - **없는 것**: MFC 인계 거절(D-8)·중지 실패 목록 — mfc-fulfillment 에 목록 API 가 없어 화면에 안 나온다(재시도 API 는 있음). 사용자 2026-11-16 처음엔 "지금은 요청하지 않음" → 같은 날 확인(중지 실패면 취소 사가가 WAITING_RESULT 로 조용히 멈춰 처리 대기에 안 나옴) 뒤 **"A안" — PC 3 에 목록·직원용 재시도 요청 [F-4](mfc-fulfillment-service.md)**. bff 쪽은 0.4.0 으로 준비(목록은 설정으로 꺼 둠, 재시도 명령은 지금 내부 경로로 연결).
- 화면은 pmart-clients 몫 — 정해지면 `docs/handoff/clients/` 로 알린다.

## BF-1. 화면 응답의 운영자 ID 에 "이름 · 사번" 싣기 (2026-11-19, 보낸 영역 platform — PC 1, 원 요청 backoffice operator-display-names R2)
- **왜**: 백오피스 승인함·처리 대기·접수 건함의 요청자·결정자·처리자 칸에 `OP-…` 가 그대로 보인다. PC 1 이 hr-service 0.8.0 에 표시 조회를 만들었다(R1) — 화면이 따로 불러도 되지만, bff 화면 응답에 같이 실으면 화면 호출이 줄어든다(요청서 R2, 선택).
- **원천**: hr-service `GET /api/v1/hr/employee-labels?operatorIds=…`(직원 토큰 — bff 는 받은 토큰 그대로 전파, 1~50개, 퇴사자 포함, 모르는 ID 는 빠짐 → 화면 "확인 불가"). 줄 `{operatorId, name, employeeNo}`. 이름은 로그 금지.
- **요청**: `views/approvals`(요청자·결정자)·`views/pending-work`(최근 재시도한 사람)·접수 건함 화면(맡은 사람)의 운영자 ID 옆에 `…Label: {name, employeeNo} | null`. hr 를 못 읽으면 그 칸만 null(화면 전체를 막지 않음 — unavailableSources 에 hr).
- 급함: 낮음(화면이 R1 을 직접 불러도 됨). 모양은 PC 2 가 정해 클라이언트에 알림.
- **상태**: **구현(2026-11-19, bff 0.17.0 — PR #212, 배포됨)**: 모양 `OperatorLabel{operatorId, name, employeeNo}` — 승인함 `items[].requestedByLabel`, 처리 대기 `items[].lastRetry.operator`, 접수 건함 `items[].assignee`(그 뒤 0.18.0 라이더 컴플레인 `recordedByLabel`). 화면 응답 하나에 hr 한 번(50개씩), 실패면 그 칸만 null + unavailableSources hr-service(403·404 는 장애 아님). 승인 결정자(상세의 결정 이력)는 bff 화면에 없어 웹이 hr 를 직접(R1).
