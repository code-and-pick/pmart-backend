# 시험 데이터 seed (LAN·로컬)

승인 경로 API 로만 적재한다(ADR-0014, DB 직접 입력 없음). JDK 21 만 있으면 된다.

```bash
java deploy/lan/seed/Seed.java                                  # LAN: 게이트웨이 = placement.conf 의 api-gateway PC :8080
java deploy/lan/seed/Seed.java --initial-stock                  # + 시험 초기 재고(SKU 마다 initialStockPerSku). 한 번만 — 다시 돌리면 그만큼 더 들어간다
java deploy/lan/seed/Seed.java --initial-stock --initial-stock-skus E-00000008,E-00000009   # 추가 SKU 도 함께
```

- 직원 토큰(2026-11-13, ADR-0056 — auth-service 운영자 로그인을 없앰): `deploy/lan/.secrets/keycloak-admin-password`·`keycloak-claude-dev-password`(또는 `PMART_SEED_KEYCLOAK_ADMIN_PASSWORD`·`PMART_SEED_DEV_PASSWORD`). Keycloak(pmart-dev-cli) 로그인 → employee-auth-service 시험용 교환. 로컬 프로필에는 Keycloak 이 없어 LAN 에서만 돈다(`--keycloak`·`--employee-auth` 로 주소 변경).
- 시험 직원 `seed-md`(MD, 센터 `MFC-SEED-01`)·`seed-approver`(PRODUCT·DISPLAY·PRICE·SERVICEABILITY·CENTER_MASTER·INVENTORY 승인)의 역할·센터를 맞춘다(관리자 claude-dev). 계정은 만들지 않는다(hr-service 로 등록). Keycloak 비밀번호는 실행마다 무작위로 정하고 저장하지 않는다(발의자 ≠ 승인자 규칙 때문에 두 계정).
- 순서: 센터·시간표 → 카테고리 트리 → 배송 권역 → 상품 → 콘텐츠·대표 이미지(합성 PNG) → 기본가 → MFC 취급 ON → (--initial-stock) 초기 재고. 이미 있으면 건너뛴다(상품은 바코드로 확인). 초기 재고 말고는 다시 돌려도 안전하다.
- 초기 재고는 INITIAL_STOCK 승인 → mfc-inbound 초기 보유 반입 → mfc-inventory 전기(D2, 조정으로 넣지 않음). 반영은 `GET /initial-stock-loads?approvalId=` 로 확인한다.
- 다른 카테고리 트리나 권역 매핑이 이미 발효 중이면 덮어쓰지 않는다(트리 발효본은 그대로 쓰고, 권역은 넣지 않는다). 발효 중인 권역이 모두 seed 권역(`Z-SEED-*`)이면 `seed-data.json` 의 권역으로 새 버전을 올려 교체한다. 시험 권역은 `serviceability`(센터 `mfcId`) + `serviceability.extraZones`(권역마다 자기 `mfcId`) — 2026-11-22 2호점 목포 `Z-SEED-MOKPO` → `MFC-MOKPO-01`(시뮬레이터 R-39). 모두 한 버전에 담고, 시험 점이 모두 맞는 센터로 판정되면 건너뛴다.
- **카테고리 묶음·순서(2026-11-24 BR-94)**: `seed-data.json` 의 합성 트리(t01~t03)와 그 뒤 묶음 추가(`/category-tree-additions`)로 생긴 참고 자료 노드에는 TOP 묶음(section)·레퍼런스 순서가 없다. 시드·묶음 적재를 다시 하면 끝에 `category_sections_br94.py`(발효 트리 내보내기 json 을 받아 t01~t03 상품을 옮기고 t01~t03 를 뺀 새 버전 — 묶음·순서 넣음, 승인까지)를 돌린다. 상품 없는 노드 숨김은 product-service 0.6.0 이 조회 때 계산한다.
- 센터가 이미 있어도 이름·주소·좌표가 `seed-data.json` 과 다르면 CENTER_MASTER 승인(UPDATE_PROFILE)으로 맞춘다. 시험 위치: 부산진구(업무 문서의 초기 부산 MFC 5곳 중 하나) — 2026-11-16 부산진구지점 실제 주소 "부산 부산진구 동천로 109 삼한골든게이트 2층"(35.158501, 129.062021)로 바꿈(그 전 서면 부근 시험 좌표). 권역 `inside` 시험 점(서면역 부근)은 고객 쪽 시험 좌표라 그대로.
- 데이터: `seed-data.json`. 모두 합성(상품명 `[시험] …`). `local-reference/` 캡처 자료는 쓰지 않는다.

## MFC 칸·재고·바코드 정리 스크립트 (Seed.java 뒤, PC 1)
| 스크립트 | 하는 일 |
|---|---|
| `mfc_layout.py` | 2026-11-09 시뮬레이터 평면대로 칸 등록(코드·좌표), 옛 선반 재고 이전, 고회전 시험 칸(`httest`) |
| `mfc_layout_v2.py` | 2026-11-16 칸 수를 MFC 문서 설계로(14,559칸) — 기존 칸 치수 변경·새 칸 등록·H 6단 닫기. 다시 돌려도 남은 것만 한다 |
| `mfc_spread_stock.py` | 재고를 랙 전체로 펼침(한 SKU 한 칸, 위치 정정 `layout-v2`) |
| `mfc_wide_cells.py` | 2026-11-18 칸에 안 들어가는 상품 → 같은 단 빈 칸을 합친 넓은 칸(세로 합침 포함)·`MIGRATION` 상품 → 보통 칸(위치 정정 `layout-v3-wide`). 결과 `wide-cells-2026-11-18.json` |
| `estimate_dimensions.py` · `apply_estimated_dimensions.py` | 상품 치수 추정 → 승인 경로 재적재(바코드도 함께 — 바코드 교체에도 씀) |
| `mfc_fix_lot_dates.py` | 비식품 LOT 소비기한 제거, 출고 불가 식품 LOT 소비기한 다시 매김(위치 정정) |
| `location_labels.py` | 위치 바코드 라벨 다시 만들기(`labels/locations/`). 스캔 코드·299 바코드 설명 `labels/README.md` |
| `seed-template-2026-11-24.json` | 2026-11-24 1호점 다시 설계 전 칸·재고 고정본 — `mfc_clone_center.py` 가 원본 MFC-SEED-01 대신 읽음(`freeze` 가 만듦, 실시간은 `PMART_SOURCE_LIVE=1`) |
| `cdc_center.py` | 2026-11-25 CM-3 — 칠곡 DC `CDC01`(type CDC) center-master 등록(계획만·`--apply`, 있고 같으면 건너뜀). 값 `docs/simulator/cdc/chilgok-dc.md` §1 |
| `cdc_locations.py` | 2026-11-25 cdc — 칠곡 `CDC01` 위치 32,436칸(랙 RESERVE 19,840·피킹 선반 PICK_FACE 12,480·출고대기/승강기 문 102·지하 접안 14)을 cdc-location `/internal/location-batches` 로(2,000행 묶음, ACTIVE, 묶음마다 정해진 멱등 키라 다시 돌려도 안전). 목록 `docs/simulator/cdc/layout/chilgok-locations.json`(시뮬레이터 dd3d185 사본). 계획만·`--apply --approval-ref <BO-06>`, 주소 `PMART_CDC_LOCATION`(기본값 없음 — cdc-location LAN 배치 전) |
| `mfc_clone_center.py` | 2026-11-22 R-39 — 1호점 칸 구성을 다른 센터(기본 2호점 `MFC-MOKPO-01`)에 복사. `locations`(원본 ACTIVE + 위치 파일 칸, 원본에서 닫힌 칸은 닫음 — 계획만, `--apply`), `stock`(정식 입고 흐름으로 재고 — 시험 입고 예정 → 수령 → 품질검사 → 적치, 계획만·`--apply`), `stock-plan`(원본 재고 요약·파일). 먼저 center-master 센터·대상 관리자 계정(`claude-mfc-sup-mk`) 2026-11-23 세종: `layout`(대상 센터 위치 파일로 칸 — 1호점 같은 코드면 그 속성, 없으면 같은 모양 칸을 본뜸), `stock --placement mixed`(1호점 칸이 있으면 그 칸·없으면 서버 나눠 넣기). 11-24 `wide`(1호점 넓은 칸을 대상 빈 랙에). 세종 배치가 바뀌면 `layout`(새 칸) → `mfc_layout.py r34plan`·`r34`(위치만). 11-24 춘천: 위치 파일 선반 `dimMm` 우선(랙 모양이 1호점과 다르면 필수), `wide` 랙 통째로·`PMART_WIDE_RACKS`, `prune`(파일에서 빠진 칸 닫기) |
| `mfc_carts.py` | 이동 카트 — 2026-11-17 40대(상온 20·냉장 12·냉동 8) → 같은 날 사용자 "일단 상온으로": **`CRT-A-01~40`**(온도 구분 없이 씀), 옛 냉장·냉동 카트는 닫음(재고 있으면 열어 둠 — `CRT-C-01`). 카트 라벨(Code128, "온도 구분 없음"). `--apply`·`--labels` |
| `mfc_split_lots.py` | 2026-11-17 LOT 변주 — LOT 1개뿐인 상품(옮길 수량 4 이상) 195개를 같은 칸에서 두 LOT 으로 반씩(새 LOT 기한·제조일 +7일, 기한 없는 것은 원천 표시만, approvalRef lot-split-2026-11-17). 적용: 성공 195·실패 0, LOT 1,064 → 1,259 |
