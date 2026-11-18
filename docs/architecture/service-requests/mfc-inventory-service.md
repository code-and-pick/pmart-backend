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
