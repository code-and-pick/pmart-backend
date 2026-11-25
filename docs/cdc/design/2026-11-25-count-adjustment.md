# 칠곡 CDC 재계수·실사 조정 설계 — 초안

- 작성: cdc 세션, 2026-11-25. 사용자 "1부터 4까지 차례대로" 중 4번(재고 남은 기능)의 첫 항목.
- 상태: **구현 중** — §7-1 피킹 재고 옮겨 적기(#497 배포), §7-2 실사 세션(cdc-inventory 0.8.0, PR `cdc/count-sessions`), §7-3 approval AP-4(PC 1), §7-4 예약 부족 연결(cdc-inventory 0.10.0·cdc-outbound 0.17.0, PR `cdc/count-shortfall`).
- 대상: cdc-inventory(주)·cdc-outbound(피킹 재고 옮겨 적기·예약 부족 연결). 승인 확인은 approval-service(PC 1 몫 — 요청 필요).
- 근거: `docs/business/WMS-business-logic.md` CDC-F-029(위치 실사·재계수·원거래 차이 조사), CDC-F-030(조사 차이의 승인·부분집행·원장 조정).

## 1. 사용자 결정 (2026-11-25)

| # | 질문 | 결정 |
|---|---|---|
| 1 | 모양 | **MFC 실사와 같게** — 관리자 시작(칸 잠금) → 장부 숨긴 첫 계수 → 다르면 다른 작업자 재계수 → 시작·계수자가 아닌 관리자 승인 → 차이만큼 CNT 조정 → 재계수 대기 해소 |
| 2 | 승인 | **MFC 와 같은 본사 승인 기준** — 한 SKU 수량·감소 금액이 기준 이상이면 본사 승인 번호(approval-service 로 한 번 사용), 아래면 센터 관리자 승인만. 기준값은 설정 — 비면 조정 집행을 막는다(CONFIG_REQUIRED) |
| 3 | 실물 < 예약 | **조정은 하고 나중 예약부터 부족 표시** — 모자란 만큼 그 칸에 가장 늦게 잡힌 할당부터 풀고 출고 쪽에 부족으로 알린다(피킹 부족 재할당과 같은 길 — 다른 LOT 로 다시 잡거나 못 찾음) |

## 2. 흐름 (cdc-inventory)

1. **시작** `POST /cdcs/{cdcId}/count-sessions` — 운영자(자기 센터). 칸 1~200개.
   - 그 칸들의 SKU·LOT·품질별 현물을 **장부 기준**으로 고정한다(기준 시각·원장 commit 기록).
   - 칸은 **실사 잠금** — 끝날 때까지 새 할당·추가 할당 후보에서 빠진다(재계수 대기와 같은 거르기, `recount_hold` 와 별도 표 `count_lock`).
   - 같은 칸에 열린 실사가 있으면 409 COUNT_IN_PROGRESS.
2. **계수표** `GET …/count-sessions/{id}` — 칸·SKU·LOT 목록만, **장부 수량은 보이지 않는다**(승인 전까지).
3. **첫 계수** `POST …/{id}/counts` — 계수자 = 토큰 주체. 줄 = 칸·SKU·LOT·센 수량(없는 줄 = 0). 장부에 없던 LOT 은 이미 있는 LOT(`stock_lot`)만 받는다 — 처음 보는 LOT 은 400(입고 기록 없는 재고 증가 금지, CDC-F-029 "미등록 LPN 발견은 재고 증가 근거가 아님").
   - 모두 같으면 MATCHED → 잠금·재계수 대기 해소, 조정 없음.
   - 다르면 RECOUNT_REQUIRED.
4. **재계수** `POST …/{id}/recounts` — 첫 계수자와 다른 사람(409 SAME_COUNTER). 최종 = 재계수. 기준과 같으면 MATCHED, 다르면 PENDING_APPROVAL.
5. **결정** `POST …/{id}/decisions` — APPROVE·REJECT. 시작자·계수자가 아닌 운영자(422 SELF_APPROVAL).
   - APPROVE: 기준 이후 그 칸 원장이 바뀌었으면 409 BOOK_CHANGED(다시 실사). 승인 관문(§3) 통과 → 한 commit 으로 **CNT** 조정(최종 − 기준, SKU·LOT·위치) → 잠금·재계수 대기 해소 → `InventoryAdjustmentPosted`.
   - 감소로 그 칸 현물이 예약보다 적어지면 §4.
   - REJECT: 조정 없음, 잠금 해소(재계수 대기는 남김 — 다시 실사).

## 3. 승인 관문 (MFC `ApprovalRefs` 와 같은 규칙)

- 설정 `pmart.inventory.approval-ref.mode`: `VERIFY` / `RECORD_ONLY`(LAN 시험용 — 번호 필수, 확인 안 함) / 그 밖·비움 = CONFIG_REQUIRED.
- VERIFY: 기준 `hq-loss-krw`(감소 수량 × 판매 기준가 합)·`hq-sku-qty`(한 SKU 움직이는 수량) — 비면 CONFIG_REQUIRED. 이상이면 번호 없을 때 422 APPROVAL_REQUIRED + `approvalRequest`(approval-service 변경 요청 본문), 있으면 approval-service `/internal/approval-ref-uses` 로 한 번 사용·범위 비교. 단가 = pricing `/internal/prices`(없으면 미평가 → 본사).
- **approval-service 에 새 종류 필요**: `CDC_STOCK_ADJUSTMENT`(대상 cdc-inventory, targetRef `<cdcId>:COUNT_ADJUSTMENT:<sessionId>`, 승인 역할 INVENTORY_APPROVER) — PC 1 요청(`service-requests/approval-service.md`). 그 전에는 RECORD_ONLY 로만 돈다.
- 기준값(원·개)은 업무 결정 — MFC 는 Q-11 "한 건 손실 10만 원 이상 또는 한 SKU 20개 이상". CDC 값은 §6.

## 4. 실물 < 예약 (결정 3)

- 조정으로 그 칸 현물 E' 가 그 칸 예약 R 보다 작아지면, 모자란 `R − E'` 만큼 **그 칸에 가장 늦게 잡힌 할당부터** 예약을 푼다(해제 사유 `COUNT_LOSS` 새 값).
- 푼 할당마다 사건(`AllocationShortfall` — trfId·allocationId·sku·lot·locationCode·qty)을 내고, cdc-outbound 가 구독해 **피킹 부족 후속과 같은 길**(추가 할당 → 새 작업 / 못 찾음)로 처리한다. cdc-outbound 의 첫 사건 구독이다(Spring Cloud Stream 함수 바인딩·inbox — 설정 정본 PC 1).
- 이미 집은 몫이 그 칸 예약에 들어 있으면 §5.

## 5. 집었지만 아직 출발하지 않은 물건 — 피킹 재고는 출고대기 자리로 옮겨 적는다(결정 B)

- 정책 17.4 — 피킹은 CDC 총현물을 줄이지 않는다. 지금은 집은 물건이 **출발할 때까지 장부상 원위치**에 남아, 피킹 뒤·출발 전에 칸을 세면 실물이 정상이어도 모자라 보인다.
- 사용자 결정(2026-11-25 "피킹재고는 피킹재고로 따로" → "출고대기 자리로 옮겨 적기"): **피킹 실적이 들어오면 cdc-outbound 가 그 수량을 예약과 함께 칸 → 그 층 출고대기(통로 91)의 목적 MFC 자리로 옮겨 적는다**.
  - cdc-inventory `POST /internal/move-postings`(0.3.0 — 줄에 allocationId 를 주면 예약도 함께 옮김) 재사용. 총현물·예약 수는 그대로, 위치만 바뀐다. moveKind 는 새 값 `PICK_MOVE`.
  - 출고대기 자리 = `CDC01-<구역>-<층>-91-<자리>-0-1`. 구역 글자는 온도대(냉동 A·냉장 B·상온 — 5층 C·7층 G, 도면의 91 행). 자리 번호는 MFC 마다(CDC-5 기본 배정 01 BJG·02 BNG·03 MOKPO·04 SEJ·05 CHC) — cdc-outbound 지역표(`region_mfc`)에 `staging_bay` 를 더한다.
  - 묶음 피킹은 TRF(MFC) 몫별로 나눠 옮긴다 — 실물은 지역 카트·라인 칸에 있어도 장부는 목적 MFC 자리(그 MFC 로 갈 물건). 묶음 피킹 설계 §4 "MOV 하지 않는다"(백엔드 판단)는 이 결정으로 바뀐다.
  - 출발 TOUT 은 지금처럼 할당이 지금 잡은 LOT·위치에서(= 출고대기 자리). 잔여 해제도 그대로.
  - 옮겨 적기는 피킹 실적 트랜잭션 밖(재고 호출) — 불명이면 피킹 부족 후속처럼 주기 재시도(같은 키).
- 그래서 실사는 **칸에 실제 있는 물건만** 장부 기준이 되어 언제든 할 수 있다. 출고대기 자리(91)는 실사 대상에서 뺀다(출발할 물건 — 출발 검증이 대조한다).

## 6. 결정

| # | 질문 | 결정(사용자 2026-11-25) |
|---|---|---|
| A | CDC 본사 승인 기준값 | **MFC 와 같게** — 한 건 손실(감소 수량 × 판매 기준가) 10만 원 이상 또는 한 SKU 20개 이상이면 본사 승인. 설정 `hq-loss-krw: 100000`·`hq-sku-qty: 20`(pmart-config — PC 1) |
| B | 집었지만 아직 출발하지 않은 물건 | **피킹 재고는 출고대기 자리로 옮겨 적는다**(§5) |

## 7. 나눠 올릴 PR

1. cdc-outbound: 피킹 재고 옮겨 적기(§5) — 지역표 `staging_bay`, 피킹 실적 뒤 move-postings(PICK_MOVE, allocationId), 주기 재시도. cdc-inventory moveKind `PICK_MOVE` 추가.
2. cdc-inventory: 실사 세션·잠금·계수·재계수·결정·CNT 조정·승인 관문(RECORD_ONLY/VERIFY)·`InventoryAdjustmentPosted` — 출고대기(91) 칸은 실사 대상 아님.
3. approval-service `CDC_STOCK_ADJUSTMENT`(PC 1 요청) + pmart-config 기준값(PC 1).
4. cdc-inventory 예약 부족 해제(`COUNT_LOSS`)·`AllocationShortfall` + cdc-outbound 구독(첫 소비자 — 설정 정본 PC 1).
