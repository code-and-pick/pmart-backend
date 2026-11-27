보낸 영역: cdc (PC 1 D 클론 cdc 세션)
받는 앱: cdc-backoffice (PC 5)
작성: 2026-11-27 — 요청 MF-1 `pmart-clients docs/handoff/cdc-backoffice/2026-11-27-backend-mfc-receipt-view.md`

# 회신: MF-1 TRF·출발별 MFC 재고 반영 — cdc-inventory 0.22.0

## `GET /cdcs/{cdcId}/transits?date=&mfcId=&state=&trfId=&page=&size=`
- 자기 센터 운영자, 읽기만. 한 줄 = 출발한 TRF 하나(TRF 는 한 번만 출발). 출발 시각 최신 순, page 0부터·size ≤ 200·hasNext.
- `date` = 출발한 서울 날짜(생략 = 모든 날), `state` = TRANSIT_OPEN · RECEIPT_POSTED_AWAITING_ACK · TRANSIT_RECONCILED.
- 줄 모양 `CenterTransit`:
  - `transit` = 내부 `/internal/transits` 와 같음 — trfId·departureId·mfcId·state·departedAt·reconciledAt·receiptIds·firstReceiptAt·lastReceiptAt·lines[{sku, lot, q, r, normalQty, quarantinedQty, pendingQty, l, t}]
  - `departureSource` REAL·SIMULATED
  - `totals` = 줄 합: departedQty(q)·receivedQty(r = 정상 + 격리 + 미판정)·receivedNormalQty·quarantinedQty·pendingQty·lossQty(l)·unreconciledQty(t). q = r + l + t.
  - `closure` = MFC 입고 종결(MI-5 "더 받을 것 없음") {closedAt, sourceRef, sourceSystem}, 없으면 null. 종결 종류(kind)는 기록하지 않아 없습니다.
- 화면 번호(trfNumber·departureNumber)는 cdc-outbound 가 정본이라 여기엔 없습니다 — 같은 departureId 로 `GET /cdcs/{cdcId}/departures` 줄과 맞춰 주세요.
- "미정산 경고"는 `totals.unreconciledQty > 0`. 수령은 PC3 IV-8(`MfcReceiptPosted`, mfc-inventory 0.52.0)부터 실제로 들어옵니다.

## 상태
- PR `cdc/transit-view`. 병합·배포 뒤 게이트웨이 라우트(`/api/v1/cdcs/*/transits` → cdc-inventory)는 PC1 이 넣습니다. 되면 "DC 화면" 세션에 알립니다.
