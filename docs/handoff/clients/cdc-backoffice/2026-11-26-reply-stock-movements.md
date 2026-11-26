보낸 영역: cdc(PC 1 D 클론 — CDC 세션)
받는 앱: cdc-backoffice
원 문서: pmart-clients `docs/handoff/cdc-backoffice/2026-11-26-backend-missing-screens.md`(PR #579) LG-1 — 나머지(IB·RP·TR)는 `2026-11-26-reply-backend-missing-screens.md`
작성: pmart-backend, 2026-11-26
상태: **구현(cdc-inventory 0.20.0) — LAN 배포·게이트웨이 라우트 전**

# 회신: LG-1 재고 이동 이력

`GET /cdcs/{cdcId}/stock-movements?sku=&lot=&locationCode=&kind=&from=&to=&page=&size=` (OPERATOR, 자기 센터 — 다른 센터 403)

- **sku 또는 locationCode 중 하나는 필수**(없으면 400). 기간 from·to(ISO date-time, 기본 최근 7일, 최대 31일 — 넘으면 400).
- 최신 순(appliedAt 내림), 쪽 나눔 page 0부터·size ≤ 200(기본 50)·hasNext — transfers·waves 와 같음. 응답 `{from, to, items, page, size, hasNext}`.
- 줄 = 원결과(commit) 하나 `StockMovement`:
  - `commitId`·`postingKind`·`appliedAt`·`requestId`·`sourceSystem`·`sourceRef`·`actorId`·`deltas[{sku, lot, locationCode, bucket(ON_HAND·RESERVED·HELD·TRANSIT), delta}]`
  - `postingKind`: ALLOCATION·ALLOCATION_ADD·RELEASE·TOUT(출발)·TIN_ACK·TIN_CORRECTION(MFC 수령·정정)·TRANSPORT_LOSS·MOVE(내부 이동)·QUALITY_HOLD·QUALITY_RELEASE·DISPOSAL·CNT(실사 조정)·INITIAL_BALANCE.
  - `sourceRef` 로 원천을 찾으세요: TOUT = departureId(출발 검증 화면), MOVE = `<moveKind>:<참조>`(REPLENISHMENT_MOVE·PICK_MOVE·RELOCATION…), QUALITY_*·DISPOSAL = `<HOLD|RELEASE|DISPOSE>:<참조>`, CNT = `COUNT:<세션 ID>`, ALLOCATION = trfId, RELEASE·ALLOCATION_ADD = `<사유>[:<참조>]`.
  - `actorId` = 요청 토큰 주체(운영자 ID 또는 `cdc-outbound-service` 같은 서비스 이름). **이번 판 전에 쌓인 원결과와 사건 구독(MFC 수령 등)은 null** — 그때는 sourceSystem 을 보여 주세요.
- deltas 는 sku·lot 거르기에 맞는 것만 담습니다. **locationCode 로 거르면** 그 칸에 증감이 있는 SKU·LOT 의 다른 칸 증감도 함께 담아 이동의 출발·도착을 한 줄에서 볼 수 있습니다.
- 요청서와 다른 점: **뒤 수량(전기 후 잔고)은 없습니다** — 지금 수량은 `location-stocks`·`stock-snapshot`. 처리자 표시 이름도 없습니다(ID 만).
- 쓰는 곳 예: 재고 현황 줄 펼침 → `?sku=&lot=`, 칸별 재고·재계수 대기 → `?locationCode=`, 실사 결과 → `?locationCode=&kind=CNT`.
