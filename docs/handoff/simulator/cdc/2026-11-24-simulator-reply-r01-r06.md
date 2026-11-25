# 회신 — CDC 시뮬레이터 R-01~R-06 (2026-11-24)

보낸 영역: cdc(PC 1 D 클론 세션). 받은 요청: 세션 "cdc 시뮬레이션", `pmart-cdc-simulation` 커밋 bb04f87 의 `docs/backend-requests.md`. 결정 근거·전체 표는 `docs/simulator/cdc/chilgok-dc.md`.

| R | 회신 |
|---|---|
| R-01 센터 코드 | **`CDC01`**(CDC-1). 지금 쓰는 임시값 그대로. center-master 등록은 PC 1 에 요청함(CM-3) — 등록 전까지 백엔드 센터 조회에는 없다. |
| R-02 온도 글자·셋째 구간 | ~~온도대 글자 A 상온·C 냉장·F 냉동~~ → **R-08 로 고침: 구역 글자 A 냉동·B 냉장·C/D/E 상온·F 상온 인기·G/H 생활용품·기타**(CDC-4, MFC 시뮬레이터 기준). 셋째 구간 = **층 번호 5·6·7**, 넷째 = 구역 안 통로 — 확정(CDC-3). 층 배치는 CDC-2 — **R-09 로 고침(아래)**. |
| R-03 비랙 위치 | 같은 7구간, 단 `0`·슬롯 `1`. **R-07 로 고침: 통로 90 = DOCK 은 지하 접안(층 칸 `B1`), 91 = STAGING 출고대기, 92 = STAGING 층 승강기 반출 문**(CDC-5). 구역 칸은 대표 글자 냉동 A·냉장 B·상온 C. 예 `CDC01-B-B1-90-03-0-1`, `CDC01-B-6-91-02-0-1`, `CDC01-C-7-92-01-0-1`. 출고대기 01~05 는 MFC 다섯 곳에 기본 배정. |
| R-04 공급 대상 | **MFC 다섯 곳 모두**: `MFC-BJG-01`(1호점 부산진구 — 오늘 R-46 으로 `MFC-SEED-01` 에서 바뀜, 옛 ID 를 쓰지 마세요), `MFC-BNG-01`(부산남구), `MFC-MOKPO-01`(목포), `MFC-SEJ-01`(세종), `MFC-CHC-01`(춘천). 지역 묶음은 부산권(BJG·BNG)·호남권(MOKPO)·충청권(SEJ)·강원권(CHC) — 대응표 정본은 cdc-outbound(CDC-6). |
| R-05 층별 도면 | 받으면 이쪽에서 `docs/simulator/cdc/layout/` 에 PR 로 넣는다. 도면이 CDC-2 표의 평수 나눔과 다르면 도면을 따르고 합계 8,600평만 지킨다. |
| R-06 CORS 5182 | 게이트웨이는 PC1 백엔드 세션 몫. 실서버 모드를 붙일 때 알려 주시면 그때 요청한다(지금은 CDC 서비스가 게이트웨이에 없어 붙일 API 도 없음). |

덧붙임
- 현장 근거 두 페이지(알바몬 인천 비마트 후기·우아한청년들 인천기지 글)는 백엔드도 원문을 확인해 `chilgok-dc.md` §2 에 요약했다. 작업 이름은 후기대로 **FC(지역 바코드 피킹)·분배·라인(DAS)** 을 쓰고, 설비는 휠소터·DAS·DPS·오토스토어(가상 설비).
- 받은 그림 중 "부산 중앙물류기지" 렌더는 이름·실제 회사 로고가 들어 있다 — 화면에는 칠곡 DC 이름만 쓰고 실제 회사 브랜드·로고는 넣지 않는다.
- Wave·지역·MOV 업무 근거는 `docs/business/WMS-dc-wave-business-logic.md`(사용자 2026-11-24 근거로 채택).

## 추가 회신 — R-07·R-08 (2026-11-24)

| R | 회신 |
|---|---|
| R-07 지하 도크·승강기 | "차량은 지하, 승강기로 올림"(사용자, 시뮬레이터 세션) 반영. 다만 **지하 접안 자리도 위치 코드를 만든다** — 통로 90 DOCK 을 지하(층 칸 `B1`)로 옮기고, 층의 승강기 반출 문은 **92(STAGING)** 로 따로 둔다. 까닭: TMS 도크 예약(`cdc-location` `/internal/dock-reservations`)과 가상 상차 LOADED 대조(WMS CDC-F-021)가 차량이 실제로 대는 자리를 가리켜야 한다. 지하는 8,600평 밖이어도 위치 마스터에는 있다(면적과 위치 마스터는 별개). 층의 90 을 승강기 문으로 읽는 해석은 쓰지 않는다. |
| R-08 구역 글자 | 반영. 사용자 정의 **A 냉동·B 냉장·C/D/E 상온·F 상온 인기상품·G/H 생활용품·기타**(MFC 업무 원문 2-1 의 E·G·H 뜻과는 조금 다르며 CDC 는 사용자 정의를 따름). 위치 코드 둘째 구간 = 구역 글자. 다만 층 배치의 통로는 C 로 몰지 않고 구역을 나눴다: **5층 C 01~10·D 01~10, 6층 B 01~18, 7층 E·F·G·H 각 01~03 + A 01~12**(통로 번호는 구역마다 01 부터). 제안하신 `CDC01-C-5-12-24-3-1`(C 12번 통로)는 C 가 01~10 이라 없는 통로 — 예 `CDC01-C-5-07-24-3-1`, `CDC01-A-7-05-10-2-2` 는 맞음. |

## 공지 — 층 배치 바뀜 (R-09, 2026-11-24 사용자 확정)
| 층 | 구역·통로 | 설비 |
|---|---|---|
| 5층 | 상온·인기상품·생활용품 E·F·G·H 각 01~03 + 냉동 A 01~12 | E~H 오토스토어, A DPS |
| 6층 | 냉장 B 01~18 | 야채 분배, DAS 라인 |
| 7층 | 상온 C 01~10 · D 01~10(무거운 상자 존) | 박스 휠소터 |

- 앞 R-08 회신의 5층 C·D / 7층 E~H·A 를 **서로 바꾼다**(5층 ↔ 7층). 구역 글자·통로 번호·비랙 규칙(90 지하 접안·91 출고대기·92 승강기 문)은 그대로.
- 예: `CDC01-C-7-07-24-3-1`(7층 C), `CDC01-A-5-05-10-2-2`(5층 냉동), `CDC01-B-6-91-02-0-1`(6층 출고대기), `CDC01-C-7-92-01-0-1`(7층 승강기 문).
- 오토스토어(E~H) 칸을 7구간에 어떻게 대응할지는 층별 도면(R-05)을 보내 줄 때 같이 정한다.

## 회신 — R-10 층 배치 다시 바뀜 (2026-11-24)
해석 그대로 반영(CDC-2 고침 2). 5층 G·H 자리(6통로) ↔ 7층 C·D 자리(20통로), 자리·통로 수 그대로.

| 층 | 구역·통로 | 설비 |
|---|---|---|
| 5층 | 무거운 상온 C·D 각 01~03 + 상온·인기상품 E·F 각 01~03 + 냉동 A 01~12 | C·D 1차 고속분류기, E·F 오토스토어(R-09 그대로), A DPS |
| 6층 | 냉장 B 01~18 | 야채 분배, DAS 라인 |
| 7층 | 생활용품·기타 G·H 각 01~10 | 비식재 오토스토어 |

- 예: `CDC01-C-5-02-24-3-1`, `CDC01-G-7-07-24-3-1`, `CDC01-G-7-92-01-0-1`. 7층은 상온만 있어 비랙 위치의 구역 칸에 `G` 를 쓴다.
- 오토스토어 칸의 7구간 대응은 7층 G·H(와 5층 E·F) 일 — 도면 R-05 때.


## 회신 — R-11 작업 배정·착수 + 시뮬레이터가 정한 값 (2026-11-25)
- **R-11**: 업무 원문 CDC-F-017 상세안 5번("동시 두 피커의 수락은 한 유효 담당만 성공, 나머지에는 최신 상태 반환", "수락/착수와 실피킹 완료는 별개")에 맞춰 cdc-outbound 를 만들 때 계약에 **`POST /tasks/{taskId}/acceptances`(X-Expected-Version, 진 쪽은 409 + 최신 Task)** 와 **`POST /tasks/{taskId}/starts`(→ IN_PROGRESS)** 를 넣는다. 다만 **배정 방식(관리자 배정 / 작업자가 "다음 작업 받기")** 은 원문 4번이 "추가 승인 대상"이라 사용자 결정을 기다린다 — 정해질 때까지 MOCK 의 "첫 실적 작업자 = 담당, 다른 작업자 409 INVALID_STATE" 는 임시로 괜찮다.
- **안전량 S = 0**: 실서버는 미설정을 0 으로 대신하지 않는다(가용·할당이 422 CONFIG_REQUIRED). 시드할 때 cdc-inventory 0.2.0 의 새 경로 `POST /internal/safety-stocks`(SKU 마다 `safetyQty: 0`)를 같이 불러 주세요.
- **시작 재고**: `POST /internal/initial-balances`(센터·SKU·LOT 당 1회, 2,000줄까지). `lotIdentification: VERIFIED` 를 꼭 넣어 주세요 — 비우면 UNVERIFIED 라 할당되지 않는다. SKU 는 상품 투영이 있어야 하고(없으면 404), 할당 전에 센터 취급을 켜야 한다(`/cdcs/CDC01/sku-handlings`).
- **피킹 = 랙 → 91 MOVE**: 재고 쪽 이동 전기(`/internal/move-postings`)는 아직 501. 그동안 할당·예약은 랙 위치에 남으므로 TOUT(`/internal/departure-postings`) 줄의 `locationCode` 는 비우거나 랙 위치를 쓴다. 목적 MFC 는 새 칸 `toMfcId` 로 보낸다(TransferDeparted.toMfc → mfc-inbound 입고 예정).
- **출발 뒤 잔량 RESIDUAL_CLOSED 해제**: 계약과 같다 — 출발 전기는 남은 할당을 풀지 않는다.
- createWave PLANNED·Task = TRF × 층·구역·PARTIAL = 부족을 남기고 끝남: cdc-outbound 설계 때 반영 여부를 다시 본다(지금 백엔드에 서비스 없음).

## 회신 — R-12 랙 규격·4단 (2026-11-25)
반영(CDC-2 랙 줄). 모든 랙 4단(6m × 2.6m × 1m, 단 2m·5,200L·2톤, 팔레트 1.1 × 1.1m 단당 2개). 단 구간은 1~4(1 피킹면·2 보충 대기·3~4 리저브). 팔레트 위치 22,400 → **19,840**(상온 10,240·냉장 5,760·냉동 3,840). 통로·베이·슬롯·층 배치(R-10)는 그대로. 예 `CDC01-C-5-02-24-3-1` 은 그대로 맞고, 단 `5` 가 든 코드는 없다. R-11 회신은 cdc-inventory PR(#439)의 이 문서 "회신 — R-11" 절에 있다.

## 추가 회신 — R-11 배정 방식 확정 (2026-11-25)
사용자 결정: **피킹은 다음 작업을 PDA 가 자동 배정**한다(MFC 의 "다음 작업 받기"를 DC 판으로). cdc-outbound 계약 0.2.0 에 넣었다(서비스는 아직 없음).
- `POST /cdcs/{cdcId}/workers/{workerId}/next-tasks` — 작업자 본인이 부른다. 들고 있는 작업(ACCEPTED·IN_PROGRESS)이 있으면 200 으로 그것, 없으면 RELEASED Wave 의 담당 없는 CREATED 작업 하나를 배정해 201(state **ACCEPTED** — 받기 = 수락). 순서 TRF 기한 이른 순 → 만든 순. 본문 `kind`·`zone`·`floor` 로 거르기. 줄 작업이 없으면 409 NO_TASK_AVAILABLE. 동시에 받아도 한 작업은 한 사람에게만.
- `POST /tasks/{taskId}/starts` — 담당만 ACCEPTED → IN_PROGRESS(다른 작업자 409 NOT_ASSIGNEE). pick-results 도 담당의 IN_PROGRESS 작업에만.
- 앞 회신의 `/acceptances` 경로는 두지 않는다(받기가 수락). MOCK 의 "첫 실적 작업자 = 담당"은 이 두 경로로 바꿔 주세요. 근무·업무 자격·온도대 출입 검사는 CDC 작업자 정본이 생길 때까지 하지 않는다.

## 회신 — R-13 피킹 선반 (2026-11-25)
반영(chilgok-dc.md CDC-7, cdc-location 0.2.0 코드 규칙). 제안하신 범위·타입 그대로다.
- 통로 01~60 = 팔레트 랙: 단 1~4·슬롯 1~2, **모두 RESERVE**(QUARANTINE·TEMPORARY 도 허용). 앞서 CDC-2 의 "1 피킹면·2 보충 대기"는 지웠다.
- 통로 61~89 = 피킹 선반: 베이 = 유닛(01~), 단 1~5, 칸 1~3, **PICK_FACE**. 예 `CDC01-C-5-61-03-2-1` 은 맞다. 유닛 수는 백엔드가 정하지 않는다(베이 01 이상만 검사).
- cdc-location 에 일괄 등록 `POST /internal/location-batches`(2,000행씩, 전부 또는 전무, `activate`)가 생긴다 — 시뮬레이터 배치(랙·선반·출고대기·승강기 문·지하 접안)를 이 경로로 넣을 수 있게, 실서버 모드 전에 배치를 위치 목록(JSON)으로 내보내 주시면 시드 스크립트를 만든다. 피킹면 최소·목표량은 기본값 없이 비워 둔다(정하면 같이 넣는다).
- 할당 "같은 LOT 이면 선반 먼저"·"팔레트 예약은 선반 보충 뒤 Wave 배포"·move-postings 는 다음 cdc-inventory 작업에서 본다. 지금 cdc-inventory 할당은 위치 유형을 모른다(위치는 문자열) — 선반 우선을 하려면 cdc-location 조회나 위치 유형 사본이 필요하다.

### R-13 덧붙임 — 위치 목록 받음 (2026-11-25)
`docs/layout/chilgok-locations.json` @ 6eba59a 를 받아 화면 좌표만 뺀 표로 `docs/simulator/cdc/layout/chilgok-locations.json` 에 두었다. 23,076칸 모두 cdc-location 0.2.0 규칙을 통과한다(중복 0, 실제 서버로 12묶음 넣는 시험 `ChilgokLayoutIT`). 넣기 스크립트 `deploy/lan/seed/cdc_locations.py`(묶음마다 정해진 멱등 키 — 다시 돌려도 안전). 실제 LAN 에 넣는 것은 cdc-location 배치 뒤. 배치를 다시 내보내면 알려 주세요 — 사본과 시험을 같이 바꾼다.

## 공지 — TMS 흐름 맞춤 (2026-11-25, 사용자 "현재 TMS … 다른 부분은 바꿔")
근거 `docs/business/TMS-business-logic.md` 2.1. chilgok-dc.md CDC-6·8.
- **공급 대상 MFC 26곳**(충청·강원 9·경상·전라 17). 백엔드에 있는 건 5곳(BJG·BNG·MOKPO·SEJ·CHC)뿐 — 나머지 21곳은 ID·주소가 정해지면 더한다(지어내지 않음). 출고대기 91 은 01~05 지금 5곳, 06~26 나머지, 27~30 여분.
- 권역·계획 그룹은 TMS §4.2 그대로(경상·전라: 대구·경산·부산·울산·김해·창원·전주·광주·목포 / 충청·강원: 대전·세종·청주·천안·강원). 앞서 CDC-6 의 부산권·호남권·충청권·강원권 지역 묶음은 지웠다 — 피킹 지역 대응표는 cdc-outbound 에서 계획 그룹이나 TMS 고정 노선(노선당 최대 3곳)을 후보로 정한다. MOCK 이 4지역을 쓰고 있으면 계획 그룹으로 바꿔 주세요.
- **하루 시간표**: 13:00~ MFC 별 분류(Wave) → **21:30 WMS 출고 작업 완료** → **22:00 PDA R/T 카운팅 완료**(박스 스캔 → R/T, 온도대별) → 22:00~22:30 TMS 배차 → 22:30~ 방문 역순 상차(멀티드롭 최대 3곳) → **23:00~ 첫 출차**(이때만 TOUT). 시각은 설정값으로.
- **운송 단위**: 적재 = 롤테이너(R/T), 수량 검증 = 박스 바코드. 간선에 팔레트는 쓰지 않는다 — 출고대기·상차 화면은 R/T 로.
- 회수(빈 R/T·회수품 MFC → DC)는 범위 밖(출고·피킹·상차만).

## 공지 — 되돌림: 공급 5곳·4지역, TMS 노션이 기준 (2026-11-25)
사용자: "일단 지역은 4지역 묶음(부산권·호남권·충청권·강원권) 이렇게 하고 지금은 일단 공급대상 MFC 5곳으로 해", "TMS 문서 오늘 준 게 기준이야".
- 바로 앞 공지(#447)의 **26곳·TMS 계획 그룹은 되돌린다** — 공급 대상은 지금 MFC 5곳, 지역은 부산권(BJG·BNG)·호남권(MOKPO)·충청권(SEJ)·강원권(CHC). 69d9f74 의 계획 그룹 반영을 4지역으로 되돌려 주세요. 출고대기 91 은 01~05, 06~30 여분.
- 시간표(CDC-8)·R/T 운송 단위는 그대로. TMS 정본을 노션에 맞춰 2.2 로 고쳤다: MFC 도착 마지막 07:30·하차·인계 완료 08:00(MFC 08:00 영업, 고객 주문 08:00~24:00).

## 공지 — cdc-inventory 내부 이동 전기 (2026-11-25, 계약 0.3.0)
R-13 에서 말씀하신 "팔레트 예약은 선반 보충(MOV) 뒤 Wave 배포"에 필요한 `POST /internal/move-postings` 를 만들었다(병합 전 PR).
- 줄: sku·lot·fromLocationCode·toLocationCode·qty + 선택 `qualityState`(비우면 RELEASED)·`allocationId`. moveKind PUTAWAY·REPLENISHMENT_MOVE·RELOCATION, sourceRef = MOV 번호.
- `allocationId` 를 주면 그 할당이 원위치(랙)에 잡은 예약을 현물과 함께 도착 위치(선반)로 옮긴다 — 가용·예약 합은 그대로, `GET /internal/allocations/{id}` 의 lots 위치가 선반으로 바뀌고, 출발 전기(TOUT)는 선반에서 빠진다(줄 locationCode 를 비우면 할당이 잡은 위치).
- `allocationId` 가 없으면 예약 안 된 현물만 옮긴다(부족하면 409 INSUFFICIENT_STOCK). 같은 위치·격리 + 할당은 400.
- 위치 상태(BLOCKED 등)는 보지 않는다 — cdc-location 공간 선점(아직 501)이 맡는다. "같은 LOT 이면 선반 먼저" 할당은 아직 없다(재고는 위치 유형을 모름).

## 공지 — cdc-outbound 첫 판 0.3.0 (2026-11-25)
- **Task 경로가 바뀐다**: `/tasks…` → **`/cdcs/{cdcId}/tasks`**(목록)·`/cdcs/{cdcId}/tasks/{taskId}`·`…/starts`·`…/pick-results`(게이트웨이 `/api/v1/tasks/**` 가 MFC 쪽이라). 다음 작업 받기는 그대로 `/cdcs/{cdcId}/workers/{workerId}/next-tasks`.
- 구현된 것: `POST /internal/transfer-intakes`(TRF 인수 → cdc-inventory 할당, 결과는 `GET /internal/transfer-intakes/{trfId}`), `POST /waves`(PLANNED)·`GET /waves/{id}`·`POST /waves/{id}/releases`(할당의 **지금** LOT·위치로 TRF × 구역 × 층 PICK Task — 보충 MOV 로 선반에 옮긴 예약은 선반 위치), 다음 작업 받기·착수·피킹 실적.
- 아직 501: 분류·검품·상차·출발 검증(→ TOUT)·매니페스트 — 다음 PR. R-15(출발 source)는 그때 정해 회신한다.
## 회신 — R-16 실서버 모드 준비·회수·R-15 (2026-11-25)
- **내부 경로**(사용자 결정 "LAN 서비스 직접 호출", chilgok-dc.md **CDC-9**): 시뮬레이터가 보충 승인·TMS 역할로 부르는 `/internal/*`(TRF 인수·출발 검증·매니페스트·출발 준비·재고 조회·보충 MOV)은 **게이트웨이를 거치지 않고 LAN 서비스 주소로 직접**, 토큰 없이 `X-Source-System: pmart-cdc-simulation`. 게이트웨이로 `transfer-intakes`·`outbound-readiness`·`shipment-manifests` 를 열지 않는다. 직원 화면 경로(Wave·Task·다음 작업 받기·위치)는 게이트웨이 + 직원 토큰.
- **R-16 ①~④**(계정·게이트웨이·CORS·LAN 배치)는 PC1 백엔드 몫이라 `docs/operations/pc1-todo.md` 에 넣었다. 계정 이름 제안: 운영자 `claude-cdc-sup`, 작업자 `cdcw-fc-01~10`·`cdcw-dl-01~05`·`cdcw-qc-01~05`·`cdcw-ld-01~05`·`cdcw-fk-01~03`(센터 `CDC01`) — 만들어지면 확정 이름을 알린다.
- **회수**: 사용자 "TMS 문서에서도 뺌" — TMS 정본 2.3 D-11 을 회수 제외로 고쳤다(12장은 참고로만). 운행은 칠곡 복귀까지.
- **R-15**: cdc-outbound 출발 검증을 만드는 다음 PR 에서 SIMULATED 처리와 필드 대응(TruckActuallyDeparted → validateDeparture)을 정해 회신한다. transport-execution 계약 쪽 설명은 TMS 담당과 함께 본다.

## 회신 — R-15 출발 source·필드 대응, cdc-outbound 0.4.0 (2026-11-25)
- **SIMULATED 도 TOUT 한다**: cdc-outbound `POST /internal/departure-validations` 는 source 가 SIMULATED 여도 일치 TRF 를 cdc-inventory 출발 전기(TOUT)로 보내고, 재고·Transit·`TransferDeparted` 에 SIMULATED 표시가 남는다(칠곡 CDC-9 시뮬레이터 실서버 모드). transport-execution 계약의 "SIMULATED 는 TOUT 대상 아님" 설명은 TMS 쪽 — TMS 담당이 맞출 때까지 시뮬레이터는 출발을 SIMULATED 로 cdc-outbound 에 바로 보내면 된다(LAN 내부 경로).
- **TruckActuallyDeparted → validateDeparture**: departureId·tripId·vehicleId·departedAt·source·evidenceRef 그대로, trfCompositions[].trfId 그대로, **toMfc → mfcId, lines[].lpn → lpns(중복 없이), sealIds → sealNos** — 추측하신 대로다(계약 0.4.0 설명에 적음).
- **0.4.0 에 생긴 것**: `POST /sorting-results`(BOX·PLT·**RT 롤테이너** — 수량은 BOX 만, RT 는 contents 비우고 BOX 의 parentLpn 으로), `POST /inspection-results`(RT 를 검품하면 안의 BOX 모두), `POST /loading-results`(RT 를 실으면 안의 BOX, PASSED 만, 운행 하나), 출발 검증(BOX 대조 → 일치 TRF TOUT → 잔여 RESIDUAL_CLOSED 해제는 cdc-outbound 가 함 → 매니페스트·`TransferShipmentManifestIssued`), `GET /internal/shipment-manifests/{trfId}`.
- **MOCK 에서 바꿀 것**: 출발 뒤 RESIDUAL_CLOSED 해제를 시뮬레이터가 따로 부르지 않아도 된다(cdc-outbound 가 부름 — 실서버 모드). 같은 출발을 다시 보내면 불명이던 TOUT 만 다시 시도한다.

## 공지 — cdc-location 경로·서비스 주소 (2026-11-25)
- **cdc-location 공개 경로가 바뀐다(0.3.0)**: `/locations…` → **`/cdcs/{cdcId}/locations`**·`/cdcs/{cdcId}/locations/{locationCode}`·`…/state-changes`(게이트웨이 `/api/v1/locations/**` 가 mfc-location 이라 — PC1 R-16 진행 중 지적). 목록의 `centerId` 조회 조건은 경로로, 등록 본문 centerId 는 경로와 같아야 한다. `/internal/location-batches` 는 그대로.
- **LAN 서비스 주소(PC1 백엔드 안내)**: cdc-inventory `http://192.168.0.222:8601`, cdc-location `http://192.168.0.222:8602`(배포 뒤), cdc-outbound 같은 PC `:8603`(배치 뒤). 서비스 직접 호출에는 CORS 가 없다 — 브라우저(5182)가 아니라 개발 서버 프록시로 부른다(지금 하시는 방식 그대로, `PMART_CDC_HOST=192.168.0.222`).

## 요청 B-1 (백엔드 → 시뮬레이터) — 피킹 선반 10,900칸으로 늘리기 (2026-11-25)
사용자 결정: CDC 는 **MFC 와 같은 SKU 번호**를 쓰고, 피킹 선반을 **MFC 설계 10,900 SKU 에 맞춰 칸 하나 = SKU 하나로 늘린다**(chilgok-dc.md CDC-7).
- 필요한 선반 칸(이상): **냉동 A 2,180 · 냉장 B 3,270 · 상온 C~H 합 5,450**(지금 480 · 720 · 1,920 = 3,120).
- 위치 코드 규칙은 그대로 — 구역마다 통로 61~89(29통로)·베이 01~·단 1~5·칸 1~3, PICK_FACE. 상온 C~H 사이 나눔·층 안 자리·랙을 줄일지는 시뮬레이터가 정한다(8,600평·층 배치 R-10 안에서). 29통로로 모자라면 알려 달라(문법을 넓힌다).
- 끝나면 `npm run export:locations` 로 위치 목록을 다시 내보내 커밋을 알려 달라 — 백엔드 사본·`ChilgokLayoutIT`·시드를 바꾼다.
- 시작 재고: 실제 LAN SKU(기록상 504개, MFC 와 같음)만 칸에 들어간다(백엔드 시드 — 다음 백엔드 PR). 실서버 모드에서 칸별 재고는 cdc-inventory 조회로 읽게 한다(다음 백엔드 PR 에서 조회 경로를 알린다).

## 공지 — 칸별 재고 조회 경로 (cdc-inventory 0.4.0, 2026-11-25)
- `GET /internal/snapshots?cdcId=CDC01`(내부 — LAN 직접, CDC-9). sku 를 주면 그 SKU 만, 비우면 현물 행이 있는 SKU 전부(오름차순).
- 항목 = 가용 조회와 같은 모양 + **`lots[].locations[]`**(locationCode·qualityState·onHand·reserved) — 칸별로 무슨 SKU·LOT 가 몇 개, 얼마나 예약됐는지.
- 취급 OFF·안전량 미설정이어도 거절하지 않는다(`handlingActive`, `safety.state` NOT_SET 이면 `available` null). 매번 지금 장부(보관하는 스냅샷 없음).
- 주기 조회 권장: 작업에 나온 SKU 만 `sku=` 로, 전체는 드물게(시작·재동기화 때) — MFC SIM-5 와 같은 원칙.
### B-1 회신 받음 — 위치 목록 dd3d185 (2026-11-25)
32,436칸(선반 12,480 = 냉동 2,400·냉장 3,360·상온 6,720) 모두 cdc-location 규칙 통과(중복 0). 백엔드 사본·`ChilgokLayoutIT`·시드 안내를 바꿨다.

### R-17 회신 — 층 배치 다시(eddbcb8, 26,156칸) 받지 않음 (2026-11-25)
- 사용자 결정(2026-11-25): **팔레트 랙 위치 18,000칸 이상**("18000칸 이상으로 해야 해"). 근거 CDC-2 의 참고 그림(CDC-03·04) 팔레트 목표 18,000~22,000.
- eddbcb8 은 팔레트 13,440(통로 76 → 40, 베이 01~48, 칸당 용량 197 → 288)이라 목표보다 적다 — 백엔드 사본·LAN 은 dd3d185(32,436칸, 팔레트 19,840) 그대로.
- 확인한 것: 26,156칸 모양 이상 없음. 다만 랙 베이 41~48 은 지금 규칙(01~40) 밖, 옛 목록 대비 사라지는 칸 12,720(LAN 시작 재고 596줄·107,332개가 그 랙 칸에 있음).
- 부탁: 문서 면적 고정·랙 묶음은 살리고 팔레트 18,000칸 이상으로 다시 — 무엇을 바꿀지(통로 폭·선반 블록·단 수·묶음 크기)는 사용자 결정, 랙 규격(R-12)·통로 3.5m 가정을 바꾸면 먼저 사용자에게. 새 목록이 오면 LAN 전환 방법(초기화 후 다시 넣기 등)을 사용자와 정한다.

### R-17 2차 받음 — 6616cc1, 31,880칸 (2026-11-25)
- 팔레트 19,584(하한 18,000 이상)·선반 12,180·STAGING 102·DOCK 14, 31,880칸 모두 cdc-location 규칙 통과(중복 0, 온도대·유형 맞음), 용량 합 460만.
- 랙 베이 41~48(3,264칸)을 위해 규칙을 01~48 로 넓혔다(cdc-location 0.4.2). 백엔드 사본·`ChilgokLayoutIT`·시드 안내를 바꿨다.
- LAN 은 아직 dd3d185 — 옛 칸 6,760 이 사라지고(그 랙 칸에 시작 재고 480줄) 남는 칸 16,320 의 용량이 바뀐다. 전환 방법은 사용자 결정 뒤 알린다.

### 알림 — 출발 검증 미설명 수량 막기 (cdc-outbound 0.8.0, 2026-11-25)
- 사용자 결정(묶음 피킹 설계 §7 결정 4, WMS CDC-F-017): TRF 의 실피킹이 그 TRF BOX 에 담은 수량보다 많으면(SKU 별) 출발 검증에서 그 TRF 는 **MISMATCHED**, 불일치 `kind = UNEXPLAINED_QTY`(lpn 비움, detail = 실피킹·분류·차이). TOUT 하지 않고 `OutboundDiscrepancyRaised`(MISSING)를 낸다.
- 지금 단건 피킹에도 적용된다. 집은 것을 다 담지 않고 출발시키면 그 TRF 는 출발 처리되지 않는다(불일치 TRF 는 다시 출발 검증할 수 없음 — 조사 몫, 0.4.0 그대로).
- 부탁: 출발 전 `GET /internal/outbound-readiness?trfId` 의 `blockers` 에 `UNSORTED_QTY` 가 없는지 본다. 화면 mismatches 목록에 새 kind 가 보일 수 있다(enum 값 추가).

### R-19 받음 — 위치 37,306칸 (d0fdaff, 2026-11-25)
- 사용자 확인(2026-11-25 "그대로 진행"): cdc-location 0.5.1 — 랙 1단 PICK_FACE, 통로 93 바닥 레인(단 1·칸 1~), 7층 H 61~62 오토스토어(단 1~16·칸 1~2). 94~99 는 여전히 비움.
- 37,306칸 모두 새 규칙 통과(`ChilgokLayoutIT`), 백엔드 사본 바꿈. 팔레트 자리 18,048(RESERVE 15,792 + 랙 1단 2,256) — 하한 18,000 지킴.
- 바닥 하중 2,500kg/㎡·오토스토어 약 2,000kg/㎡ 는 시뮬레이터 값 그대로(사용자) — chilgok-dc.md 에 `[가정]` 으로 적음.
- LAN 반영은 사용자가 최종 배치라 할 때(그 전엔 시드 --apply 안 함).
### 알림 — 묶음 피킹 2단계 (cdc-outbound 0.9.0·cdc-location 0.5.0, 2026-11-25)
- 설계 `docs/cdc/design/2026-11-25-batch-picking.md`(사용자 결정 §7). Wave 릴리스가 위치 통로 방식(cdc-location `aisle-pick-methods` → 조회 pickMethod)으로 작업을 만든다:
  DPS·방식 없음 = DISCRETE(지금과 같음), BOX_PICK·FC·DISTRIBUTION = REGION_BATCH(지역 카트 — 지역 × 방식 × 구역 × 층), LINE = SKU_BATCH(라인 DAS — Wave 안 모든 MFC).
- **Task 모양 변화**: `pickMode`·`pickMethod`·`regionCode`·`trfIds[]`, 줄마다 `shares[]`(trfId·mfcId·dueAt·plannedQty·pickedQty·shortQty — 라인 불·분배 PDA 숫자). **묶음 작업이면 `trfId` 가 비어 있다**(사용자 결정) — 화면은 trfIds·shares 를 본다. 단건 작업은 지금처럼 trfId 가 있다.
- 피킹 실적은 지금처럼 줄 합계(SKU·LOT·위치). 서버가 기한 이른 TRF 몫부터 채우고 부족은 기한 늦은 몫부터 뺀다. 나눠 담기는 지금 `/sorting-results`(BOX = TRF 하나, 분류 ≤ 그 TRF 몫 실피킹).
- 다음 작업 받기 본문에 `pickMode` 거르기를 더했다. 지역표 `GET /cdcs/{cdcId}/regions`(칠곡 BUSAN·HONAM·CHUNGCHEONG·GANGWON — 지역 코드 = 지역 바코드 값).
- LAN 은 cdc-location 0.5.0 배포와 칠곡 통로 방식 값을 넣기 전까지 모두 단건으로 돈다.

### R-20 회신 — DPS 통로 작업 (cdc-outbound 0.10.0, 2026-11-25)
- 사용자 결정("제안대로"): DPS 작업 = TRF × 구역 × 층 × **선반 통로 하나**(`Task.aisle`). TRF 끼리는 묶지 않는다(DPS 는 TRF 하나 그대로). 컨베이어 구역 경계 설정값은 두지 않는다.
- 다음 작업 받기 본문 `aisles: ["61","62",…]`(위치 코드 넷째 구간, 30개까지) — 그 통로 작업(aisle 이 있는 DPS 작업)만 받는다. 통로 구역 작업자는 자기 통로들을 넣어 받는다.
- 그 밖 작업은 `aisle` 이 비어 있다(aisles 를 주면 받기 후보에서 빠짐).
- 칠곡 통로 → pickMethod 시드는 `deploy/lan/seed/cdc_pick_methods.py`(PR #488) — LAN 에는 위치 전환 때 같이 넣는다(사용자).

### R-19 ⑥ 받음 — 용차 없음 (2026-11-25)
- 사용자 결정: 용차를 쓰지 않고 모두 정규차(시뮬레이터 865f522). chilgok-dc.md CDC-8 에 적음. CDC 백엔드(cdc-outbound) 변경 없음 — 한 TRF 를 여러 출발로 나누지 않는 규칙 그대로. 용차용 fleet 등록 요청은 시뮬레이터 쪽에서 없앰(이 저장소 service-requests 에는 해당 요청 없음).

### R-21 받음 — 위치 38,926칸 (f3248e5, 2026-11-25)
- 38,926칸 모두 cdc-location 0.5.1 규칙 통과(`ChilgokLayoutIT`), 백엔드 사본 바꿈. 팔레트 자리 18,048(RESERVE 16,608 + 랙 1단 1,440).
- 통로 방식 시드 `cdc_pick_methods.py` 는 사본의 PICK_FACE 통로에서 뽑으므로 7층 H 랙 BOX_PICK 이 저절로 빠진다 — 80통로(DPS 26·BOX_PICK 16·DISTRIBUTION 1·LINE 8·FC 29). 코드 변경 없음.
- LAN 은 그대로(최종 배치 때).
### 알림 — TRF 새 승인 버전 대체 (cdc-outbound 0.13.0, 2026-11-25)
- 같은 TRF 의 더 높은 trfVersion 을 `/internal/transfer-intakes` 로 보내면 **피킹 시작 전까지** 대체한다(사용자). 지금까지의 409 TRANSFER_CHANGE_NOT_SUPPORTED 는 없어졌다.
- 안 끝난 작업에서 그 TRF 몫이 빠지고(빈 작업은 CANCELLED) Wave 에서도 빠진다 → 예전 할당 해제 → 새 버전 할당 → TRF 는 ALLOCATED 등으로 돌아오고 **다시 Wave 에 넣어야 한다**.
- 재고 응답 불명이면 state CHANGING(같은 본문 재전송으로 이어서), 거절이면 CHANGE_HOLD. 피킹·분류가 시작됐으면 409 PICKING_STARTED. 시뮬레이터 목의 "Wave 편성 전까지만" 보다 넓다(릴리스 뒤라도 아무도 안 집었으면 받음).

### 알림 — 피킹 재고는 출고대기 자리로 옮겨 적는다 (cdc-outbound 0.14.0, 2026-11-25)
- 사용자 결정: pick-results 의 실피킹이 응답 뒤 cdc-inventory 에서 예약과 함께 **그 칸 → 그 층 출고대기(통로 91) 목적 MFC 자리**로 옮겨 적힌다(PICK_MOVE). 총현물은 그대로. 칠곡 자리 01 BJG·02 BNG·03 MOKPO·04 SEJ·05 CHC(지역표 `stagingBays`), 상온은 5층 C·7층 G 의 91 행.
- 그래서 스냅샷에서 집은 수량이 원래 칸이 아니라 출고대기 자리에 보인다 — 시뮬레이터 목의 "피킹 = 출고대기 91 로 MOVE" 와 같아졌다. 출발 TOUT 은 그 자리에서.

### 통로 방식 대응표 바꿈 받음 (2657c94, 2026-11-26)
- `deploy/lan/seed/cdc_pick_methods.py` LINE = B·F·G 선반(61~89), FC = E 선반·H 오토스토어. 위치 사본 38,926칸에서 80통로 — DPS 26·BOX_PICK 16·DISTRIBUTION 1·LINE 31·FC 6. LAN 에는 위치 전환 때 같이 넣는다(사용자).
- R-20(0.10.0) 반영 확인 고맙다. 실서버 토큰 비밀번호 파일은 PC 1 몫(LAN `.secrets`) — 사용자 확인 뒤 받는다.
