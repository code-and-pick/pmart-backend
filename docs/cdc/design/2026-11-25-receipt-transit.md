# 칠곡 CDC MFC 수령·운송 중 재고 대사 설계 — 초안

- 작성: cdc 세션, 2026-11-25. 사용자 "그렇게 해"(다음 할 일 B — 출고 다음 단계의 빠진 고리).
- 상태: **CDC 쪽 구현**(cdc-inventory 0.11.0, PR `cdc/receipt-transit`) — mfc-inventory 발행(PC 3, IV-8) 대기. 정정(correctionId)은 다음.
- 대상: cdc-inventory(주). MFC 쪽 수령 사건 발행은 mfc-inventory(PC 3 — 요청 IV-8).
- 근거: `docs/business/WMS-business-logic.md` CDC-F-024(MFC 실제 입고 확정 결과 인수·Transit 대사·복구), CDC-F-025(원출발·수령·손실·미출발 수량 종결 대사).

## 1. 지금

- 출발 검증 → cdc-inventory TOUT: CDC 현물 −q·예약 −q·**Transit +q**(`transit`·`transit_line` — 출발 줄마다 q, 정상·격리·손실 칸은 0). `TransferDeparted` 발행.
- mfc-inbound 가 `TransferDeparted`·`TransferShipmentManifestIssued` 를 받아 입고 예정을 만든다(2026-11-20). MFC 현장 수령·검수 → mfc-inventory TIN 으로 MFC 현물이 는다.
- **빠진 것**: MFC TIN 결과가 CDC 로 오지 않는다. `MfcReceiptPosted` 는 계약만 있고 발행자가 없다("TRF 경로 구현 때 발행"). cdc-inventory `/internal/receipt-acks` 는 501. 그래서 CDC Transit 이 출발량 그대로 남는다.

## 2. 규칙(업무 문서 — 바꾸지 않음)

- MFC 는 자기 TIN 을 확정하고, CDC 는 그 **확정 결과**로 자기 원출발 Transit 만 대사한다. 현장 수령 주장만으로 Transit 을 줄이지 않는다. CDC 가 MFC TIN 을 대신 만들지 않는다.
- 수령 r = 정상 n + 격리 h (> 0, 정수). 원출발 수령 누계 + 손실 + 미정산 ≤ 출발량.
- 같은 원 receiptId·줄의 반복은 기존 대사 결과를 돌려주고 추가 차감 0. 다른 본문은 덮어쓰지 않고 충돌로 보류.
- **출발량 q = 확인 수령 누계 r + 승인 손실 l + 미정산 t**. t > 0 이 남아도 자동 손실 전기 금지 — 조사·승인 손실(CDC-F-027) 몫.
- 여러 SKU·줄은 줄별로 대사한다(합한 r 로 차감하지 않음). 부분 성공이면 성공 줄만 확정, 나머지는 원결과 조회로 복구.
- LOT 이 출발 LOT 과 다르면 원출발을 덮지 않고 그 줄을 보류(CONFLICT)한다.
- 수령 수량 정정은 MFC 가 승인 정정한 `correctionId` 의 δr 만 한 번 반영(Transit −δr). 품질 해제만 바뀐 것은 δr = 0.
- 출발 기록보다 수령 통지가 먼저 오면 선행 출발을 기다린다(새 출발을 만들지 않음).

## 3. 흐름

1. mfc-inventory: TRF 입고(입고 예정의 수령·검수 → TIN) 확정 뒤 `MfcReceiptPosted` 발행(토픽 `mfc.inventory.v1`) — receiptId·postingId·commitId(TIN)·trfId·departureId·mfcId·lines[sku·lot·normalQty·quarantinedQty·pendingQty]·receivedQty·postedAt. **PC 3 요청 IV-8**.
2. cdc-inventory: `MfcReceiptPosted` 구독(첫 MFC 사건 구독 — 설정 정본 PC 1) → 아래 대사. 재처리·복구용으로 같은 규칙의 `POST /internal/receipt-acks` 도 연다(지금 계약 그대로 — 501 해제).
3. 대사(한 트랜잭션, receiptId 한 번):
   - transit 없음(TOUT 전) → 대기 표(`receipt_pending`)에 두고, TOUT 이 생기면 그 TRF 의 대기를 처리(순서 역전).
   - 줄마다 SKU·LOT 로 출발 줄(`transit_line`)을 찾는다. 같은 SKU·LOT 출발 줄이 여럿이면 앞 줄부터 남은 수량만큼.
   - 남은 수량 초과·없는 SKU/LOT → 그 줄 CONFLICT(보류·조사), 차감 0.
   - 맞는 줄: normal_qty += n, quarantined_qty += h, Transit 원장 −r(원결과 `TIN_ACK`).
   - 전 줄 정산 t = 0 → `TRANSIT_RECONCILED`, 아니면 `RECEIPT_POSTED_AWAITING_ACK`(남은 t 는 조사 대상).
   - `TransitReconciled`(trfId·departureId·q·r·l·t·state) 발행.
4. 정정 `ReceiptCorrectionPosted`(correctionId·δr) — 같은 규칙으로 한 번. 0 ≤ r, 0 ≤ q − r − l 검사.

## 4. 데이터(cdc-inventory, Flyway 다음 번호)

- `transit_receipt`(receiptId·trfId·departureId·mfcId·mfcCommitId·body_hash·state APPLIED/CONFLICT/PENDING_DEPARTURE·commit_id·received_at) — 원 receiptId 한 번.
- `transit_receipt_line`(receiptId·departure_line_id·sku·lot·normal·quarantined·pending·result MATCHED/CONFLICT·reason).
- 미판정(pending)은 r 에 넣는다(§6-A) — `transit_line` 에 칸을 더하지 않고 정상·격리 칸 옆에 `pending_qty` 를 더한다(MFC 판정 뒤에도 CDC 수령 누계는 그대로 — CDC-F-024 "품질만 바뀐 것은 δr = 0").

## 5. 하지 않는 것

- MFC 현물·품질·판매 준비를 바꾸지 않는다(MFC 몫).
- 미정산 t 를 손실로 지우지 않는다 — 운송 손실 승인(CDC-F-027, `/internal/loss-postings`)은 다음 단계.
- 수령 통지가 늦는다고 CDC 가 수령을 만들지 않는다.

## 6. 결정

| # | 질문 | 결정(사용자 2026-11-25) |
|---|---|---|
| A | MFC 의 "미판정(pending)" 수량 | **받은 것으로 보고 바로 뺀다** — r = 정상 + 격리 + 미판정(사건 계약 그대로). 판정은 MFC 몫 |
| B | 전달 경로 | **사건 + 복구용 API** — mfc-inventory 가 `MfcReceiptPosted` 발행, cdc-inventory 구독. 유실 복구는 `/internal/receipt-acks`(같은 receiptId 한 번) |
