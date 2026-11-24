# CDC 인수인계 (2026-11-24 작성)

CDC WMS 작업을 이어 받는 세션·사람이 **처음 읽는 문서**. 무엇이 있고, 무엇이 없고, 어디부터 읽는지를 모았다. 업무 원문·ADR·계약이 정본이고, 이 문서와 다르면 그쪽을 따른다.

## 0. 영역·세션
- 영역: 따로 없음 — `docs/operations/pc-split.md` 상 "영역 밖 서비스"라 **platform(PC 1)** 몫. cdc 영역을 나누려면 pc-split·`docs/worklog/cdc/` 부터 사용자와 정한다.
- 작업 세션(2026-11-24 사용자): PC 1 의 **D 드라이브 클론** `D:\development\pmart-project\pmart-backend-cdc` 에서 연다(C 쪽 주 작업 폴더와 따로). **모든 변경은 무조건 PR**(사용자 2026-11-24 — 본 작업 폴더가 아니라 충돌이 날 수 있어서): main 에 직접 커밋·push 하지 않고, 일마다 `origin/main` 에서 `cdc/<주제>` 브랜치 → PR 전 `git rebase origin/main`·시험 다시 → PR → PR 백엔드 세션 검토·병합. 공용 표는 PR 본문에 "공용 표 갱신 필요". 게이트웨이·pmart-config·LAN 배치·배포는 PC1 백엔드 세션.
- 그 세션만의 규칙은 클론의 `CLAUDE.local.md`(커밋 안 함).

## 1. 지금 있는 것
| 무엇 | 상태 |
|---|---|
| `services/cdc-inventory-service` | 스캐폴딩(2026-10-30, 86xx 첫 서비스, 로컬 포트 8601). 상품 등록 슬라이스만: `commerce.product.v1` SkuIssued·ProductVersionPublished → `sku_projection`(물류 규격 units_per_box·boxes_per_pallet·ti·hi), 센터 취급 `center_sku_handling`(레코드 없음 = OFF, `/cdcs/{cdcId}/sku-handlings`). Flyway V1·V2. **장부(0.2.0, 2026-11-25)**: 초기 보유 `/internal/initial-balances`(센터·SKU·LOT 1회)·안전량 `/internal/safety-stocks`(새 경로)·가용 `max(0,E−R−S)`·FEFO 다LOT 할당/해제·TOUT 출발 전기(`TransferDeparted` 발행 — mfc-inbound 가 받음)·원결과·Transit 조회, Flyway V3. **아직 501: 수령 확인(receipt-acks)·이동·품질·손실·조정·스냅샷·일일 대사.** 게이트웨이·LAN 배치·Helm 미등록 |
| 계약 | `contracts/openapi/cdc-{inventory,inbound,location,outbound,quality}-service.yaml` 다섯 개 모두 있음(구현은 inventory 일부만) |
| 설계 | `docs/architecture/service-map.md` §4 CDC WMS — 다섯 서비스 소유·발행·구독·사가 S7(적치) |
| 업무 원문 | `docs/business/WMS-business-logic.md`(정본) + `docs/business/WMS-dc-wave-business-logic.md`(Wave·지역 바코드 피킹·분배·라인·보충 MOV — 2026-11-24 사용자 채택) — `MFC-business-logic.md` 와 섞지 않는다 |
| 시뮬레이터 | `pmart-cdc-simulation`(세션 "cdc 시뮬레이션", 2026-11-24 설계 시작). 센터 **칠곡 DC `CDC01`** — 고정값(8,600평·5~7층·520만 유닛·주소)·현장 근거·결정 CDC-1~6(층 배치·위치 코드·온도 글자·비랙 위치·공급 MFC 5곳·지역)은 `docs/simulator/cdc/chilgok-dc.md`, 회신 `docs/handoff/simulator/cdc/`. 범위 **출고·피킹·상차만**(S-3·S-6), CDC 재고는 시작 재고(시드) |

## 2. 없는 것
- 서비스: `cdc-outbound`·`cdc-location`(시뮬레이터 범위에 먼저 필요), `cdc-inbound`·`cdc-quality`.
- cdc-inventory 의 MFC 수령 확인(Transit −r)·손실·이동·품질·조정·스냅샷·일일 대사.
- 품목군 최소 잔여일수(총기간 T 의 30%·20%) — 제조일 입력이 없어 아직 계산하지 않음(할당 요청의 `minRemainingDays` 와 당일 차단만).
- 승인량 초과 할당 검사 — 승인 정본(replenishment)이 없음.
- 이어지는 운송은 TMS(scope S-4·S-7) — 이 문서 범위 밖.

## 3. 먼저 읽을 것
1. 이 문서
2. `docs/architecture/service-map.md` §4
3. `docs/business/WMS-business-logic.md`
4. `docs/simulator/cdc/README.md`, `docs/simulator/scope.md`
5. `services/cdc-inventory-service/CLAUDE.md`, `docs/architecture/events.md` 의 CDC 줄, `docs/adr/` 의 CDC·재고 관련

## 4. 남은 결정(사용자)
- ~~첫 작업~~ → 2026-11-25 사용자 "cdc-inventory 부터", ~~시작 재고 방식~~ → "초기 보유 API 로". 다음 순서(세션 추천): cdc-location(CDC-3~5 코드) → cdc-outbound(지역 Wave·FC·분배·라인, 시뮬레이터 R-11 작업 배정 API 포함) → cdc-inbound 보충 MOV.
- cdc 영역을 따로 둘지(일지 폴더·PC).

## 5. 기록
- 2026-11-24: 이 문서·D 클론 시작(PC 1, 사용자 "가, 나 둘다").
- 2026-11-24: CDC 시뮬레이터 R-01~R-06 회신, 칠곡 DC 결정 CDC-1~6, DC Wave 근거 문서 채택, center-master CM-3 요청, cdc-location 계약 0.1.1(코드 설명만) — PR `cdc/simulator-decisions`(#436). 같은 날 R-07(지하 접안 DOCK `B1`·층 승강기 문 92)·R-08(구역 글자 A 냉동·B 냉장·C~E 상온·F 인기·G/H 생활용품, 사용자 "MFC 시뮬레이션이 기준") 반영.
- 2026-11-24: R-09·R-10 층 배치(#437). R-10 의 무거운 상온 C·D 6통로는 사용자 의도("냉동 그대로, 5층 무거운 것, 7층 상온 생활용품", 2026-11-25)와 맞아 그대로 둔다 — 용량은 도면(R-05) 때 다시 본다.
- 2026-11-25: cdc-inventory 장부 0.2.0(초기 보유·안전량·가용·할당/해제·TOUT·원결과·Transit) — PR `cdc/inventory-core`.
