# 알림 — bff 0.16.0 승인 심사 가능 여부(AP-3), 대시보드 D2·D3 은 원천 직접

보낸 영역: backoffice
- 작성: pmart-backend PC 2, 2026-11-18
- 받은 요청: pmart-clients `docs/handoff/backoffice/2026-11-18-backend-approval-can-decide.md`(R1·R2), `2026-11-18-backend-dashboard-metrics.md`(D2·D3·D4)
- 상태: 백엔드 PR(PC 1 검토·배포 전) — 병합 뒤 `gen:api`

## bff 0.16.0
- `GET /views/approval-inbox`: 쿼리 `decidableByMe=true`(내가 지금 결정할 수 있는 승인만 — approval 판정), 항목 `viewerCanDecide`(boolean|null)·`viewerDecideBlockedReason`(string|null — NOT_PENDING·SELF_APPROVAL·CONFIG_REQUIRED·NO_ROLE·GRANT_TO_SELF, 새 값이 생길 수 있음).
- `GET /views/work-counts`: APPROVALS 에 key **DECIDABLE_BY_ME**(segment = 승인 종류) — "내 할 일 — 심사 대기 N건"은 이 줄로. REVIEW_PENDING·EVIDENCE_REQUIRED 줄(누가 심사하든 전부)은 그대로.
- 승인 상세·목록을 approval 에서 직접 부르는 화면은 approval 0.4.0(이미 LAN 배포)의 같은 값을 바로 써도 된다.

## 대시보드 D2·D3 — bff 를 거치지 않는다
- 원천이 이미 게이트웨이에 열려 있다(OPERATOR_ADMIN 만, 기간 93일까지, LAN 배포됨).
  - D2: order `GET /orders/metrics/summary?from&to&mfcId`(주문 확정일 한국 날짜 기준 상태별 건수·결제 승인 금액 합) + claim `GET /claims/metrics/refunds?from&to`(환불 완료일 기준 환불 합, 사유 묶음별) — **나란히, 빼지 않는다**(사용자 결정).
  - D3: order `GET /orders/metrics/top-products?from&to&mfcId&limit` — 화면에 "주문 확정 기준".
- D5(품절 SKU 수)는 PC 3 IV-5 대기.
