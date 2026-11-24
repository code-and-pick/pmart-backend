# CDC 인수인계 (2026-11-24 작성)

CDC WMS 작업을 이어 받는 세션·사람이 **처음 읽는 문서**. 무엇이 있고, 무엇이 없고, 어디부터 읽는지를 모았다. 업무 원문·ADR·계약이 정본이고, 이 문서와 다르면 그쪽을 따른다.

## 0. 영역·세션
- 영역: 따로 없음 — `docs/operations/pc-split.md` 상 "영역 밖 서비스"라 **platform(PC 1)** 몫. cdc 영역을 나누려면 pc-split·`docs/worklog/cdc/` 부터 사용자와 정한다.
- 작업 세션(2026-11-24 사용자): PC 1 의 **D 드라이브 클론** `D:\development\pmart-project\pmart-backend-cdc` 에서 연다(C 쪽 주 작업 폴더와 따로). **모든 변경은 무조건 PR**(사용자 2026-11-24 — 본 작업 폴더가 아니라 충돌이 날 수 있어서): main 에 직접 커밋·push 하지 않고, 일마다 `origin/main` 에서 `cdc/<주제>` 브랜치 → PR 전 `git rebase origin/main`·시험 다시 → PR → PR 백엔드 세션 검토·병합. 공용 표는 PR 본문에 "공용 표 갱신 필요". 게이트웨이·pmart-config·LAN 배치·배포는 PC1 백엔드 세션.
- 그 세션만의 규칙은 클론의 `CLAUDE.local.md`(커밋 안 함).

## 1. 지금 있는 것
| 무엇 | 상태 |
|---|---|
| `services/cdc-inventory-service` | 스캐폴딩(2026-10-30, 86xx 첫 서비스, 로컬 포트 8601). 상품 등록 슬라이스만: `commerce.product.v1` SkuIssued·ProductVersionPublished → `sku_projection`(물류 규격 units_per_box·boxes_per_pallet·ti·hi), 센터 취급 `center_sku_handling`(레코드 없음 = OFF, `/cdcs/{cdcId}/sku-handlings`). Flyway V1·V2. **장부(0.2.0, 2026-11-25)**: 초기 보유 `/internal/initial-balances`(센터·SKU·LOT 1회)·안전량 `/internal/safety-stocks`(새 경로)·가용 `max(0,E−R−S)`·FEFO 다LOT 할당/해제·TOUT 출발 전기(`TransferDeparted` 발행 — mfc-inbound 가 받음)·원결과·Transit 조회, Flyway V3. **내부 이동(0.3.0)**: `/internal/move-postings` — `allocationId` 를 주면 할당 예약을 함께 옮김(랙 → 피킹 선반 보충), Flyway V4. **재고 스냅샷(0.4.0)**: `/internal/snapshots` — LOT 마다 위치별 현물·예약. **아직 501: 수령 확인(receipt-acks)·품질·손실·조정·일일 대사.** 게이트웨이·LAN 배치·Helm 미등록 |
| `services/cdc-location-service` | 2026-11-25 새로 만듦(로컬 포트 8602). 위치 마스터: 등록·일괄 등록 `/internal/location-batches`·조회·상태 전환(`LocStateChanged`), 칠곡 코드 문법 검사(CDC-3~5·7). 점유는 모름(null). 적치 후보·공간 예약·도크 예약은 501. pmart-config 정본 파일·게이트웨이·LAN 배치 없음(PC1 요청) |
| `services/cdc-outbound-service` | 2026-11-25 새로 만듦(로컬 포트 8603, 계약 0.3.0). TRF 인수 → cdc-inventory 할당, Wave 생성·릴리스(할당의 지금 위치로 구역·층별 PICK Task), PDA 다음 작업 받기·착수·피킹 실적(부족 → PickShortageReported). Task 경로 `/cdcs/{cdcId}/tasks…`. 0.4.0: 분류·검품·상차(BOX·RT 롤테이너)·출발 검증 → TOUT·잔여 해제·매니페스트. **아직 501: 출발 준비·미출발 취소.** pmart-config·게이트웨이·LAN 없음(PC1 요청) |
| 계약 | `contracts/openapi/cdc-{inventory,inbound,location,outbound,quality}-service.yaml` 다섯 개 모두 있음(구현은 inventory 일부만) |
| 설계 | `docs/architecture/service-map.md` §4 CDC WMS — 다섯 서비스 소유·발행·구독·사가 S7(적치) |
| 업무 원문 | `docs/business/WMS-business-logic.md`(정본) + `docs/business/WMS-dc-wave-business-logic.md`(Wave·지역 바코드 피킹·분배·라인·보충 MOV — 2026-11-24 사용자 채택) — `MFC-business-logic.md` 와 섞지 않는다 |
| 시뮬레이터 | `pmart-cdc-simulation`(세션 "cdc 시뮬레이션", 2026-11-24 설계 시작). 센터 **칠곡 DC `CDC01`** — 고정값(8,600평·5~7층·520만 유닛·주소)·현장 근거·결정 CDC-1~8(층 배치·위치 코드·온도 글자·비랙 위치·공급 MFC 5곳·4지역·피킹 선반·하루 시간표)은 `docs/simulator/cdc/chilgok-dc.md`, 회신 `docs/handoff/simulator/cdc/`. 범위 **출고·피킹·상차만**(S-3·S-6), CDC 재고는 시작 재고(시드) |

## 2. 없는 것
- 서비스: `cdc-inbound`·`cdc-quality`. cdc-outbound 출발 준비·미출발 취소, 피킹 부족 즉시 해제·재할당, TRF 새 버전 대체.
- cdc-outbound 가 TMS 에 줄 것(TMS 정본 2.1, chilgok-dc.md CDC-8): 21:30 출고 작업 완료·22:00 MFC·온도대별 R/T 수(박스 스캔 → R/T 매핑), 방문 역순 상차 대조, 실제 출발 때만 TOUT. 회수(빈 R/T·회수품) 반품 입고는 cdc-inbound.
- cdc-outbound 게이트웨이: `/api/v1/tasks/**` 는 mfc-fulfillment 라우트가 잡고 있다(PR 백엔드 #443 댓글) — cdc-outbound 를 만들 때 Task 경로를 `/cdcs/{cdcId}/tasks…` 처럼 겹치지 않게 바꾸고 게이트웨이 허용 목록·라우트를 같이 요청한다.
- 칠곡 위치를 실제로 넣는 일 — 목록 사본(23,076칸)·스크립트 `deploy/lan/seed/cdc_locations.py` 는 있다. cdc-location LAN 배치 뒤 PC1 이 `--apply`. 오토스토어 빈 코드(R-05)는 아직 없다.
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
- ~~피킹 배정 방식~~ → 2026-11-25 사용자 "PDA 있고, MFC 기능은 다 구현되어 있으니까 그거 DC 버전 만들어서 쓰면 돼. 피킹은 다음 작업을 PDA 가 자동 배정". cdc-outbound 계약 0.2.0 에 MFC `next-tasks` 의 CDC 판(다음 작업 받기·착수)을 넣음. CDC 화면(PDA)도 MFC PDA 의 DC 판으로 만든다(pmart-clients 몫 — 클라이언트에 요청할 때 이 결정을 근거로).
- cdc 영역을 따로 둘지(일지 폴더·PC).

## 5. 기록
- 2026-11-24: 이 문서·D 클론 시작(PC 1, 사용자 "가, 나 둘다").
- 2026-11-24: CDC 시뮬레이터 R-01~R-06 회신, 칠곡 DC 결정 CDC-1~6, DC Wave 근거 문서 채택, center-master CM-3 요청, cdc-location 계약 0.1.1(코드 설명만) — PR `cdc/simulator-decisions`(#436). 같은 날 R-07(지하 접안 DOCK `B1`·층 승강기 문 92)·R-08(구역 글자 A 냉동·B 냉장·C~E 상온·F 인기·G/H 생활용품, 사용자 "MFC 시뮬레이션이 기준") 반영.
- 2026-11-24: R-09·R-10 층 배치(#437). R-10 의 무거운 상온 C·D 6통로는 사용자 의도("냉동 그대로, 5층 무거운 것, 7층 상온 생활용품", 2026-11-25)와 맞아 그대로 둔다 — 용량은 도면(R-05) 때 다시 본다.
- 2026-11-25: cdc-inventory 장부 0.2.0(초기 보유·안전량·가용·할당/해제·TOUT·원결과·Transit) — PR `cdc/inventory-core`.
- 2026-11-25: cdc-location 서비스·위치 마스터(계약 0.2.0) — PR `cdc/location-service`. 사용자 제공 CDC 구축 명세서는 참고 문서로만("칠곡 결정 유지"). R-13 피킹 선반(통로 61~ PICK_FACE, 랙 전부 RESERVE).
- 2026-11-25: TMS 흐름 맞춤 — 공급 MFC 26곳(충청·강원 9·경상·전라 17, 지금 등록 5곳), DC 하루 시간표·R/T 운송 단위·회수(CDC-6·8). 사용자 노션 "TMS"(2026-11-25)와 TMS 정본 2.1 의 차이(고객 주문 시작 08:00↔09:00, 마지막 MFC 도착 07:30↔08:30)는 TMS·고객 쪽 결정이라 고치지 않고 사용자에게 물음.
- 2026-11-25: 사용자 "지역은 4지역 묶음, 공급 대상은 일단 MFC 5곳", "TMS 문서 오늘 준 게 기준" — CDC-6 되돌림, TMS 정본 2.2(마지막 MFC 도착 07:30·인계 08:00·고객 주문 08:00~). 다른 도메인 문서의 고객 주문 09:00 은 그대로(사용자에게 알림).
- 2026-11-25: cdc-outbound 서비스 첫 판(0.3.0 — 인수·할당·Wave·Task·피킹) — PR `cdc/outbound-service`. 출발 쪽은 다음 PR.
- 2026-11-25: cdc-outbound 0.4.0(분류·검품·상차·출발 검증 → TOUT·매니페스트) — PR `cdc/outbound-departure`. R-15 회신(SIMULATED 도 TOUT).
- 2026-11-25: 사용자 "MFC랑 같은 sku"·"10,900 설계에 맞춰 선반 확장" — CDC-7 기록, 시뮬레이터에 선반 확장·위치 목록 재내보내기 요청(B-1). 시작 재고는 실제 LAN SKU 만(시드 다음 PR).
- 2026-11-25: cdc-inventory 0.4.0 재고 스냅샷(칸별 재고 조회) — PR `cdc/inventory-snapshot`.
