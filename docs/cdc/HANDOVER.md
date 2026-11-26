# CDC 인수인계 (2026-11-24 작성, 2026-11-26 전면 갱신 — 다른 계정으로 넘기기 전)

CDC WMS 작업을 이어 받는 세션·사람이 **처음 읽는 문서**입니다. 무엇이 있고, 무엇을 기다리고, 다음에 무엇을 하는지를 모았습니다. 업무 원문·ADR·계약이 정본이고, 이 문서와 다르면 그쪽을 따릅니다. 0~4절은 2026-11-26 기준 현황이고, 5절은 날짜별 작업 기록입니다(그대로 이어 적으세요).

## 0. 영역·세션·작업 방식
- **영역 cdc**(2026-11-26 사용자 결정 — `docs/operations/pc-split.md` §1). cdc-*-service 코드는 cdc 세션만 고칩니다.
  - platform 주 세션(PC1 백엔드)은 게이트웨이 라우트·pmart-config·LAN 배치·배포만 합니다.
  - 일지는 아직 `docs/worklog/platform/` 에 있습니다. `cdc/` 폴더로 나눌지는 사용자 결정입니다.
- **작업 폴더**: PC 1 의 D 드라이브 클론 `D:\development\pmart-project\pmart-backend-cdc`. C 쪽 주 작업 폴더와 따로입니다.
- **클론 전용 규칙** `CLAUDE.local.md`(커밋 안 함): 반드시 먼저 읽으세요. 요약:
  - 사용자에게 보이는 글은 한국어로 씁니다.
  - 서브에이전트는 쓰지 않습니다.
  - 무거운 시험 전 남은 메모리를 확인하고, 4GB 아래면 미룹니다. 아래 "함정"을 보세요.
  - `docker compose stop` 만 씁니다(`down` 금지).
  - **모든 변경은 PR** 입니다. 문서 한 줄도 그렇습니다.
- **PR 흐름**
  1. `git fetch origin && git switch -c cdc/<주제> origin/main`
  2. 계약 → 코드 → 시험
  3. PR 전에 `git rebase origin/main` 하고 시험을 다시 돌립니다.
  4. push 한 뒤 `gh pr create --body-file`. 본문에는 "바꾼 것 / 실행한 시험 / 영향 영역 / 공용 표 갱신 필요"를 씁니다.
  5. **PR 백엔드** 세션이 검토·병합하고, PC1 이 설정·배포합니다.
  - 병합된 브랜치는 다시 쓰지 않습니다.
  - `service-requests/README.md` 공용 표는 직접 고치지 않습니다.
- **같이 일하는 세션**(SendMessage 로 연락. 동료 요청이고, 큰 일·데이터 변경은 사용자 확인 뒤에 합니다)
  - **PR 백엔드**: 검토·병합·LAN 배포. 병합은 대개 B 방식(새 커밋)이라 쌓아 둔 브랜치는 병합 뒤 main 에 **자기 커밋만** cherry-pick 해서 다시 올립니다.
  - **PC1 백엔드**(platform): 라우트·설정·배포. 다른 PC 요청을 넘겨줍니다. CDC 서비스 판 번호를 서로 알려 겹치지 않게 합니다.
  - **CDC 시뮬레이터**(`pmart-cdc-simulation` 세션, bridge): 칠곡 DC 배치·R 요청. 시뮬레이터 저장소는 읽기만 합니다(`C:\development\pmart-project\pmart-cdc-simulation`).
  - 회신 파일: `docs/handoff/simulator/cdc/2026-11-24-simulator-reply-r01-r06.md` 끝에 이어 붙입니다.
- **함정(이번에 실제로 겪은 것)**
  - 메모리 확인은 시험과 **다른 명령으로 먼저** 합니다. 한 줄에 묶으면 값을 보기 전에 시험이 돕니다. 이것으로 두 번 규칙을 어겼습니다.
  - 모자라면 `./gradlew.bat --stop` 으로 이 세션의 데몬을 내리면 2~4GB 가 돌아옵니다.
  - 커밋 전에 매번 `spotlessApply` 와 해당 서비스 `check` 를 돌립니다. 한 번 빼먹어 PR 백엔드에서 spotlessJavaCheck 실패가 났습니다(#516).
  - Bash heredoc 에 따옴표·복잡한 내용이 들어가면 "unexpected EOF" 가 납니다. 긴 파일은 Write 도구로, 고치기는 짧은 Python 스크립트(`python -I`)로 합니다.
  - 변경 요청 POST 는 공통 헤더 검사가 **Idempotency-Key 를 요구**합니다. 내부 조회용 POST 도 그렇습니다(아무 고유값).
  - 서비스 간 호출은 트랜잭션 밖에서 합니다. 같은 요청 키로 다시 오는 재생에서 앞 검사가 자기 몫 때문에 거절하지 않게 "이미 있으면 그것" 을 먼저 봅니다.
  - LAN 은 아직 **옛 위치 배치(dd3d185)** 입니다. 구역 글자를 코드에 박지 말고 cdc-location 조회(온도대·유형)로 고릅니다(#505 교훈).

## 1. 서비스 현황 (main·LAN 모두 같음, 2026-11-26)
| 서비스 | 판·Flyway | 포트 | 있는 것 |
|---|---|---|---|
| cdc-inventory | 0.19.0 · V10 | 8601 | 상품 투영·센터 취급·장부(현물·예약·LOT·Transit)·FEFO 할당/추가/해제·TOUT·내부 이동(PICK_MOVE 포함)·스냅샷·칸별 재고·재계수 대기·실사(CNT·승인 관문)·MFC 수령/정정 대사·운송 손실(입고 종결 뒤)·품질 전기(HOLD·RELEASE·DISPOSE, recall)·일일 대사(조회 때 계산)·LOT 추적·운영 제한 확인(새 할당)·상품 이름·실사 번호 |
| cdc-location | 0.7.0 · V4 | 8602 | 위치 마스터(칠곡 문법 R-22)·통로 피킹 방식·일괄 조회·공간 예약·피킹 선반 최소·목표 |
| cdc-outbound | 0.25.0 · V16 | 8603 | TRF 인수·Wave(묶음 피킹·DPS 통로)·PDA 다음 작업·피킹 실적·부족 재할당·버전 대체·미출발 취소·분류·검품·상차·출발 검증·매니페스트·출고대기 옮겨 적기·실사 부족/품질 보류/리콜 받기(RECALL_HOLD)·운영 제한 확인(출발)·DC 백오피스 조회·작업/Wave 조작·화면 번호·사건 기록 |
| cdc-quality | 0.4.0 · V4 | 8604 | 보류·해제·폐기(요청·결정·실행)·리콜 발령(RecallIssued·모든 CDC 보류)·운영 제한(걸기·풀기·내부 확인·사건) |
| cdc-inbound | 0.2.0 · V2 | 8605 | 적치 지시·자동 보충(최소 이하, 박스 입수 배수)·가상 설비 claim·결과(재고 이동·예약 소비)·보충 판단 조회 |

- 각 서비스의 자세한 규칙은 `services/<서비스>/CLAUDE.md`, 흐름은 `docs/business/flows/cdc-putaway-replenishment.md`(S7), 설계는 `docs/cdc/design/`, DC 백오피스 계획은 `docs/cdc/cdc-backoffice-plan.md` 에 있습니다.
- 시드 스크립트 `deploy/lan/seed/cdc_*.py`(center·locations·pick_methods·stock·test_workers): 위치 → 통로 방식 → 시작 재고 순입니다. **LAN 위치 전환은 사용자가 "최종 배치"라고 할 때** 합니다.
- **판 번호 규칙**: 다음 판은 main 의 계약 `version` + 1 입니다. 열린 PR 이 쓰는 판과 PC1 이 쓰는 판을 확인하고 서로 알립니다.

## 2. 다른 쪽을 기다리는 것
| 무엇 | 누가 | 요청서 | 막힌 것 |
|---|---|---|---|
| MFC 이관 수령 확정 사건 `MfcReceiptPosted`·`ReceiptCorrectionPosted` | PC3 (mfc-inventory) | `service-requests/mfc-inventory-service.md` IV-8 | 운송 중 재고가 실제로 줄지 않음(복구용 API 로만) |
| MFC 이관 입고 종결 알림 | PC3 (mfc-inbound) | `mfc-inbound-service.md` MI-5 | 운송 손실 승인은 종결 기록이 있어야 함(복구용 API 로만) |
| 승인 종류 `CDC_STOCK_ADJUSTMENT`(kind COUNT_ADJUSTMENT·TRANSPORT_LOSS·QUALITY_RELEASE·DISPOSAL, cdc-inventory·cdc-quality) | PC1 (approval) | `approval-service.md` AP-4(덧붙임 2개) | LAN 은 RECORD_ONLY — 결정마다 승인 번호를 **기록만** |
| 피킹 선반 최소·목표 **규칙**(예: 목표 = 칸 용량, 최소 = 30%) | 사용자(시뮬레이터 세션이 묻는 중) | 시뮬레이터 회신 | 자동 보충이 아무 칸도 돌지 않음 |
| 상품 물류 규격 **박스 입수**(units_per_box) | product 데이터(LAN 상품) | — | 없으면 그 SKU 보충 UNIT_MISSING |
| 시뮬레이터의 cdc-inbound 연결(claim·work-results), 보충 주체를 "가상 설비"로 | CDC 시뮬레이터(사용자 확인) | 시뮬레이터 회신 | 적치·보충 작업이 실제로 수행되지 않음 |
| LAN 위치 전환(R-22 글자·층 도크·R-23 좌표·**R-24 36,121칸**) | 사용자 "최종 배치" | 시뮬레이터 R-22~24 | LAN 은 옛 배치 |

## 3. 앞으로 할 일 (추천 순서 — 사용자와 정하세요)
1. **피킹 선반 최소·목표 시드**: 규칙이 정해지면 `deploy/lan/seed/cdc_pick_face_settings.py` 를 새로 만듭니다.
   - 호출: cdc-location `POST /internal/pick-face-settings`(낱개 단위).
   - 칸 용량은 시뮬레이터 값입니다: 선반 80·야채 레인 120·오토스토어 40·랙 1단 팔레트 유닛.
   - 함께 할 일: LAN 상품의 박스 입수 유무를 확인합니다.
2. **이상 알림**: 기준은 사용자 결정 "분명한 실패만"입니다.
   - 대상: 부족 재할당 FAILED·출발 검증 불일치/거절·리콜 막힘·미출발 취소 거절.
   - cdc-outbound 사건 기록(`GET /cdcs/{cdcId}/events`) 위에 표시나 별도 조회를 둡니다. 숫자 기준(오래된 작업)은 넣지 않습니다.
3. **FEFO 예외 이중 승인**(cdc-quality `/fefo-exceptions`): 할당 규칙(cdc-inventory FEFO)이 바뀝니다. 경로를 `/cdcs/{cdcId}/…` 로 옮기세요(MFC 와 겹침). 업무 규칙은 사용자에게 묻습니다.
4. **LOT 계보 화면**(cdc-quality `/lot-traces`): cdc-inventory `GET /internal/lot-traces` 가 이미 있으니 감싸면 됩니다. 경로를 옮기고 라우트를 PC1 에 요청합니다.
5. **cdc-inbound 다음**
   - 적치 후보 자동 산출: cdc-location `putaway-candidates`, 아직 501.
   - 출발 점유: 출발 칸 실물을 잡아 두는 방식 — 합의가 필요합니다.
   - 사건 발행: `PutawayTaskStateChanged`.
   - ASN·실수령·검수는 3단계입니다.
6. **운송(TMS)** 새 서비스(배차·출발·MFC 도착): 범위·서비스 경계는 사용자·`service-map.md` 를 먼저 봅니다.
7. **반품·폐기·작업자 생산성·KPI 보고**: PC1 이 넘긴 DC 백오피스 6번입니다.
8. **시뮬레이터로 끝까지 돌리기**: 인수 → 할당 → Wave → 피킹 → 출발 → MFC 수령. LAN 위치 전환 뒤가 좋습니다.
9. **작은 남은 것**
   - 일일 대사 새벽 배치·경보(시각 설정 결정 뒤)
   - 손실로 해소한 양이 나중에 발견될 때의 승인 정정
   - 리콜 발령 **뒤** 들어온 같은 LOT 막기(입고 수령이 생긴 뒤)
   - cdc-inventory `/internal/loss-postings`·`/internal/adjustments` 501(실사 밖 조정)
   - 품질 사건(HoldRequested 등) 발행
   - 품목군 최소 잔여일수(제조일 입력 없음)
   - 승인량 초과 할당 검사(replenishment 없음)
   - DC 백오피스 요청(PC5)이 오면 `docs/handoff/clients/cdc-backoffice/` 에 회신합니다.

## 4. 사용자 결정(확정 — 다시 묻지 마세요)
- **피킹**
  - PDA 다음 작업 자동 배정(MFC 기능의 DC 판).
  - 묶음 피킹 방식은 통로 설정값이고, DPS 는 통로별입니다.
  - 부족 칸은 새 할당에서 뺍니다. 피킹한 재고는 출고대기 자리로 옮겨 적습니다.
- **TRF**
  - 새 버전 대체는 피킹 시작 전까지만 됩니다.
  - 미출발 취소도 피킹 전에만 됩니다.
  - 부분 출고 승인·수동 할당은 **지금처럼** 둡니다(2026-11-26).
- **실사**
  - MFC 실사와 같은 방식이고, 승인은 MFC 와 같은 본사 기준입니다(10만 원·20개).
  - 예약보다 적어지면 조정은 하고, 나중 예약부터 부족으로 표시합니다.
- **수령·운송 손실**
  - 미판정도 받은 수량으로 보고 바로 뺍니다. 수령은 사건 + 복구용 API 로 받습니다.
  - 운송 손실은 MFC 입고 종결 확인 뒤에만 받고, 승인은 실사와 같은 본사 기준입니다.
- **품질**
  - 첫 범위는 재고 쪽 먼저였습니다.
  - 걸린 예약은 나중 예약부터 풀고 출고에 알립니다. 출고대기분은 일반 보류면 건드리지 않습니다.
  - cdc-quality 첫 범위는 보류·해제·폐기, 승인은 MFC 품질과 같게입니다.
- **리콜**
  - 출고대기분도 막습니다.
  - 본사 승인 번호 + 모든 CDC 입니다.
- **운영 제한**
  - 새 할당과 출발을 막습니다.
  - 거는·푸는 사람은 문서 그대로입니다(혼잡은 관리자, 안전은 현장 운영자·풀 때 본사, 본사 부과·품질은 본사 승인).
- **일일 대사**
  - 조회할 때 계산합니다.
  - 예약·운송 중을 대조합니다.
- **입고·적치·보충**
  - 적치 지시까지 만듭니다.
  - 최소·목표는 시뮬레이터 값으로 시드합니다(규칙은 대기).
  - 이동 단위는 박스 입수입니다.
- **이상 알림** 기준은 "분명한 실패만"입니다(아직 구현 전).
- **도면**: 칠곡 DC 결정 CDC-1~ 은 `docs/simulator/cdc/chilgok-dc.md` 에 있습니다. 구역 글자(R-22)는 상온 A~F·냉장 G·냉동 H 입니다.
- **남은 결정**
  - 위 2절 대기 항목
  - 일지 폴더(`docs/worklog/cdc/`) 분리 여부
  - 이상 알림을 붙일 곳(사건 기록 표시 vs 따로)

## 4-1. 먼저 읽을 것
1. 이 문서와 클론의 `CLAUDE.local.md`
2. `docs/architecture/service-map.md` §4 CDC WMS
3. `docs/business/WMS-business-logic.md`(정본 — `MFC-business-logic.md` 와 섞지 않음)
4. 각 `services/cdc-*-service/CLAUDE.md`
5. `docs/simulator/cdc/README.md`·`chilgok-dc.md`
6. `docs/operations/pc1-todo.md` 의 cdc 줄, `docs/architecture/service-requests/` 의 cdc 파일

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
- 2026-11-26: 리콜 1/3 — cdc-inventory 0.18.0(recall HOLD 출고대기까지·AllocationShortfall RECALL·LOT 추적). PR `cdc/recall-inventory`. 다음 2/3 cdc-outbound 0.23.0·V16(RECALL_HOLD — 재할당 안 함), 3/3 cdc-quality 0.3.0(리콜 발령·RecallIssued).
- 2026-11-26: 리콜 3/3 — cdc-quality 0.3.0(Flyway V3) `/cdc-recalls` 발령(본사 승인 번호)·RecallIssued·모든 CDC 보류. PR `cdc/recall-quality`. 배포 순서 inventory 0.18.0 → outbound 0.23.0 → quality 0.3.0(pc1-todo: 라우트·Debezium).
- 2026-11-26: 운영 제한 1/3 — cdc-quality 0.4.0(Flyway V4) 걸기·풀기·내부 확인·사건. PR `cdc/restriction-quality`(#513 다음). 2/3 cdc-inventory 0.19.0(새 할당 확인), 3/3 cdc-outbound 0.24.0(출발 확인).
- 2026-11-26: 운영 제한 2/3 — cdc-inventory 0.19.0 새 할당·추가 할당 앞 확인(막힘 409·확인 안 되면 503 보류). PR `cdc/restriction-inventory`(#512 다음). ⚠ 배포 전 `pmart.inventory.cdc-quality-base-url`.
- 2026-11-26: 운영 제한 2/3 — cdc-inventory 0.19.0 새 할당·추가 할당 앞 확인(막힘 409·확인 안 되면 503 보류). PR `cdc/restriction-inventory`(#512 다음). 주소 기본 `http://cdc-quality-service:8604`, cdc-quality 를 먼저 배포.
- 2026-11-26: 운영 제한 3/3 — cdc-outbound 0.24.0 출발 검증 TOUT 직전 확인(막힘 REJECTED·불명 UNKNOWN_RESULT). PR `cdc/restriction-outbound`(#514 다음). 배포 순서 quality 0.4.0 → inventory 0.19.0 → outbound 0.24.0.
- 2026-11-26: DC 백오피스 M-5 사건 기록 — cdc-outbound 0.25.0 `GET /cdcs/{cdcId}/events`(이미 남은 기록을 최신 순으로, 이상 알림 등급 없음). PR `cdc/event-log`(#517 다음). 화면 회신 `docs/handoff/clients/cdc-backoffice/2026-11-25-reply-monitoring-gaps.md` 덧붙임.
- 2026-11-26: 입고·적치·보충 1/3 — cdc-location 0.7.0(Flyway V4) 공간 예약·피킹 선반 최소·목표 설정. PR `cdc/location-space`. 다음 cdc-inbound 새 서비스(적치 지시·자동 보충·가상 설비 결과·다음 작업 받기), 그 뒤 시드(최소·목표 규칙은 시뮬레이터 세션이 사용자 확인 중).
- 2026-11-26: 입고·적치·보충 2/3 — **cdc-inbound-service 새로 만듦**(0.2.0, 포트 8605, Flyway V2) 적치 지시·자동 보충·가상 설비 claim·결과. 흐름 `docs/business/flows/cdc-putaway-replenishment.md`. PR `cdc/inbound-service`(#519 다음). 남은 것: 최소·목표 시드(시뮬레이터 세션이 사용자 확인 중)·박스 입수는 상품 물류 규격에 있어야 보충됨.
- 2026-11-26: 0~4절 전면 갱신(다른 계정으로 넘기기 전 — 사용자 요청). PR `cdc/handover-rewrite`.
- 2026-11-26: PC5 DC 백오피스 요청(pmart-clients #579) 회신 `docs/handoff/clients/cdc-backoffice/2026-11-26-reply-backend-missing-screens.md` — IB·RP 는 cdc-inbound 0.2.0 에 이미 있음(목록 cursor·보충은 kind), LG-1 다음 PR, TR 뒤로(사용자 "응"). PR `cdc/backoffice-reply-ib-rp`.
- 2026-11-26: 재고 이동 이력 cdc-inventory 0.20.0(Flyway V11, DC 백오피스 LG-1 — #579) `GET /cdcs/{cdcId}/stock-movements`, 원결과 처리자 actorId, PostingKind 실제 값으로(옛 `/internal/commits` 500 고침). PR `cdc/stock-movements`. PC1: 게이트웨이 라우트 `/api/v1/cdcs/*/stock-movements` → cdc-inventory. 다음: TR 운송은 사용자 확인 뒤.
- 2026-11-26: **TMS 1단계 설계·계약** — 사용자 "시작해 — TMS md 참고"·"planning·execution·fleet"·"TMS 2.3 에 맞춰". 설계 `docs/cdc/design/2026-11-26-tms-first-stage.md`, 계약 fleet·transport-planning·transport-execution 0.2.0(옛 0.1.0 대체)·cdc-outbound 0.26.0(transport-feed), 상태표 갱신, 허용 목록. PR `cdc/tms-design`. 다음: fleet → cdc-outbound feed → planning → execution 구현(포트 8703·8701·8702).
- 2026-11-26: **fleet-service 새로 만듦**(0.2.0, 포트 8703, Flyway V2) — 운송사·차량·기사(표시 이름만)·점유([시작,끝) 차량·기사 따로, 목적당 하나). 관리 역할은 `CDC_SUPERVISOR`(staff-roles 정본 — ⚠ cdc-inbound·cdc-quality 코드의 `CDC_MANAGER` 는 정본에 없는 역할, 사용자에게 보고). 시드 `tms_fleet.py`. PR `cdc/fleet-service`(#526 다음). PC1: pc1-todo.
- 2026-11-26: **transport-planning-service 새로 만듦**(0.2.0, 포트 8701, Flyway V2) — 노선·정규차, 배차 생성(feed → 검증 → 노선 × 온도대 지시서), 요청서 옮기기, 계획완료(fleet 점유 → execution 운행, 불명이면 CONFIRMING 이어 가기), 결행·취소, 운행 상태 사본. 시드 `tms_routes.py`. PR `cdc/transport-planning`(#527 fleet 다음). 다음: transport-execution.
