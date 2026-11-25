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
| `services/cdc-outbound-service` | 2026-11-25 새로 만듦(로컬 포트 8603, 계약 0.3.0). TRF 인수 → cdc-inventory 할당, Wave 생성·릴리스(할당의 지금 위치로 구역·층별 PICK Task), PDA 다음 작업 받기·착수·피킹 실적(부족 → PickShortageReported). Task 경로 `/cdcs/{cdcId}/tasks…`. 0.4.0: 분류·검품·상차(BOX·RT 롤테이너)·출발 검증 → TOUT·잔여 해제·매니페스트. 0.6.0: 출발 준비 조회(WMS 자기 사실만). 0.7.0: 미출발 취소·축소(피킹 전에만 — 사용자 확정). pmart-config·게이트웨이·LAN 없음(PC1 요청) |
| 계약 | `contracts/openapi/cdc-{inventory,inbound,location,outbound,quality}-service.yaml` 다섯 개 모두 있음(구현은 inventory 일부만) |
| 설계 | `docs/architecture/service-map.md` §4 CDC WMS — 다섯 서비스 소유·발행·구독·사가 S7(적치) |
| 업무 원문 | `docs/business/WMS-business-logic.md`(정본) + `docs/business/WMS-dc-wave-business-logic.md`(Wave·지역 바코드 피킹·분배·라인·보충 MOV — 2026-11-24 사용자 채택) — `MFC-business-logic.md` 와 섞지 않는다 |
| 시뮬레이터 | `pmart-cdc-simulation`(세션 "cdc 시뮬레이션", 2026-11-24 설계 시작). 센터 **칠곡 DC `CDC01`** — 고정값(8,600평·5~7층·520만 유닛·주소)·현장 근거·결정 CDC-1~8(층 배치·위치 코드·온도 글자·비랙 위치·공급 MFC 5곳·4지역·피킹 선반·하루 시간표)은 `docs/simulator/cdc/chilgok-dc.md`, 회신 `docs/handoff/simulator/cdc/`. 범위 **출고·피킹·상차만**(S-3·S-6), CDC 재고는 시작 재고(시드) |

## 2. 없는 것
- 서비스: `cdc-inbound`·`cdc-quality`. cdc-outbound 출발 준비·미출발 취소, 피킹 부족 즉시 해제·재할당, TRF 새 버전 대체.
- cdc-outbound 가 TMS 에 줄 것(TMS 정본 2.1, chilgok-dc.md CDC-8): 21:30 출고 작업 완료·22:00 MFC·온도대별 R/T 수(박스 스캔 → R/T 매핑), 방문 역순 상차 대조, 실제 출발 때만 TOUT. 회수(빈 R/T·회수품) 반품 입고는 cdc-inbound.
- cdc-outbound 게이트웨이: `/api/v1/tasks/**` 는 mfc-fulfillment 라우트가 잡고 있다(PR 백엔드 #443 댓글) — cdc-outbound 를 만들 때 Task 경로를 `/cdcs/{cdcId}/tasks…` 처럼 겹치지 않게 바꾸고 게이트웨이 허용 목록·라우트를 같이 요청한다.
- 칠곡 위치를 실제로 넣는 일 — 목록 사본(31,880칸 — 팔레트 19,584·선반 12,180, R-17 2안 6616cc1)·스크립트 `deploy/lan/seed/cdc_locations.py` 는 있다. cdc-location LAN 배치 뒤 PC1 이 `--apply`. 오토스토어 빈 코드(R-05)는 아직 없다.
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
- 2026-11-25: 위치 목록 사본을 시뮬레이터 dd3d185(B-1 선반 확장)로 — 32,436칸, PR `cdc/chilgok-locations-v2`.
- 2026-11-25: 시작 재고 시드 `deploy/lan/seed/cdc_stock.py`(실제 LAN SKU, 취급·안전량 0·초기 보유) — PR `cdc/stock-seed`. → 2026-11-25 할당 피킹 선반 먼저(cdc-inventory 0.5.0·cdc-location 0.4.0 위치 일괄 조회) PR `cdc/shelf-first`.
- 2026-11-25: 출발 뒤 잔여 해제 거절 사유 — cdc-outbound 0.5.0 `residual_status` FAILED·`residual_reason`(V5), 출발 검증 응답 `transfers[].residualStatus·residualReason` — PR `cdc/residual-reason`(#455 검토 후속).
- 2026-11-25: 출발 준비 조회 `/internal/outbound-readiness` — cdc-outbound 0.6.0(CDC-F-020, TMS·MFC 조건 제외) — PR `cdc/outbound-readiness`. 다음: 미출발 취소(CDC-F-026).
- 2026-11-25: 미출발 취소·축소 cdc-outbound 0.7.0 — 사용자 "피킹 전까지만". 경로 `/cdcs/{cdcId}/transfers/{trfId}/pre-departure-cancellations`(게이트웨이 라우트 PC1 요청) — PR `cdc/pre-departure-cancel`. → 사용자 확정(2026-11-25): 취소는 피킹 전에만, 공급 쪽 TransferCancelled 구독·취소 사건 발행은 하지 않는다.
- 2026-11-25: 위치 목록 사본을 시뮬레이터 6616cc1(R-17 2안 — 사용자 "팔레트 18,000칸 이상" 뒤 지게차 통로 3.0m·선반은 DT)로 — 31,880칸, 랙 베이 01~48(cdc-location 0.4.2), PR `cdc/chilgok-locations-v3`. **LAN 은 아직 옛 dd3d185 32,436칸 + 시작 재고** — 전환 방법은 사용자 결정 대기(옛 칸 6,760 사라짐, 그 랙 칸에 재고 480줄·84,480개, 새 칸 6,204, 용량 바뀌는 칸 16,320 — 용량 변경 API 없음).
- 2026-11-25: 사용자 결정 — R-18 분배·라인·DPS 1단계를 시뮬레이터가 계약 그대로 만든다(백엔드 변경 없음, 2단계 묶음 피킹은 나중). LAN 위치 전환은 최종 랙 배치·설계가 정해진 뒤(그 전엔 시드 --apply 금지). PR `cdc/r18-decisions`.
- 2026-11-25: 묶음 피킹(2단계) 설계 `docs/cdc/design/2026-11-25-batch-picking.md` — 사용자 결정 4개(묶음 범위·부족은 기한 이른 TRF 부터 채움·6층은 선반 통로 pickMethod·미설명 수량이면 출발 검증 막음). 다음: 계약 → 구현. PR `cdc/batch-picking-design`.
- 2026-11-25: 묶음 피킹 결정 4 먼저 구현 — cdc-outbound 0.8.0, 실피킹 > BOX 분류(SKU 별)면 출발 검증 불일치 `UNEXPLAINED_QTY`(TOUT 없음, 사건 MISSING). 단건 피킹에도 적용. PR `cdc/departure-unsorted-block`. 다음: cdc-location pickMethod → cdc-outbound 묶음 작업.
- 2026-11-25: cdc-location 0.5.0 — 피킹 선반 통로별 피킹 방식 설정값(`/cdcs/{cdcId}/aisle-pick-methods`, DPS·BOX_PICK·FC·DISTRIBUTION·LINE, Flyway V3)·일괄 조회 `pickMethod`. PR `cdc/location-pick-method`. 남음: 칠곡 통로별 값(6층 B 어느 통로가 야채 분배인지 — 시뮬레이터 배치·사용자) 넣기, cdc-outbound 묶음 작업.
- 2026-11-25: R-19 위치(사용자 "그대로 진행") — cdc-location 0.5.1 문법 넓힘(랙 1단 PICK_FACE·통로 93 바닥 레인·H-7-61~62 오토스토어 16단 2칸), 사본 37,306칸(d0fdaff), PR `cdc/chilgok-locations-r19`. **LAN 은 아직 dd3d185 32,436칸 + 시작 재고** — 전환은 사용자가 최종 배치라 할 때. 시작 재고 시드(`cdc_stock.py`)의 칸 배치 규칙은 전환 때 새 목록에 맞춰 다시 본다(랙 1단이 피킹면이 됨).
- 2026-11-25: 묶음 피킹 구현 cdc-outbound 0.9.0(Flyway V7) — 통로 방식으로 단건·지역 묶음·SKU 묶음 작업, 줄 몫(task_line_share), 실피킹 기한 이른 몫부터·부족 늦은 몫부터, 지역표 `/cdcs/{cdcId}/regions`(칠곡 5곳 시드), 묶음이면 trfId 비움. PR `cdc/batch-picking`. LAN 은 cdc-location 0.5.0(#483) 배포·칠곡 통로 방식 값 넣기 전엔 모두 단건으로 돈다.
- 2026-11-25: #484·#485 LAN 배포(PR 세션 — cdc-outbound v7·cdc-location v3, ERROR 0). 남은 확인·할 일: ① LAN 첫 Wave 릴리스가 202 인지(cdc-outbound → cdc-location 기본 주소 `http://cdc-location-service:8602`, 503 이면 PC1 에 lan 주소 요청). ② **LAN 위치 전환 때**: 옛 목록(dd3d185)의 H-7-61/62 3번 칸 160개는 R-19 오토스토어 규칙(칸 1~2) 밖 — 등록 때만 검사해 지금은 쓰이지만 전환 때 정리(PR 세션 #485 댓글). ③ 칠곡 통로 → pickMethod 값(시뮬레이터가 확정하면 보냄)을 LAN 에 넣기 전엔 모두 단건.
- 2026-11-25: 칠곡 통로 → pickMethod 확정(시뮬레이터 R-19 회신) — 시드 `deploy/lan/seed/cdc_pick_methods.py`(89통로, 기본 계획만). 사용자: **LAN 에는 위치 전환 때 같이 넣는다**(옛 목록엔 랙 1단 피킹면·통로 93 이 없음). 전환 순서: `cdc_locations.py --apply` → `cdc_pick_methods.py --apply` → 시작 재고. PR `cdc/pick-method-seed`.
- 2026-11-25: R-20 DPS 통로 작업 — cdc-outbound 0.10.0(Flyway V8 `task.aisle`): DPS 는 TRF × 선반 통로, 다음 작업 받기 `aisles` 거르기(사용자 "제안대로"). PR `cdc/dps-aisle-tasks`.
- 2026-11-25: 피킹 부족 재할당 1단계 — cdc-inventory 0.7.0(Flyway V5): 해제 위치·재계수 대기(부족 칸은 새 할당에서 뺌 — 사용자), 추가 할당 `/internal/allocations/{id}/additions`, 운영자 대기 목록·풀기. PR `cdc/shortage-reallocation-inventory`. 다음: cdc-outbound 가 부족 보고 뒤 해제(재계수 대기) → 추가 할당 → 새 작업, 못 잡은 건 "못 찾음" 기록·알림(사용자 "48개만 보냄").
- 2026-11-25: 피킹 부족 재할당 2단계 — cdc-outbound 0.12.0(Flyway V9): 부족 후속(해제·재계수 대기 → 추가 할당 → 새 작업, 못 찾음은 집은 만큼만 출발·PickShortageResolved), 주기 재시도, readiness SHORTAGE_REALLOCATING. PR `cdc/shortage-reallocation-outbound`(cdc-inventory #491 먼저 배포).
- 2026-11-25: R-21 위치 사본 38,926칸(f3248e5, 사용자 요청 — 7층 H 랙 1단도 RESERVE, 오토스토어 4,800칸). 규칙 변경 없음(R-19 그대로). 통로 방식 시드는 사본에서 뽑으므로 H 랙 BOX_PICK 이 자동으로 빠짐(80통로). LAN 은 그대로.
- 2026-11-25: LAN 실제 확인 스크립트 `deploy/lan/checks/cdc_outbound_e2e.py`(선반 먼저 할당·Wave 릴리스·미출발 취소 정리) — 이 클론엔 `.secrets` 가 없어 PC 1 실행 요청(pc1-todo). 계획만 실행은 LAN 읽기로 확인(SKU B-00000006 LOT A 가 선반·랙 모두). PR `cdc/lan-outbound-check`.
- 2026-11-25: TRF 새 승인 버전 대체 — cdc-outbound 0.13.0(Flyway V10), 피킹 시작 전까지(사용자): 몫 빼기·Wave 에서 빼기 → 예전 할당 해제 → 새 버전 할당, CHANGING·CHANGE_HOLD. PR `cdc/transfer-version-change`(#492 0.12.0·V9 다음).
- 2026-11-25: LAN 실제 확인 9개 OK(PR 세션, 사용자 확인) — 선반 먼저 할당·Wave 릴리스 202(cdc-outbound → cdc-location 기본 주소로 닿음)·미출발 취소 정리. 재계수·실사 조정 설계 `docs/cdc/design/2026-11-25-count-adjustment.md`(사용자 결정 5개 — MFC 실사와 같게·MFC 본사 기준 10만 원·20개·실물<예약이면 나중 예약부터 부족·**피킹 재고는 출고대기 자리로 옮겨 적기**). PR `cdc/count-adjustment-design`.
- 2026-11-25: 피킹 재고 출고대기 자리로 옮겨 적기 — cdc-outbound 0.14.0(Flyway V11)·cdc-inventory 0.7.1(PICK_MOVE). 실사 설계 §7-1. PR `cdc/pick-move-staging`. 다음: §7-2 실사 세션.
- 2026-11-25: 위치 실사·CNT 조정 cdc-inventory 0.8.0(Flyway V6) — MFC 실사와 같은 흐름, 승인 관문 VERIFY/RECORD_ONLY(AP-4 전엔 RECORD_ONLY), 실물<예약 조정은 아직 409. PR `cdc/count-sessions`. 다음: 설계 §7-4(예약 부족 → cdc-outbound 첫 사건 구독).
- 2026-11-25: MFC 수령·운송 중 재고 대사 설계 `docs/cdc/design/2026-11-25-receipt-transit.md`(사용자 결정: 미판정도 받은 수량, 사건 + 복구용 API). PC 3 요청 mfc-inventory IV-8(`MfcReceiptPosted` 발행). cdc-inventory 구독·대사는 다음 PR. PR `cdc/receipt-transit-design`.
- 2026-11-25: 실사 부족 연결 — cdc-inventory 0.10.0(실물<예약이면 늦은 할당부터 예약 해제·`AllocationShortfall`)·cdc-outbound 0.17.0(첫 사건 구독 `inventoryEvents` → 작업 줄 부족 COUNT_LOSS → 재할당·못 찾음). PR `cdc/count-shortfall`. pmart-config 구독 바인딩 PC 1 요청.
- 2026-11-25: MFC 수령·운송 중 재고 대사 cdc-inventory 0.11.0(Flyway V7) — `MfcReceiptPosted` 구독·복구용 receipt-acks·줄별 대사·TOUT 전 수령 대기. PR `cdc/receipt-transit`(#500 0.10.0 다음). MFC 발행(IV-8, PC 3)·구독 바인딩(PC 1) 대기.
- 2026-11-26: 통로 피킹 방식 대응표 — 시뮬레이터 2657c94: 5F F·7F G 선반도 LINE(상온 DAS 존 — 1칸만 켜지는 비율 48% → 15%). `cdc_pick_methods.py` 80통로(LINE 31·FC 6). LAN 은 그대로(전환 때). PR `cdc/pick-method-line-ambient`.
- 2026-11-26: 수령 정정 cdc-inventory 0.13.0(Flyway V8) — ReceiptCorrectionPosted 구독·receipt-acks correctionId(정정 뒤 누계). PR `cdc/receipt-correction`. 다음: 운송 손실 승인(CDC-F-027).
- 2026-11-26: 운송 손실 승인 cdc-inventory 0.14.0(Flyway V9, #503 0.13.0 다음) — MFC 입고 종결 기록 `/internal/transit-closures` + 운영자 `/cdcs/{cdcId}/transit-losses`(종결 전 409, 실사와 같은 승인 관문). PR `cdc/transit-loss`. MFC 입고 종결(MI-5, PC 3)·승인 kind TRANSPORT_LOSS(AP-4 덧붙임, PC 1) 대기.
- 2026-11-26: R-22 구역 글자 바뀜(사용자, 기사 사진 — 옛 A→H·B→G·C→A·D→B·E→C·F→D·G→E·H→F)·층 도크 9·승강기 문 층마다 2 — cdc-location 0.6.0, cdc-outbound 출고대기 자리 계산(계약 그대로), 위치 사본 38,915칸·통로 방식·재고 시드. PR `cdc/layout-r22`. LAN 은 최종 배치 때.
- 2026-11-26: R-22 구역 글자 바뀜(사용자, 기사 사진 — 옛 A→H·B→G·C→A·D→B·E→C·F→D·G→E·H→F)·층 도크 9·승강기 문 층마다 2 — cdc-location 0.6.0, cdc-outbound 출고대기 자리를 위치 서비스 온도대로 고름(PR 백엔드 검토 — 옛 LAN 배치에서도 맞게, 계약 그대로), 위치 사본 38,915칸·통로 방식·재고 시드. PR `cdc/layout-r22`. LAN 은 최종 배치 때.
- 2026-11-26: 품질 보류·해제·폐기 cdc-inventory 0.15.0(#504 0.14.0 다음) — `/internal/quality-postings`, 보류로 걸린 예약은 늦은 할당부터 풀고 AllocationShortfall(QUALITY_HOLD), 출고대기 91 은 그대로, 해제·폐기 승인 번호 필수(기록만), 폐기는 증빙·실제 수량. cdc-outbound 는 부족 사유를 사건 그대로 적음(버전 그대로). PR `cdc/quality-hold`. 다음: 판정·승인 흐름의 cdc-quality(새 서비스), 일일 대사.
- 2026-11-26: R-22 추가(시뮬레이터 cbae2e9) — 5층 출고대기 91 상온 A·냉동 H 각 14칸, 위치 사본 38,913칸. PR `cdc/layout-r22-staging14`.
- 2026-11-26: 일일 대사 cdc-inventory 0.16.0(#506 0.15.0 다음) — 조회할 때 원장으로 계산, 예약·운송 중 대조. PR `cdc/daily-reconciliation`. 남은 것: 새벽 배치·경보(승인 설정 뒤), 운영자 화면 경로(DC 백오피스 요청 오면), 판정·승인의 cdc-quality.
- 2026-11-26: **cdc-quality-service 새로 만듦**(0.2.0, 포트 8604, Flyway V2) — 보류·해제·폐기(사용자 "보류·해제·폐기만"·"MFC 품질과 같게"), 재고는 cdc-inventory quality-postings. PR `cdc/quality-service`. PC1: 설정 정본·LAN 배치·게이트웨이 라우트(pc1-todo). 다음: CI-1(cdc-inventory productName·실사 번호, PC5 요청), 리콜·운영 제한.
- 2026-11-26: CI-1 cdc-inventory 0.17.0(Flyway V10) — productName(product internalName)·실사 sessionNumber C+6. PR `cdc/inventory-names-numbers`. PC1: product 주소 설정.
