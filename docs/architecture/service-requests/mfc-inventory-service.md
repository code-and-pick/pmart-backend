# mfc-inventory-service 에 대한 요청

받는 영역: mfc(PC 3). 요청한 쪽이 지금 어떻게 버티는지와 생기면 바꿀 것을 같이 적는다.

## IV-1. 가용 수량에서 출하 부적격 LOT 빼기 — 높음 (2026-11-16, 보낸 영역 platform — 고객 주문 막힘)
- **본 것(LAN 2026-11-16)**: 고객이 6개 상품을 주문 → 주문서까지 들어감 → 결제 단계 재고 예약(`RESERVE_INVENTORY`)이 `INSUFFICIENT_STOCK`("2개 줄에 소비기한 기준을 통과한 재고가 부족합니다")로 거절. 원인 두 상품: `E-00000496` 재고 5개가 모두 소비기한 11-17(최소 잔여일 미달), `E-00000501` 남은 1개가 소비기한 11-13(**이미 지남** — 그래도 `sellable 1` 로 보임). 센터 전체 `GET /mfcs/MFC-SEED-01/unshippable-lots` = 250 LOT·135 SKU·1,751개, 그중 이미 지난 것 65 LOT·48 SKU.
- **문제**: `GET /internal/availability`(장바구니·주문이 쓰는 가용 A = E − R)에 **소비기한 기준을 통과하지 못한 LOT 이 들어 있다**(계약 `Availability` 설명에도 "예약이 NO_ELIGIBLE_LOT 으로 거절될 수 있다"). 그래서 장바구니는 "5개 있음"으로 보이고 주문서까지 가서 결제 단계에서야 막힌다. 사용자 결정(2026-11-16): **재고가 없으면 주문서 화면으로 가면 안 된다.**
- **할 것**
  1. 가용 조회에 **출하 가능 수량**을 더한다 — 예약과 같은 적격 판정(상품 소비기한 보장, 없으면 보관 유형 최소 잔여일, 기준일 = 오늘 또는 요청한 배송 약속일)을 통과한 LOT 의 E − R. 새 칸(예: `shippableAvailable`, 필드 추가 — 하위 호환)으로 두거나 `available` 을 그 값으로 바꾼다(바꾸면 계약 버전과 cart 영향 표시). 배송 약속일 질의(`deliveryDate=`)가 있으면 예약 배송도 맞게 판정된다.
  2. **소비기한이 지난 LOT 이 `sellable` 로 남아 있는 것** 확인 — 지난 LOT 은 출하 부적격으로 빠져야 한다(지금은 E 에 그대로 셈).
- **요청한 쪽에서 바꿀 것(PC 1)** — order 쪽은 2026-11-16 구현(계약 0.8.0): order-service 주문 초안 생성 때 이 가용을 확인해 부족하면 초안을 만들지 않는다(422, 부족 상품·수량) — 1 이 생기기 전에는 `available` 로 확인한다(그래서 소비기한 미달은 아직 결제 단계에서 걸린다). cart 표시도 새 값으로.
- **사용자 결정**: 필요 없음(재고 없으면 주문서 진입 금지 — 사용자 2026-11-16, 적격 기준은 이미 있는 예약 규칙).
- **구현(PC 3, 2026-11-17 — 브랜치 `mfc/shippable-availability`, mfc-inventory 0.24.0, 계약 필드 추가만)**:
  1. `GET /internal/availability` 응답 줄에 **`shippableAvailable`** — 예약과 **같은 코드**(`LotAllocator.eligible`·같은 소비기한 규칙)로 기준일에 적격인 LOT 의 E − R 합(≤ `available`). 기준일 = 새 query `deliveryDate`(배송 약속일, Asia/Seoul) 없으면 오늘. 최소 잔여일 미설정으로 판정할 수 없는 SKU 는 **null**(예약은 CONFIG_REQUIRED — null 을 0·available 로 바꾸지 말 것). `available` 은 그대로(하위 호환).
  2. 지난 LOT 이 `sellable` 에 남는 것: **맞다 — E 는 실물·품질 상태 기준이라 기한이 지나도 자동으로 빠지지 않는다**(격리·폐기는 사람이 — 품질 판정, `unshippable-lots` 알림이 그 목록). 대신 `shippableAvailable` 에서는 빠진다(기한 < 기준일 + 최소 잔여일). 자동 격리가 필요하면 따로 결정.
  - **PC 1 할 것**: order 주문 초안·cart 표시를 `available` → `shippableAvailable` 로(예약 주문은 `deliveryDate` 함께). null 이면 판정 불가로 다룬다.
    **→ 반영(2026-11-18 PC 1, PR 세션 요청)**: order 0.11.3(`Upstreams.availability` 가 `shippableAvailable`, 예약 주문은 `deliveryDate` = 도착 예정일 — orchestration promisedDate 와 같음), cart 0.1.1(`CatalogClient.stock` — 오늘 기준, 표시만 — 담기는 원래 가용으로 막지 않음). null 은 불명(available 로 바꾸지 않음).
  - 시험: inventory check 단위 61·통합 31(`StockReservationFlowIT` — 적격 3 = 예약 eligibleQty 3, 약속일 +9일이면 0).

## IV-2. PDA "재고 확인" — 상품명 (2026-11-17, mfc-app 요청 전달 — platform PC 1)
- 원 요청: pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-pda-stock-check.md`(PC 3 mfc-app, 사용자 결정 "재고 확인" 메뉴).
- Q1 답(PC 1 조사 2026-11-17, `docs/architecture/auth/permissions.md` §8): `GET /mfcs/{mfcId}/stock` 은 **이미 그 센터 직원이면 역할 없이** 된다(`CorrectionService` — OPERATOR + centers). MFC_WORKER 자기 센터 토큰 OK, 바꿀 것 없음. 계약 설명에 "그 센터 운영자(역할 무관)"를 적어 두면 좋다.
- **요청 Q4**: 응답 줄에 `productName`(R19-1 이동·실사처럼 SKU 사본의 이름) — PC 3.
- Q3(칸으로 재고 찾기)는 기존 MB-1 과 같다.
- **구현**(PC 3, 2026-11-17 — 브랜치 `mfc/worker-history`, mfc-inventory 0.23.0): Q4 `CenterStock.productName·barcodes`(SKU 사본), Q1 계약 설명 "그 센터 운영자(역할 무관)". 회신 `docs/handoff/clients/mfc-app/2026-11-17-reply-stock-check-and-work-history-impl.md`. 같은 PR 에 F-7 실사 몫(`/internal/work-activities` COUNT·RECOUNT, V22 계수 시각).

## IV-3. 피킹 도착지 = 장바구니, 포장 = 장바구니 → 패킹대 (2026-11-17, mfc-app 요청 전달 — platform PC 1)
- 내용·판정은 `mfc-fulfillment-service.md` **F-6**(P1·P2) — inventory `TaskExecutionService` PICK 의 `requirePacking` → `requireCart`, PACK 은 장바구니 → 패킹대. 원 요청 pmart-clients `docs/handoff/mfc-app/2026-11-17-backend-pick-to-cart.md`.
- **구현**(PC 3, 2026-11-17): PR #91 `d5386b2f`, **mfc-inventory 0.19.0** — PICK 도착지 `Locations.requirePickDestination`(CART 또는 PACKING), PACK 은 장바구니 → 패킹대(최종 위치 PACKING). LAN 배포 11-17. 자세한 회신은 F-6.

## AP-1 참조. 승인 번호 실제 확인 — 본사 승인 기준·한 번만 쓰기 (2026-11-17, platform PC 1)
- 요청 본문은 `approval-service.md` **AP-1 "PC 3 이 할 것" 1~6**(이 서비스 몫: 실사 조정·재고 정정 — `CountService` 승인, `CorrectionService`). 기준(사용자 2026-11-17): 한 건 손실 10만 원 이상 또는 한 SKU 20개 이상이면 본사 승인, 그 아래는 센터 관리자. approval-service 쪽(승인 종류·`/internal/approval-ref-checks`·`/internal/approval-ref-uses`)은 구현됨(b969005d).
- **구현**(PC 3, 2026-11-17 — 실사 조정·정정 0.22.0, 브랜치 `mfc/approval-ref-check`): 회신은 `approval-service.md` AP-1 "회신(PC 3)". LAN 은 설정(기준 값·주소·VERIFY) 뒤에 켜진다 — PC 1.

## IV-4. LOT 바코드 — GS1-128 짧은 LOT 번호·바코드로 LOT 찾기·피킹 LOT 확인 (2026-11-17, platform PC 1 — 사용자 결정)
- **사용자 결정(2026-11-17)**: 상품 바코드(EAN-13 `299…`, 상품마다 하나)로는 LOT 을 구분하지 못한다 → **LOT 단위 바코드**, 형식 **GS1-128 표준**(`(01)` 상품번호 GTIN-14 + `(10)` LOT 번호 + `(17)` 소비기한 YYMMDD, 기한 없는 LOT 은 `(17)` 생략), 쓰는 곳 **피킹에서 LOT 확인까지**(입고 검수·실사에도). 진행 방식 "(가) PC 3 에 요청".
- **할 것(mfc 영역 — 제안, PC 3 가 정한다)**:
  1. **짧은 LOT 번호**: LOT 마다 GS1 `(10)` 에 넣을 20자 이하 번호(예 `L2611170001` — 생성일 + 일련, 센터 안 유일). 새 LOT 부터 붙이고 기존 LOT 은 한 번 채운다(마이그레이션). 재고 조회·작업 상세·스냅샷 응답에 `lotNo` 를 싣는다.
  2. **바코드로 LOT 찾기**: GS1-128 원문(또는 GTIN + LOT 번호)을 받아 SKU·LOT·기한을 돌려주는 조회(예 `GET /mfcs/{mfcId}/lot-resolutions?gs1=` — 직원·그 센터). GTIN 은 상품 바코드 `299…`(EAN-13 → 앞에 0 붙인 GTIN-14)로 SKU 를 찾는다(SKU 사본의 바코드).
  3. **피킹 LOT 확인**(mfc-fulfillment·mfc_app): 작업 지시의 LOT 과 찍은 LOT 바코드가 다르면 PDA 가 막고, 실적 `lines[].lotId` 는 찍은 LOT 으로(지금은 지시 사본). 서버는 지금처럼 예약 LOT 이 아니면 거절(IV-07). 상품 바코드(EAN-13)만 찍는 경우를 계속 받을지 — PDA 화면 결정(제안: LOT 라벨이 붙은 상품은 LOT 바코드 필수).
  4. mfc_app 스캔 해석: GS1-128 은 FNC1 으로 시작하는 Code128 — 스캐너가 주는 원문(`]C1` 접두·그룹 구분자 GS)을 AI 로 나눠 읽는다.
- **PC 1 이 할 것**: LAN 시험 데이터 — LOT 이 1개인 상품 일부를 두 LOT(기한 다름)으로 나눔 — **적용됨**(2026-11-17, `deploy/lan/seed/mfc_split_lots.py`: 195개, LAN LOT 1,064 → 1,259, LOT 1개 상품 1개만 남음), LOT 번호가 생기면 GS1-128 LOT 라벨 인쇄 파일(`deploy/lan/seed/labels/`).
- **상태**: **구현**(PC 3, 2026-11-17 — 브랜치 `mfc/lot-gs1`, **mfc-inventory 0.21.0(V21)·mfc-fulfillment 0.11.0**). 처음엔 요청함(2026-11-17).
- **회신(PC 3, 2026-11-17)** — 제안 1~3 수용, 4 는 앱:
  1. **LOT 번호 `lotNo`**: `L` + 만든 날(Asia/Seoul) YYMMDD + 일련 6자리 이상(시퀀스 — 날마다 처음부터가 아님), 예 `L261117000123`(13자). **전체 유일**(센터 안이 아니라 — LOT 이 센터와 무관하게 하나라서), 바뀌지 않는다. 새 LOT 은 DB 기본값으로 붙는다(코드 밖에서 넣는 LAN 시드 행도). 기존 LOT 은 V21 이 만든 순서대로 한 번 채운다. 싣는 곳: `LotLabel`·`CenterStock.lots`·`PickPlan.parts`·`CountSession.sheet`, fulfillment 작업 상세 `picks[].lotNo`(PICK 만).
  2. **GS1-128**: 라벨 값 `LotLabel.gs1` = 괄호 꼴 `(01)`GTIN-14 `(17)`YYMMDD `(10)`lotNo — 고정 길이(01·17)를 앞, 가변(10)을 끝에 두어 FNC1 구분자가 필요 없다(요청의 01→10→17 순서 대신 — GS1 권장). 기한 없는 LOT 은 `(17)` 생략. GTIN = 상품 바코드 중 숫자 8·12·13·14자리 첫 값을 앞에 0 붙여 14자리(검사 숫자는 보지 않음 — 시험 바코드). 숫자 바코드가 없으면 `gs1` null.
  3. **LOT 찾기** `GET /mfcs/{mfcId}/lot-resolutions?code=`(그 센터 운영자): 스캐너 원문(`]C1` 접두·GS 구분자)·괄호 꼴·예전 QR `LOT:{lotId}` 모두. `(10)` 으로 찾고, `(01)` 이 그 LOT SKU 바코드와 다르거나 `(17)` 이 기한과 다르면 422 `GS1_MISMATCH`, 없는 번호 404 `LOT_NOT_FOUND`, 틀린 값 400. 응답 = `LotLabel`(그 센터 보유 포함). AI 는 01·10·11·15·17·21 만.
  4. **피킹 LOT 확인**: PDA 는 찍은 GS1 의 `(10)` 을 `picks[].lotNo` 와 바로 대조(서버 호출 없이), 실적 `lines[].lotId` 는 그 pick 의 lotId. 서버는 예약 LOT 이 아니면 지금처럼 거절(LOT_MISMATCH). 입고 검수·이동·실사는 찍은 값으로 `lot-resolutions` → lotId 대조(실사표는 `sheet[].lotNo` 로 바로도). 상품 바코드만 찍는 경우를 받을지는 PDA 화면 결정(제안대로 LOT 라벨 붙은 상품은 LOT 바코드 필수 — 기존 재고 라벨을 다 붙인 뒤).
  - 예전 QR `LOT:{lotId}`(0.20.0 `labelValue`)는 그대로 주고 읽는다 — 이미 붙인 라벨.
  - **PC 1**: 배포 뒤 LAN LOT 1,259개에 lotNo 가 채워진다 → LOT 라벨 인쇄 파일(`deploy/lan/seed/labels/`)은 `GET /mfcs/{mfcId}/lot-labels?sku=|locationCode=` 의 `gs1` 이나 DB `stock_lot.lot_no` 로. 배포 순서 mfc-inventory → mfc-fulfillment(새 fulfillment 는 lotNo 가 없으면 null 로 둔다 — 순서가 바뀌어도 깨지지 않음). 게이트웨이 허용 목록 GET 한 줄. **→ 만듦(2026-11-17 PC 1): `deploy/lan/seed/labels/lots/`(1,259개 LOT, `lot_labels.py` — 다시 읽기 전부·서버 `lot-resolutions` 표본 50건 일치).**
  - 시험(PC 3): inventory 단위 50(GS1 해석 6)·통합 31, fulfillment 단위 19·통합 27, 게이트웨이 허용 목록.

## IV-5. 센터별 품절 SKU 수 — 백오피스 대시보드 D5 (2026-11-18 기록, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: 백오피스 대시보드 D5("센터별 재고 부족"). 안전재고(Q-13)가 정해지지 않아 원문이 `A=E−R−SS` 를 막으므로(MFC 원문 "Q-13 결정 없이 적용하지 않는다") — **사용자 결정(2026-11-18)**: 정책 없이 셀 수 있는 사실 **"품절 SKU 수"** 로 바꾼다.
- **필요한 것**: 본사 직원 조회 — 센터별 `stock_balance` 에서 **가용(A = E − R) ≤ 0 인 SKU 수**(그 센터가 취급하는 SKU 기준 — 취급 범위는 inventory 가 정함), 선택 mfcId. 예 `GET /mfcs/stock-summary`(mfcId·sku 수·품절 수·기준 시각). "재고 부족"이 아니라 "품절"로 부른다.
- **지금(임시)**: 없음 — 화면은 칸을 만들지 않는다.
- **회신(PC 3, 2026-11-19 — inventory 0.28.0, 브랜치 `mfc/dashboard-metrics`)**: `GET /api/v1/stock-summaries?mfcId=` → `{items: [{mfcId, handledSkuCount, soldOutSkuCount}], asOf}`(센터 순).
  - 취급 범위 = 그 센터 **취급 ON 상품**(`center_sku_handling` ON — 레코드 없음·OFF 는 셈하지 않음). 품절 = 그중 가용 A = E − R ≤ 0(재고 행이 없으면 0). 안전재고(Q-13) 없이 "품절"(사용자 결정 그대로). 취급 ON 이 하나도 없는 센터는 줄 없음.
  - 권한: OPERATOR_ADMIN·서비스 = 모든 센터(mfcId 선택), 그 밖 운영자 = 토큰 센터만(mfcId 권한 없으면 403). 지금 값(이력 없음). 게이트웨이 라우트 `/api/v1/stock-summaries` → mfc-inventory(PC 1, pmart-config).
- **상태**: 구현(PC 3, 2026-11-19) — LAN 은 PC 1 병합·배포 뒤.

## IV-6. 격리품을 격리 구역으로 옮기는 이동 — 품질검사 격리분이 입고 구역에 남음 (2026-11-20, 보낸 영역 mfc — PDA BR-52 R19-5)
- **원 요청**: pmart-clients `docs/handoff/mfc-app/2026-11-10-backend-pda-work-areas.md` R19-5 "품질검사 격리분을 입고 구역에서 격리 구역으로 옮기는 작업(유형·출발·도착). 지금은 만들어지지 않아 입고 구역에 남는다."
- **근거(업무 문서)**: `MFC-business-logic.md` §5 "냉장·냉동 격리상품은 각각 별도 4평에 보관하고 정상 보충재고에 섞지 않는다", 입고 5단계 "격리품은 상온/냉장/냉동 각 4평에 분리 기록한다". 격리 구역(상온·냉장·냉동 각 4평)은 확정.
- **지금**: mfc-inbound 품질검사(`/inbound-tasks/{id}/inspections`)의 QUARANTINED 줄은 inventory INSPECT 로 **수령 위치(입고 구역)에 QUARANTINED·UNSTOWED** 로 남는다. 옮길 길이 없다 —
  이동 배정(`/internal/move-allocations`, `MoveService`)은 출발 = 선반·피킹 구역(`Locations.STORE_ZONES`)의 **NORMAL·STORED** 만, 도착 = `requireStorage`(선반·피킹 구역)만 받는다. 적치(PUTAWAY)는 NORMAL·UNSTOWED 만.
  회수품은 처음부터 반품 위치(RETURN 구역)에 격리로 들어가서 이 문제가 없다.
- **할 것(제안 — PC 3 가 정한다)**: 격리 이동 하나 — 출발 = 그 LOT 의 QUARANTINED 가 있는 칸(입고 구역 등), 도착 = **격리 구역**(온도대 = 상품 보관 유형) 칸, 수량 ≤ 그 칸의 격리 수량. 품질 상태는 QUARANTINED 그대로(Q·E 불변, 위치만), 도착 칸은 진열 규칙(카테고리·한 상품 한 자리) 대신 격리 구역 규칙만.
  방법 후보: ① 이동 배정에 `quality: QUARANTINED` 선택값(도착 구역 검사를 격리 구역으로 바꿈) ② 작업 실행에 새 `executionType`(STAGE 처럼 위치만). 그다음 mfc-inbound 가 품질검사 반영 뒤 격리 줄마다 이동 작업(예: 입고 작업 유형 `QUARANTINE_MOVE`, 추천 도착 칸 포함)을 만들고 mfc-capacity 다음 작업 받기(재고 이동 업무)에 넣는다.
- **정할 것(사용자)**: 격리 이동을 누가 하나(품질검사 직원이 이어서 / 재고 이동 업무 작업자), 격리 구역 칸 코드(위치 마스터 — 구역 이름이 `QUARANTINE` 인지), 회수품 반품 위치(RETURN 구역)와 격리 구역의 관계(같은 곳인지).
- **요청한 쪽에서 바꿀 것(mfc-inbound·PDA)**: inventory 쪽이 생기면 inbound 가 이동 작업을 만들고 PDA 는 다음 작업 받기로 받는다. **그 전까지 inbound 는 아무것도 만들지 않는다**(반쪽 구현 금지 — 2026-11-20).
- **사용자 결정(2026-11-20)**: 옮기는 사람 — 회수품은 반입한 사람, 입고 결함은 찾은 검수자(작업을 그 사람에게 미리 배정). 격리 칸 = 위치 구역 `QUARANTINE`(코드 `QRT-{온도대}-{번호}`, LAN 시드 `QRT-A-01`·`QRT-C-01`·`QRT-F-01`).
- **상태**: **구현**(2026-11-20 PC 3) — inventory 0.35.0 추가분(V25, 브랜치 `mfc/br52-inventory`): 작업 실행 `QUARANTINE_MOVE`(②안 — 출발 칸 QUARANTINED → 격리 구역·온도대, 위치만), `placement-suggestions?purpose=QUARANTINE`, 품질 해제·폐기가 격리 구역으로 옮겨진 격리품을 따라감(mfc-quality 변경 없음), 격리 구역에서 해제하면 NORMAL·UNSTOWED. inbound 0.17.0(V13, 브랜치 `mfc/inbound-quarantine`): 작업 유형 `QUARANTINE_MOVE`(품질검사 격리 → 검수자, 회수품 검수 폐기 → 반입자, 현장 예외 파손·변질 → 보고자), `POST /inbound-tasks/{id}/quarantine-moves`, 해제분 적치 작업. 남은 것: mfc-capacity 다음 작업 받기에 `QUARANTINE_MOVE` 없음(요청 MC-2), 회수품 검수 전(PENDING) 격리분은 옮기지 않는다(검수는 반품 위치에서).

## IV-7. 재고 변동 실시간 스트림 — 전체 센터 한 연결 (2026-11-24, 보낸 영역 backoffice — PC 2)

- **근거**: PC 1 사용자 결정(2026-11-24) "실시간 재고 차감 취소 시 증감이 바로바로 백오피스에 반영 되어야 해" → 방식 B(서버가 밀어 주기, SSE), MFC·통합 백오피스 전부. 통합 백오피스 화면 설계는 pmart-clients `docs/handoff/backoffice/2026-11-24-backend-live-stock-stream.md`(대상은 대시보드 "센터별 품절" 칸 하나 — 지금 `GET /stock-summaries` 를 60초마다 다시 읽음).
- **사용자 결정(2026-11-24, PC 2)**: "재고 서버가 전체 스트림을 직접 주는 1번으로" — **mfc-inventory 가 전체 센터 변동을 한 연결로 내보낸다. backoffice-bff 는 중계하지 않는다**(bff 는 재고를 다루지 않고, 긴 연결·다시 잇기·토큰 만료를 bff 가 따로 맡지 않게). 웹이 센터마다 따로 여는 안(브라우저 연결 수 한도)도 쓰지 않는다.
- **필요한 것**:
  1. 스트림 경로 하나(`text/event-stream`) — 예 `GET /stock-summaries/stream?mfcId=`(이름·경로는 PC 3 이 정함). **mfcId 를 주면 그 센터, 비우면 볼 수 있는 모든 센터.**
  2. 권한은 `GET /stock-summaries` 와 같게 — OPERATOR_ADMIN·서비스 = 모든 센터, 그 밖 운영자 = 토큰 센터만(mfcId 권한 없으면 403, 비우면 자기 센터들).
  3. 이벤트 — 화면은 이벤트 값으로 품절 수를 세지 않고, 받으면 그 센터 `GET /stock-summaries?mfcId=` 만 다시 읽는다(2초 모아 한 번). 그래서 **최소로는 "이 센터 재고가 바뀌었음"이면 된다**: `{eventId, mfcId, occurredAt}`(예 `event: STOCK_CHANGED` 또는 품절 요약이 바뀔 때만 `SUMMARY_CHANGED` — 어느 쪽이든 PC 3 편한 대로). SKU·가용 수량·원인·버전(웹 글의 바람)은 있으면 좋지만 필수 아님.
  4. 연결 유지 신호(heartbeat 주석 줄) 5초 안팎 — 게이트웨이 시간 제한(PC 1 이 이 경로만 풀기로 함) 때문.
  5. `id:` 줄 + 다시 연결 때 `Last-Event-ID` 이어 받기는 있으면 좋다(없으면 화면이 다시 붙을 때 한 번 다시 읽어 덮는다).
- **화면 쪽(웹)**: 토큰을 메모리에 두어 EventSource 대신 fetch 스트림(Authorization 헤더). 화면이 보일 때만 연결, 끊기면 1초 → 30초까지 늘려 다시 연결, 끊긴 동안 "실시간 끊김 — 마지막 HH:MM 기준", 60초 다시 읽기는 끊겼을 때만.
- **PC 1 이 할 것**: 경로가 정해지면 게이트웨이 라우트(pmart-config)·그 경로의 응답 시간 제한(전역 10초) 풀기, 사용자 유형 허용 목록.
- **MFC 백오피스(PC 3 화면)**: 같은 스트림을 센터 하나(mfcId)로 쓰면 된다 — 화면 설계는 PC 3.
- **상태**: 구현(PC 3, 2026-11-24 — mfc-inventory 0.46.0, PR 은 일지·HANDOVER). 회신:
  - 경로 **`GET /api/v1/stock-stream`**(`mfcId` 반복·선택 — 비우면 볼 수 있는 모든 센터, 권한 = `/stock-summaries`, 권한 밖 센터 403). 응답 `text/event-stream`.
  - 이벤트: `ready`(접속 직후 — 이어 받기가 아니면 이걸 받은 뒤 스냅샷), `stock-changed`(재고 수량이 바뀐 확정 단위마다 커밋 뒤 — `id:` = `eventId`, `mfcId`·`occurredAt`·`cause`·`items[]` SKU 별 바뀐 뒤 H·Q·E·R·A·`availableDelta`·`version`, `units[]`·`holds[]`), `summary-changed`(가용이 0 경계를 넘은 SKU 가 있을 때만 — `skus[]`. **통합 대시보드 품절 칸은 이것만 보고 `/stock-summaries?mfcId=` 를 다시 읽으면 된다**), `resync`(다시 읽기). heartbeat 5초 주석 줄 `: hb`.
  - `Last-Event-ID` 이어 받기 1시간(겹쳐 올 수 있음 — SKU 별 `version` 큰 것만). 최대 연결 30분 뒤 서버가 닫음 → 다시 접속.
  - PC 1: 게이트웨이 라우트(mfc-inventory)·이 경로 응답 시간 제한 풀기 — 시험 사본 `api-gateway.yml`·허용 목록 `user-type-allowlist.txt` 는 같은 PR 에 넣음, 실제 pmart-config 는 PC 1.

## LS-1~3. 대시보드 "곧 품절" 상품 수·목록·실시간 — 하루 판매량 기준 (2026-11-25, 보낸 영역 backoffice — PC 2, PC 1 전달)

- **근거**: 요청서 pmart-clients `docs/handoff/backoffice/2026-11-25-backend-low-stock-count.md`(PR #517). 사용자(PC 2) "근데 품절되기 전에 알아야 하는 거 아님?" → **사용자 결정(2026-11-25) "하루 판매량 기준"** — 발주 수량 추천(MI-4)과 같은 d(28일 이동평균)·같은 공급 정책 표.
- **필요한 것**: LS-1 `GET /stock-summaries` 줄마다 `lowStockSkuCount`(0 < A ≤ SS)·`lowStockUnknownSkuCount`(d 를 못 정함 — 0 으로 채우지 않기)·`lowStockBasis`. LS-2 곧 품절 목록(sku·A·d·SS, 남은 날 순, 품절 목록도). LS-3(선택) `/stock-stream` `summary-changed` 를 SS 경계에서도.
- **상태**: 구현·LAN 배포(PC 3, 2026-11-25 — mfc-inventory 0.47.0 V32·0.48.0(재고 이력 #470), mfc-inbound 같은 PR, #465·#470 375cd21e·a3e01377, pc3·게이트웨이 pc4 a3e01377, pmart-config 5700f40). LAN 은 인계 실적(handover_sale_daily)이 없어 기준을 보내는 센터가 없고 모든 SKU 가 "기준 모름"(503개) — 실적이 쌓이면 1시간 안에 채워진다. 회신 `docs/handoff/clients/backoffice/2026-11-25-reply-low-stock-count.md`.
  - d·정책의 정본은 mfc-inbound — `LowStockBasisPublisher` 가 1시간마다(기동 1분 뒤) 센터마다 `PUT /internal/mfcs/{mfcId}/low-stock-basis`(온도대별 safetyDays·historyDays·minHistoryDays·averageWindowDays + 판 SKU 의 d). inventory 가 A 와 비교해 센다. **곧 품절 판정에는 공급 정책 중 safetyDays 만** 쓴다(검토 주기·리드타임 등이 비어도 셈).
  - 목록 `GET /stock-summaries/{mfcId}/skus?kind=LOW_STOCK|SOLD_OUT|LOW_STOCK_UNKNOWN&size&cursor`.
  - PC 1: 게이트웨이 mfc-inventory 라우트 Path 에 `/api/v1/stock-summaries/*/skus`(시험 사본·허용 목록은 PR 에 있음).

## IV-8. CDC 이관 수령 확정 뒤 `MfcReceiptPosted` 발행 (2026-11-25, 보낸 영역 cdc — D 클론 cdc 세션)

- **요청한 곳**: cdc-inventory 운송 중 재고(Transit) 대사(설계 `docs/cdc/design/2026-11-25-receipt-transit.md`, WMS CDC-F-024·025).
- **왜**: CDC 출발(TOUT)로 Transit +q 가 생기지만, MFC 가 받은 결과가 CDC 로 오지 않아 Transit 이 출발량 그대로 남는다. 이벤트 목록(`events.md`)에는 `MfcReceiptPosted` 발행 = mfc-inventory, 구독 = cdc-inventory 로 적혀 있으나 아직 발행이 없다(ReceiptService 주석 "TRF 경로 구현 때 발행").
- **필요한 것**
  1. CDC 이관(TRF) 입고 — mfc-inbound 가 `TransferDeparted` 로 만든 입고 예정의 수령·검수 → TIN 확정 — 뒤 `MfcReceiptPosted.v1`(토픽 `mfc.inventory.v1`) 발행. 계약 그대로: receiptId(한 번의 수령 확정 — exact-once 키)·postingId·commitId(TIN)·trfId·departureId·mfcId·fromCdc·lines[QualitySplit — sku·lot·normalQty·quarantinedQty·pendingQty]·receivedQty(= 정상 + 격리 + 미판정)·postedAt.
  2. LOT 은 CDC 출발 LOT(매니페스트·`TransferDeparted` 의 lot) 그대로 — MFC 로컬 LOT 키가 따로 있으면 출발 LOT 을 함께 실어 주기(CDC 가 출발 줄과 맞춘다).
  3. 수령 수량 승인 정정이 생기면 `ReceiptCorrectionPosted`(correctionId·원 receiptId·δr) — 품질 판정만 바뀐 것은 내지 않음(CDC 수령 누계 그대로).
- **CDC 쪽**: cdc-inventory 가 구독해 줄별로 Transit −r 대사(같은 receiptId 한 번, 출발 줄 초과·없는 LOT 은 그 줄만 보류) → `TransitReconciled`. 유실 복구용 `/internal/receipt-acks`(같은 규칙). 미정산은 자동 손실로 지우지 않는다.
- **사용자 결정(2026-11-25)**: 미판정도 받은 수량으로 본다(r 에 넣음), 전달은 사건 + 복구용 API.
- **설정**: cdc-inventory 구독 바인딩(`mfc.inventory.v1`) — PC 1(pmart-config).
- **(2026-11-26 cdc 세션 덧붙임)** CDC 쪽 `ReceiptCorrectionPosted` 구독도 구현했다(cdc-inventory 0.13.0) — **줄(`lines[]` sku·lot·deltaNormal/Quarantined/PendingQty)을 꼭 실어 주기**(deltaQty 만 있으면 출발 줄에 나눌 수 없어 거절). 품질 판정만 바뀐 것은 내지 않는다.
