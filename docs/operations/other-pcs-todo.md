# 다른 PC 할 일 — PC 별 체크리스트 (2026-11-13 기준)

PC 1 이 아닌 PC(사람·Claude)가 **지금 해야 할 일**만 모은 목록. 방법은 `dev-on-other-pcs.md`, 영역·규칙은 `pc-split.md`, 읽을 문서 지도는 `other-pcs-docs.md`.
끝낸 줄은 그 PC 가 PR 로 `[x]` 로 바꾸고 날짜를 적는다(자기 PC 절만 고친다 — 다른 절은 PC 1 이 고친다). 새 할 일이 생기면 PC 1 이 여기에 더한다.

## 모든 다른 PC 공통 (개발 시작 전)

- [ ] 저장소 셋을 같은 부모 폴더의 형제로 받기 — `pmart-backend`·`pmart-clients`·`pmart-mfc-simulation`(드라이브 무관, `dev-on-other-pcs.md` §0)
- [ ] gh 설치·로그인(두 줄 따로: `winget install --id GitHub.cli -e` → 새 터미널에서 `gh auth login`) → 포크 만들고 `fork` remote 추가
- [ ] 비밀번호 파일은 필요한 것만 **`deploy/lan/.secrets/`** 에(사용자가 복사). 저장소 루트 `.secrets/` 에 두지 않는다. `keycloak-admin-password` 는 받지 않는다
- [ ] `./gradlew --stop` 한 번 — Gradle 사용자 단위 제한(workers 2·Xmx2g, 2026-11-13 PC 2~5 적용)이 새 데몬부터 적용된다
- [ ] 루트 CA: PC 2~5 Windows 에는 설치돼 있음. PDA·폰 실기기에서 직원 로그인하려면 기기에도 설치

> **2026-11-15 모든 PC — 로그인 코드는 PC 1 만**(`pc-split.md` §1-3): auth-service·employee-auth-service·common-security·게이트웨이 인증·Keycloak, 클라이언트 `web/core` 로그인·세션, 앱의 `features/auth`·`features/session`, `pmart_core` 토큰은 고치지 않는다 — 필요하면 요청. 이 경로를 바꾼 PR 은 PC 1 이 돌려보낸다. **예외: 라이더 로그인(rider-auth-service·rider_app 로그인)은 PC 5 그대로**.

## PC 3 — mfc 영역 (GitHub YOO196)
- [ ] **IV-7 재고 변동 실시간 스트림 — 전체 센터 한 연결**(2026-11-24 PC 2 — PC 1 사용자 "실시간 재고 … 바로바로 백오피스에 반영"(SSE), PC 2 사용자 "재고 서버가 전체 스트림을 직접 주는 1번으로", 요청서 `mfc-inventory-service.md` IV-7): mfc-inventory SSE 하나 — mfcId 주면 그 센터, 비우면 볼 수 있는 모든 센터(권한은 `/stock-summaries` 와 같음), 최소 이벤트 `{eventId, mfcId, occurredAt}`, heartbeat 5초 안팎. bff 중계 없음. 경로가 정해지면 PC 1 이 게이트웨이 라우트·시간 제한
- [ ] **MI-4 센터 발주·승인·입고 예정 + 발주 수량 추천**(2026-11-24 PC 1 — MFC 시뮬레이터 R-48, 사용자 "B", 요청서 `mfc-inbound-service.md` MI-4): 여러 줄 발주, 센터 관리자 승인 → EXPECTED, 추천 조회(근거·사유 포함, R·L·SS 값 없으면 추천 안 함)
- [x] **MQ-3 회수품 검수 담당 어긋남 + 문서 정리**(2026-11-20 PC 1, 요청서 `mfc-quality-service.md` MQ-3): capacity 는 품질검사 작업자에게 회수품 검수를 주는데 quality 는 센터 관리자만 받음 — 하나로 맞추기. 그리고 `mfc-fulfillment-service/CLAUDE.md`(인계 번호 6자리 → 4자리), `mfc-inventory-service/CLAUDE.md`(깨진 줄 UTF-8) 직접 고치기 — **끝**(2026-11-20 main d94fdeff: quality 0.11.0 검수자 MFC_WORKER 도, fulfillment 문서 4자리)
- [x] **F-13 상품(SKU)별 피킹·결품 줄**(2026-11-20 PC 2 — 웹 BR-73, 요청서 `mfc-fulfillment-service.md` F-13): F-10 picking-lines 를 SKU 로 나눔(sort shortLines·shortRate, limit 10~50), 비율 정렬 하한 값은 PC 3·사용자가 정해 적기. 받는 쪽 bff BF-3 — **구현**(PC 3 2026-11-20, 브랜치 `mfc/shortage-by-sku` fulfillment 0.32.0, 하한 기본 20·응답에 실음)
- [x] **MQ-2 품질 사고 caseId 채우기**(2026-11-20 PC 2 — CS-MFC-Q 회신, 요청서 `mfc-quality-service.md` MQ-2): case 0.31.0 배포됨 — `CaseOpened`(type MFC_QUALITY_IMPACT, refs incidentId) 구독해 `QualityIncident.caseId`(첫 것) — **구현**(PC 3 2026-11-20, 브랜치 `mfc/case-link`, 첫 caseId 만·설정 바인딩 PC 1)
- [x] **IV-6 격리품을 격리 구역으로 옮기는 이동**(2026-11-20 PC 3 — PDA BR-52 R19-5, 요청서 `docs/architecture/service-requests/mfc-inventory-service.md` IV-6): inventory 이동이 QUARANTINED·입고 구역 출발·격리 구역 도착을 받게 → mfc-inbound 가 격리 줄마다 이동 작업. 사용자 결정(누가 옮기나·격리 칸 코드) 먼저 — **구현**(2026-11-20 PC 3, inventory 0.35.0 추가분·inbound 0.17.0 `mfc/inbound-quarantine`)
- [x] **MC-2 다음 작업 받기에 격리 이동**(2026-11-20 PC 3 — 요청서 `mfc-capacity-service.md` MC-2): capacity 후보에 `QUARANTINE_MOVE`(미리 정한 담당에게만). 우선순위·업무 영역은 사용자 결정 — **끝**(2026-11-20 main 5757470a·37174fc7: capacity 0.26.0·0.27.0)
- [ ] **직원 웹 UI·UX 맞추기(web/mfc-backoffice)**(2026-11-19 PC 2 — 사용자 "백오피스 웹 UI·UX 를 MFC·라이더 웹에도"): 지침 `pmart-clients docs/handoff/all/2026-11-19-request-ui-ux-align.md`(통합 백오피스 PR #231 과 함께 main 에 들어옴 — 겉모습·목록·상세·움직임·탭 제목·화면 번호, 파일 경로·커밋). 모양만인 부품은 PC 2 가 `@pmart/web-ui` 로 올린 뒤 같이 쓰고, 앱 조회를 아는 것은 각 앱 사본. **공용 부품**: 모양 부품(SearchPanel 칩 접기·StatStrip·GuideBox·AllDone·SearchSelect·ProgressSteps·motion, `motion.css`·`wing.css`)은 `@pmart/web-ui` 로 올라감(pmart-clients PR #240) — 쓰는 법 `docs/handoff/all/2026-11-19-notice-web-ui-shared-parts.md`, StatCard·StickyTitle 은 앱 사본. 회신은 `all/` 에. 로그인·세션 코드는 PC 1 만
- [x] **MI-3 [LAN 시험 전용] 시험 입고 예정 만들기**(2026-11-19 PC 1 — MFC 시뮬레이터 R-23, 요청서 `mfc-inbound-service.md` MI-3): 승인 없이 INITIAL_STOCK 모양 EXPECTED 하나 → 지금 수령·검수·적치로 재고 채우기. 설정 켠 LAN 만 — **구현**(PC 3 2026-11-19, `mfc/return-arrivals` inbound 0.14.0, 회신 `docs/handoff/simulator/mfc/2026-11-19-simulator-reply-r23-dev-stock.md`)
- [x] **IV-5 센터별 품절 SKU 수**(2026-11-18 PC 2 — 요청서 `mfc-inventory-service.md` IV-5): 백오피스 대시보드용, 가용 ≤ 0 SKU 수(안전재고 아님 — 사용자 결정), 급하지 않음 — **끝**(2026-11-20 정리: 구현·LAN 배포 #218 inventory 0.28.0)
- [x] **F-10 결품률 지표 분모 — 날짜별 피킹한 주문 줄 수·결품 줄 수(사유별)**(2026-11-18 PC 2 — 요청서 `mfc-fulfillment-service.md` F-10): bff `views/metrics` SHORTAGE_RATE 용, 급하지 않음 — **끝**(2026-11-20 정리: 구현·LAN 배포 #218 fulfillment 0.19.0)
- [ ] **`web/mfc-backoffice`(MFC 센터 관리자 웹) 이어받기**(2026-11-17 사용자 "MFC 백오피스를 PC2에서 PC3로"): pmart-clients `docs/apps/mfc-backoffice-web.md`·`docs/handoff/clients/mfc-backoffice/` 를 읽고 시작. PC 2 가 만든 메뉴 11개 화면이 있다. 로그인·세션 코드는 PC 1 만, 웹 공통(`web/core`·`web/ui`)은 PC 2 에 요청. 클라이언트도 포크 → PR(`mfc/<주제>` 브랜치)
- [x] **앱 바꾸기 메뉴(⋮⋮⋮) 붙이기**(2026-11-17 사용자 — 요청 `docs/handoff/clients/all/2026-11-17-request-app-launcher.md` §3-2): PC 2 의 `web/ui` `AppLauncher` 가 main 에 들어간 뒤 `web/mfc-backoffice` 머리줄에 `current="mfc-backoffice"` 한 줄 — **끝**(2026-11-20 정리: PC 2 가 넣음 — pmart-clients #267(835680be))
- [x] **F-8 포장 라벨 숫자로 인계**(2026-11-17 PC 5 — 라이더 앱 요청 §5, 요청서 `mfc-fulfillment-service.md` F-8): 라벨에 짧은 숫자, 라이더 인계 승인을 숫자로도. main 에서 새 브랜치로 — **끝**(2026-11-20 정리: 구현·LAN 배포 #123 0.13.0, 4자리·5번 잠김 #260 0.23.0, 라이더 앱 실서버 연결 pmart-clients #268)

- [x] **IV-4 LOT 바코드(GS1-128)**(2026-11-17 사용자 결정 — 요청서 `docs/architecture/service-requests/mfc-inventory-service.md` IV-4): 짧은 LOT 번호(`lotNo`, 20자 이하), GS1-128 → LOT 찾기 조회, 피킹 때 찍은 LOT 이 지시 LOT 과 같은지 확인(mfc-fulfillment·mfc_app), 입고 검수·실사에도. LOT 번호가 생기면 PC 1 이 LOT 라벨 인쇄 파일을 만든다 — **끝**(2026-11-20 정리: 구현 mfc-inventory 0.21.0·mfc-fulfillment 0.11.0(브랜치 mfc/lot-gs1, 2026-11-17))
- [x] **AP-1 MFC 승인 번호 실제 검증**(2026-11-17 PC 1 — 요청서 `docs/architecture/service-requests/approval-service.md` AP-1 "PC 3 이 할 것" 1~6): approval-service 쪽(종류 `MFC_STOCK_ADJUSTMENT`·`MFC_OPERATION_RESTRICTION`, `/internal/approval-ref-checks`·`/internal/approval-ref-uses`, 계약 0.3.0)은 PC 1 이 만듦. mfc-inventory·mfc-quality·mfc-capacity 에서 Q-11 기준 판정(한 건 손실 10만 원↑ 또는 한 SKU 20개↑ — 단가 pricing `/internal/prices`), 기준 이상이면 본사 승인 요청 본문 안내, 집행 전 승인 번호 사용 호출, `ApprovalRefs` RECORD_ONLY → 실제 확인 전환. main 에서 새 브랜치로 — **끝**(2026-11-20 정리: MFC 쪽 구현·LAN 배포(2026-11-17, 공용 표 AP-1))
- [x] **EA-1 `GrantEnded` 구독**(2026-11-17 PC 1 — 요청서 `docs/architecture/service-requests/employee-auth-service.md` EA-1): mfc-capacity 가 `platform.auth.v1` 의 `GrantEnded`(역할 MFC_WORKER)도 받아 `HrLinkService` 에서 그 grant 의 작업자 센터 권한 행 `valid_to` 를 `endedAt` 으로 당기기(reason REVOKED·AFFILIATION_CHANGED, aggregateVersion 2). main 에서 새 브랜치로 — **끝**(2026-11-20 정리: 구현 — mfc-capacity HrLinkService 가 GrantEnded 구독)
- [x] **mfc-app 요청 2건 백엔드 반영**(2026-11-17 PC 1 전달 — 요청서 `mfc-inventory-service.md` IV-2, `mfc-fulfillment-service.md` F-7): 재고 확인 응답에 상품명(재고 조회 권한은 이미 자기 센터 작업자 OK), 오늘 업무 내역(R1 기간 거르기·R2 completedAt·R3 입고·검사·이동·실사 내 내역 한 곳·R4 합계) — **끝**(2026-11-20 정리: IV-2·F-7 구현·LAN 배포 #120)
- [x] **F-6 PDA 피킹 실적 전부 거절(급함)**(2026-11-17 사용자 "PC3에서 할 것 같아" — 요청서 `docs/architecture/service-requests/mfc-fulfillment-service.md` F-6): 서버 PICK `toLocation` 필수인데 mfc_app 이 안 보냄 → 400 → "거절된 실적". 서버는 카트(`CRT-…`)를 toLocation 으로 받고, 앱은 카트 코드를 보내기(BR-52 R19-3). 같이: `GET /shipments/{id}` ready_at NULL 500(`TaskStore.shipmentReadyAt`). **main 에서 새 브랜치로**(예 `mfc/pick-to-cart`). mfc-app 정식 요청(P1~P4, `handoff/mfc-app/2026-11-17-backend-pick-to-cart.md`)을 F-6 절에 옮겨 둠 — **mfc-inventory 도**(IV-3: PICK requireCart, PACK 장바구니 → 패킹대) — **끝**(2026-11-20 정리: 구현·LAN 배포 #91·#108)
- [x] 포크 → PR → PC 1 병합 경로 시험 — PR #1 병합 `965e7e6`(2026-11-13)
- [x] **저장소 루트 `.secrets/` 사본 삭제**(PR #1 에서 발견. `deploy/lan/.secrets/` 쪽만 남긴다. 이제 `.gitignore` 가 막지만 사본은 지운다) — 2026-11-13 삭제(4개 파일이 `deploy/lan/.secrets/` 와 같음 확인 뒤)
- [x] `git pull origin main`(`.gitignore`·이 문서·dev-on-other-pcs 갱신 받기) — 2026-11-13 `ddfa1f31`
- [x] `docs/mfc/HANDOVER.md` 읽기 — 시작 전 할 일(OR-Tools Docker 시험 등)·남은 일은 거기에 — 2026-11-13 읽음, OR-Tools Docker 시험 `:services:mfc-capacity-service:test` 37/37 통과
- 시뮬레이터는 1차 범위에서 하지 않는다(사용자 2026-11-13) — `pmart-mfc-simulation` 은 지금 받지 않아도 되고 `docs/simulator/` 도 지금은 안 읽어도 된다
- [x] 작업자 명단 ↔ 인사 연결(결정 3개 "추천대로") — PR #3, main `d46671c`(2026-11-13, LAN 미배포)
- [x] 배차 자동 요청 제거(BR-51 "자동요청빼") — PR #4, main `d46671c`(LAN 미배포)
- [x] **센터 관리자는 작업자와 별개**(사용자 2026-11-13, PR #3 검토 때) — 지금 코드는 MFC 센터 소속이면 등록 때 누구나 작업자로 만든다(센터 관리자 포함). **LAN 배포 전에 고친다.** `EmployeeRegistered` 에는 역할이 없다(권한은 승인 뒤 — ADR-0056 결정 4) → 예: 등록 때가 아니라 그 센터 `MFC_WORKER` 권한이 붙을 때(`GrantApplied`, `platform.auth.v1`) 만들기, 또는 등록 사건에 직무 구분을 더해 달라고 platform 에 요청(`service-requests/hr-service.md`). 방식은 PC 3 이 정해 PR 설명에. — **방식(PC 3, 2026-11-13)**: 그 센터 `MFC_WORKER` 권한 반영(`GrantApplied`) 때 작업자 생성, 권한 기간 밖이면 SUSPENDED(센터 이동도 권한으로 — 인사 소속 변경 사건은 안 씀). PR(브랜치 `mfc/worker-by-grant`), LAN 배포 전 설정 저장소 구독 2개 필요
- [x] 작업자 상세 `GET /mfcs/{mfcId}/workers/{workerId}` 응답에 자격(`qualification`)·고용 형태·유효 업무가 비어 온다 — `WorkersController.getWorker` 가 `WorkerDetail` 로 옮길 때 그 항목을 빠뜨림(목록 `GET …/workers` 에는 있음, 2026-11-13 PC 1 발견) — 고침(PC 3, 브랜치 `mfc/pda-work-area`, 계약 0.13.0 — BR-52 R1·R3·R4 와 같이)
- [ ] **mfc_app(클라이언트 mfc-app 영역, PC 3 담당 — 2026-11-13)**: `SUBJECT_BLOCKED`(로그아웃·정지 즉시 401) 안내 — **끝**(클라이언트 ca2ecf3), 남은 것 **실기기 로그인 확인**(로그인 마무리 기준 4 — `pc-split.md` §1-1) — `docs/handoff/clients/mfc-app/2026-11-13-clients-reply-mfc-app-login-and-https-build.md`. 실기기에는 LAN 루트 CA 설치 필요. 시험 계정은 `mfcw-*` 70명(`dev-on-other-pcs.md` §2)
- [ ] 클라이언트 BR-52(PDA 업무 선택 배정)·BR-53(입고 수령 화면 값) 회신 — BR-52 R1·R3·R4 는 PR #9, **BR-53 R8~R11 회신**(PC 3, 2026-11-13, 브랜치 `mfc/inbound-labels` — `docs/handoff/clients/mfc-app/2026-11-13-reply-br53-inbound-receiving.md`). **BR-52 R2(품질검사·적치)·R19-1 회신**(PC 3, 2026-11-14, 브랜치 `mfc/br52-r2-r19` — `docs/handoff/clients/mfc-app/2026-11-14-reply-br52-r2-r19-1.md`). 남은 것 R2 의 입고·회수품·실사, R5~R7·R12~R18·R19 나머지·R20. **R14 실사 계획·서버 잠금·R19-6·R19-7(실사)·R15 답**(PC 3, 2026-11-20, 브랜치 `mfc/br52-inventory` — `docs/handoff/clients/mfc-app/2026-11-20-reply-br52-inventory.md`, inventory 0.34.0) — 실사 작업 배정은 MC-1. **R19-6 남은 것 — 실사 발견품 조사**(PC 3, 2026-11-20, 브랜치 `mfc/count-found-items`, inventory 0.43.0 V28 — 승인 때 정하지 못한 줄 → 조사 건, 장부 그대로, 관리자 결론). 남은 것: R17 박스 수·바코드(CO-2), 입고 예정 원천, R18-2 격리 사유 다시 보이기(화면 요청 오면), 참조 없는 증빙 사진 정리

- [x] **MFC 백오피스(센터 관리자 웹)용 API**(2026-11-15 — 요청서 `docs/architecture/service-requests/mfc-backoffice-staff-apis.md` MB-1~3): 목록 API 들, 계약만인 inbound-promises·quality-incidents(순서는 PC 3 판단), 권한 MB-3 은 사용자 결정 뒤. main 에서 새 브랜치로 — **끝**(2026-11-20 정리: MB-1·MB-3 11-18, MB-2 입고 예정 inbound 0.16.0·품질 사고 quality 0.6.0~0.10.0·caseId #307)

- [ ] **서비스 간 요청 — PC 3 몫(2026-11-16 정리, 표 `docs/architecture/service-requests/README.md` "받는 PC" 칸)**:
  - [x] **MC-1 다음 작업 받기에 재고 실사(CYCLE_COUNT)**(2026-11-20 PC 3 — `mfc-capacity-service.md` MC-1, 클라이언트 BR-52 R2·R14): mfc-inventory `GET /internal/count-work` 후보, 재계수는 첫 계수자 제외, 한 실사 한 사람 — **끝**(2026-11-20 main dd7de5b2: capacity 0.24.0)
  - [ ] ML-1 실사 계획 잠금 칸 화면 표시(2026-11-20 PC 3 — `mfc-location-service.md` ML-1, 낮음 — 화면 요청 올 때)
  - [x] F-2 피킹 시작 전 포장 소요시간 → 배차(`mfc-fulfillment-service.md`) — **끝**(2026-11-20 정리: 구현·LAN 배포 #124)
  - [x] F-5 포장 라벨·인계 화면에 화면용 주문번호(`OrderConfirmed.orderNumber`, `mfc-fulfillment-service.md` — 2026-11-16 delivery) — **끝**(2026-11-20 정리: 구현·LAN 배포 #123)
  - [x] F-3 주문 번호로 현장 상태(직원용 — 통합 조회 MFC 칸, PC 2 요청) — **끝**(2026-11-20 정리: 구현·LAN 배포 #97)
  - [x] F-4 멈춘 중지 요청·거절된 인계 목록 + 재시도에 운영자 ID·사유(처리 대기 BO-12, PC 2 요청 — bff 쪽은 준비됨) — **끝**(2026-11-20 정리: 구현·LAN 배포 #95)
  - [x] F-9 센터 출고 목록 — 포장 인계 현황(관리자 화면, 센터 관리자 모니터)용 `GET /shipments?mfcId=&state=`(PC 2 요청, `mfc-fulfillment-service.md` F-9) — **끝**(2026-11-20 정리: 구현·LAN 배포 #128)
  - [x] IV-1 가용 조회에 출하 가능 수량(소비기한 적격 LOT 만), 지난 LOT 이 sellable 로 남는 것 확인 — **고객 주문 막힘 관련, 높음**(`mfc-inventory-service.md`) — **끝**(2026-11-20 정리: 구현·LAN 배포 #122)
  - [x] MI-1 회수품 점원 수령 확인·검수(`mfc-inbound-service.md`, DD-2 뒤) — **구현**(PC 3 2026-11-19, 브랜치 `mfc/return-arrivals`: inbound 0.13.0·quality 0.7.0)
  - [x] **MQ-1 `ReturnInspected` 줄에 `orderLineId`**(2026-11-17 PC 1 — 요청서 `docs/architecture/service-requests/mfc-quality-service.md` MQ-1, 선택): 반입 줄(`ReturnItemReceived.lines[].orderLineId`)을 검수 줄에 이어 싣기 + 계약 `ReturnInspected.v1.json`·events.md. 같은 SKU 가 두 주문 행이면 claim 환불 금액이 모호해진다. claim 은 이미 받는다(없으면 SKU 로) — **구현**(PC 3 2026-11-19, 브랜치 `mfc/return-arrivals`: inbound 0.13.0·quality 0.7.0)
  - [x] **MI-2 반납 도착 대기 목록**(2026-11-17 PC 5 — `mfc-inbound-service.md` MI-2): `ReturnDelivered`(CLAIM_RETURN·MISDELIVERY_RECOVERY·FAILED_DELIVERY_RETURN — 전달 실패 반납 2026-11-17 보충) 구독 → 점원 화면 "반납 도착" → `POST /return-receipts` 로 반입. **없으면 고객 반품 회수품이 MFC 에서 멈춰 환불까지 안 간다**(claim CL-1 배포됨) — **구현**(PC 3 2026-11-19, 브랜치 `mfc/return-arrivals`: inbound 0.13.0·quality 0.7.0)
  - 참고(2026-11-16 PC 1): LAN `mfc_inventory_service.sku_projection.barcodes` 486행을 PC 1 이 직접 채움(시험 바코드 — `deploy/lan/seed/barcodes-2026-11-16.csv`, 상품 DB 와 같음). 다음 ProductVersionPublished 가 오면 상품 쪽 값으로 덮인다.
  - [ ] MFC 시뮬레이터 요청 R-13(상온 대기 칸 — 사용자 결정 대기)·R-15/16(칸·여러 SKU 재고 조회)·R-17(작업 실적 목록)·R-19(인계 끝난 출고 `deliveryId` 비어 있음 — **R-17·R-19 구현**(PC 3 2026-11-20, 브랜치 `mfc/sim-r17-r19`: fulfillment 0.24.0, 회신 `docs/handoff/simulator/mfc/2026-11-20-simulator-reply-r17-r19.md`)) — 시뮬레이터 저장소 `docs/backend-requests.md`(GitHub 판이 11-10 이라 아직 안 보이면 PC 1 에 요청)
  - [x] **F-11 화면에 코드 그대로 남은 사유 값**(2026-11-19 PC 1 — 원 요청 backoffice code-names C1~C3): mfc-fulfillment `StopRequest.lastError`·`blockReason`·`handoverRejectReason` 값 목록과 한 줄 뜻을 계약에(enum 또는 description 표). `docs/architecture/service-requests/mfc-fulfillment-service.md` F-11 — **회신**(PC 3 2026-11-19, 계약 설명 + `docs/handoff/clients/backoffice/2026-11-19-reply-code-names-mfc.md`)
  - [x] **F-12 출하대기 랙 스캔 → 포장 재고 위치**(2026-11-19 PC 1 — MFC 시뮬레이터 R-20 = PDA BR-52 R20, 11-14 "다음 차례 후보" 뒤 미착수): `service-requests/mfc-fulfillment-service.md` F-12, 회신은 `docs/handoff/simulator/mfc/` — **끝**(2026-11-20 정리: 구현·LAN 배포 #238)
  - [x] **MB-2 품질 사고 — 사용자 결정 대기**(2026-11-19 PC 1 기록): PC 3 가 남긴 질문 5개(`docs/handoff/clients/mfc-backoffice/2026-11-18-reply-lists-and-roles.md` §3)를 `docs/business/flows/pending-user-decisions.md` §2 MB-2 에 옮김 — 답이 나오면 PC 3 가 mfc-quality 에 구현 — **끝**(2026-11-20 정리: 결정 받아 구현 — 품질 사고·자동 열기(파손·변질)·고객 영향 R6(#249·#257). 고객 사건 연결은 CS-MFC-Q(PC 2 대기))

- [x] (2026-11-19 delivery) MFC 백오피스 "배차 지연" 목록 + "배차 불가로 취소" — dispatch `GET /dispatch-delays`(0.12.0), claim CL-5. 요청 `service-requests/mfc-backoffice-staff-apis.md` 끝 절 — **끝**(2026-11-20 정리: 화면 있음 — pmart-clients web/mfc-backoffice DispatchDelayPage)

## PC 5 — delivery 영역
- [x] **DE-9 오배송 회수 포기 → 라이더 정산 차감**(2026-11-24 PC 1 — ADR-0086, 요청서 `delivery-execution-service.md` DE-9): 차감 금액 결제액 − 배달팁, 이의 5일, 정산 이월. 회수 24시간 포기는 PR #416 — 끝(#418·#420, 2026-11-24 LAN)
- [x] **DE-10 지급 넘김 상한 다 쓴 묶음 → `RiderPayoutEscalated` 사건**(2026-11-24 PC 1 — ADR-0086 결정 9, 요청서 `delivery-execution-service.md` DE-10): 묶음이 escalated 될 때 한 번, 받는 곳은 PC 1 이 뒤에 정함. → **완료**(2026-11-24 #431 21d7cd9d, execution 0.43.1, pc5 배포).
- [x] **RS-8 라이더 문의 정지 단어 → 7일 정지**(2026-11-23 PC 2 — `rider-service.md` RS-8): case 0.37.0 `RiderProfanityDetected` 구독 → 7일 정지(자동 해제)·라이더 알림 "7일 계정 정지", 오인식은 지원센터·관리자가 사유 적고 해제 — **구현**(PC 5 #378)
- [ ] **RS-7 라이더 목록 센터로 거르기**(2026-11-23 PC 1 — 사용자 "센터를 고르면 그 해당 센터에 해당하는 라이더 정보", 요청서 `service-requests/rider-service.md` RS-7): `GET /riders?mfcId=`(배달 지역 기준)·`/riders/withdrawals?mfcId=`, mfcId 없는 execution·dispatch 직원 목록 판단. 권한은 그대로.
- [x] **RA-1 라이더 소셜 로그인 "동의는 처음 가입할 때만"**(2026-11-22 PC 1 — 사용자 "라이더쪽을 수정해야해, 소셜로그인부분때문에", 요청서 `docs/architecture/service-requests/rider-auth-service.md` RA-1): ① 탈퇴 때 카카오 연결 끊기(라이더 카카오 Admin 키 — 사용자에게) ② 탈퇴 때 네이버 끊기 ③ 네이버 가입 전 끊기(rider-service 가입 여부 내부 조회 필요) ④ 네이버 이메일 → 가린 이메일. 고객 auth-service 구현을 같은 모양으로. 라이더 앱 몫은 요청서 끝. — **구현(2026-11-22 PC 5, rider-auth 0.4.0·V4, 브랜치 `delivery/rider-social-consent-once`)** — rider-service 변경 없음(기존 `GET /internal/riders/{id}` 로 가입 판단). 남음: 라이더 카카오 Admin 키(사용자·PC 1), 라이더 앱(탈퇴 네이버 재인증 purpose WITHDRAWAL 등)
- [x] **DD-6 정시 배송 지표 시간 칸**(2026-11-25 PC 5 구현 — dispatch 0.18.0, HOUR·DAY·WEEK·MONTH 모두)(2026-11-21 PC 3 — MFC 백오피스 대시보드 시간대별 추이): `GET /metrics/on-time-deliveries` 에 `bucket=HOUR`(줄 `hour` +09:00, 7일), 있으면 좋음 WEEK·MONTH(366일) — 요청서 `delivery-dispatch-service.md` DD-6, 같은 모양은 mfc-fulfillment 0.34.0 피킹 지표
- [x] **RS-MFC 라이더 화면 표기를 MFC 직원에게**(2026-11-19 PC 3 — `rider-service.md` RS-MFC): `GET /riders?riderIds=` 가 MFC_SUPERVISOR·MFC_WORKER 에 403 → 라이더 번호·가린 이름만 주는 조회를 열어 주기(ADR-0070, MFC 백오피스 인계 화면)

- [ ] **직원 웹 UI·UX 맞추기(web/rider-backoffice)**(2026-11-19 PC 2 — 사용자 "백오피스 웹 UI·UX 를 MFC·라이더 웹에도"): 지침 `pmart-clients docs/handoff/all/2026-11-19-request-ui-ux-align.md`(통합 백오피스 PR #231 과 함께 main 에 들어옴 — 겉모습·목록·상세·움직임·탭 제목·화면 번호, 파일 경로·커밋). 모양만인 부품은 PC 2 가 `@pmart/web-ui` 로 올린 뒤 같이 쓰고, 앱 조회를 아는 것은 각 앱 사본. 회신은 `all/` 에. 로그인·세션 코드는 PC 1 만

- [ ] **직원 웹 UI·UX 맞추기(web/rider-backoffice)**(2026-11-19 PC 2 — 사용자 "백오피스 웹 UI·UX 를 MFC·라이더 웹에도"): 지침 `pmart-clients docs/handoff/all/2026-11-19-request-ui-ux-align.md`(통합 백오피스 PR #231 과 함께 main 에 들어옴 — 겉모습·목록·상세·움직임·탭 제목·화면 번호, 파일 경로·커밋). 모양만인 부품은 PC 2 가 `@pmart/web-ui` 로 올린 뒤 같이 쓰고, 앱 조회를 아는 것은 각 앱 사본. **공용 부품**: 모양 부품(SearchPanel 칩 접기·StatStrip·GuideBox·AllDone·SearchSelect·ProgressSteps·motion, `motion.css`·`wing.css`)은 `@pmart/web-ui` 로 올라감(pmart-clients PR #240) — 쓰는 법 `docs/handoff/all/2026-11-19-notice-web-ui-shared-parts.md`, StatCard·StickyTitle 은 앱 사본. 회신은 `all/` 에. 로그인·세션 코드는 PC 1 만
- [x] **DE-7 배송 확인 목록 상태별 수**(2026-11-19 PC 2 — 백오피스 웹 숫자 띠, 요청서 `delivery-execution-service.md` DE-7): `GET /deliveries/counts`(목록과 같은 거르기, 모든 상태 0 까지, capped), 급하지 않음
- [x] (2026-11-19 PR — V8) **TR-4 곧 도착 사건**(2026-11-19 PC 1 — 고객 앱 알림 레퍼런스 3-4, 사용자 결정 300m): delivery-tracking `RiderApproachingCustomer`(delivery.tracking.v1) — 요청서 `delivery-tracking-service.md` TR-4. notification 받는 쪽은 PC 1 이 만들어 둠

- [x] (2026-11-19 PR — execution 0.33.0) **DE-7 배송 확인 목록 상태별 수**(2026-11-19 PC 2 — 백오피스 웹 숫자 띠, 요청서 `delivery-execution-service.md` DE-7): `GET /deliveries/counts`(목록과 같은 거르기, 모든 상태 0 까지, capped), 급하지 않음
- [x] **TR-4 곧 도착 사건**(2026-11-19 PC 1 — 고객 앱 알림 레퍼런스 3-4, 사용자 결정 300m): delivery-tracking `RiderApproachingCustomer`(delivery.tracking.v1) — 요청서 `delivery-tracking-service.md` TR-4. notification 받는 쪽은 PC 1 이 만들어 둠
- [x] **DE-6 "가고 있어요" 사건**(2026-11-19 PC 1 — 레퍼런스 3-3): delivery-execution `DeliveryHeadingToCustomer`(delivery.delivery.v1, 묶음 배달 다음 차례) — 요청서 `delivery-execution-service.md` DE-6

- [x] **TR-4 곧 도착 사건**(2026-11-19 PC 1 — 고객 앱 알림 레퍼런스 3-4, 사용자 결정 300m): delivery-tracking `RiderApproachingCustomer`(delivery.tracking.v1) — 요청서 `delivery-tracking-service.md` TR-4. notification 받는 쪽은 PC 1 이 만들어 둠
- [x] (2026-11-19 PR — V23) **DE-6 "가고 있어요" 사건**(2026-11-19 PC 1 — 레퍼런스 3-3): delivery-execution `DeliveryHeadingToCustomer`(delivery.delivery.v1, 묶음 배달 다음 차례) — 요청서 `delivery-execution-service.md` DE-6
- [x] **DE-4 배송 `packageIds` 가 인계 전에 비어 라이더가 포장 QR 을 못 찍음**(2026-11-18 PC 3, **급함** — `delivery-execution-service.md` DE-4): `ExecutionViews.delivery` 의 packageIds 를 `shipment_package` ∪ 인계 기록으로. 지금 LAN 에서 모든 인계가 막힌다
- [x] (2026-11-19 PR — dispatch 0.12.2) **DD-5 제안 조회 `pollAfterSec: null` 이면 앱이 조회를 멈춤**(2026-11-18 PC 3 — `delivery-dispatch-service.md` DD-5): 첫 제안을 놓쳐 라이더 1명 LAN 에서 13분 지연. null 대신 긴 간격, 조회 실패는 평소 간격. 같이 보기: 라이더 앱 센터 연락처 알림 `docs/handoff/clients/rider-app/2026-11-18-notice-mfc-staff-contacts.md`
- [x] **DD-4 정시 배송률 지표**(2026-11-18 PC 2 — 요청서 `delivery-dispatch-service.md` DD-4, **2026-11-19 사용자 결정**: delivery-dispatch 가 배송 단위로 셈, 시간대 지난 미전달은 늦음): 날짜·센터별 due·onTime·unjudged API → bff ON_TIME_DELIVERY

- [x] **DD-5 제안 조회 `pollAfterSec: null` 이면 앱이 조회를 멈춤**(2026-11-18 PC 3 — `delivery-dispatch-service.md` DD-5): 첫 제안을 놓쳐 라이더 1명 LAN 에서 13분 지연. null 대신 긴 간격, 조회 실패는 평소 간격. 같이 보기: 라이더 앱 센터 연락처 알림 `docs/handoff/clients/rider-app/2026-11-18-notice-mfc-staff-contacts.md`
- [x] (2026-11-19 PR — dispatch 0.13.0) **DD-4 정시 배송률 지표**(2026-11-18 PC 2 — 요청서 `delivery-dispatch-service.md` DD-4, **2026-11-19 사용자 결정**: delivery-dispatch 가 배송 단위로 셈, 시간대 지난 미전달은 늦음): 날짜·센터별 due·onTime·unjudged API → bff ON_TIME_DELIVERY
- [ ] **라이더 앱 휴대폰 푸시(FCM) 연결**(2026-11-17 — 요청 `docs/handoff/clients/rider-app/2026-11-17-request-rider-push-fcm.md`): 서버 푸시 켜짐(Firebase `pmart-123`). 앱에 google-services.json(사용자가 PC 1 → PC 5 복사)·firebase_messaging·토큰 등록 `PUT /me/push-tokens/{deviceId}`
- [ ] **`web/rider-backoffice`(라이더 백오피스 — 관제 포함) 맡기**(2026-11-17 사용자 "라이더 백오피스는 PC5로"): PC 1 이 뼈대·로그인·LAN 서빙(8446)을 만들었다. 계획·남은 결정 `docs/delivery/rider-backoffice-plan.md`, 앱 문서 pmart-clients `docs/apps/rider-backoffice-web.md`. 관제는 직원용 라이더 위치 API(delivery-tracking)부터. 로그인·세션 코드는 PC 1 만, 웹 공통은 PC 2 에 요청
- [x] **앱 바꾸기 메뉴(⋮⋮⋮) 붙이기**(2026-11-17 사용자 — 요청 `docs/handoff/clients/all/2026-11-17-request-app-launcher.md` §3-3): PC 2 의 `web/ui` `AppLauncher` 가 main 에 들어간 뒤 `web/rider-backoffice` 머리줄에 `current="rider-backoffice"` 한 줄 — **끝**(2026-11-20 PC 2 가 넣음 — pmart-clients #267(835680be))
- [x] **고객 반품 회수 배차(CL-1 배송 쪽, DD-3)**(2026-11-17 PC 5 — 브랜치 `delivery/claim-return-pickup`, 사용자 결정 바로 배차·일반 표·실패마다 알림·고객 사정 실패도 지급): dispatch 0.5.0 V11·execution 0.10.0 V11·tracking, 새 사건 `ReturnPickupFailed`. 라이더 앱 화면은 요청 handoff(`docs/handoff/clients/rider-app/2026-11-17-request-claim-return-pickup.md`)
- [x] **TR-3 고객·직원 ETA 조회·실시간 구독**(2026-11-17 PC 5 구현 — 브랜치 `delivery/tr-3-eta` PR)(2026-11-17 사용자 "PC 5에 요청" — 요청서 `docs/architecture/service-requests/delivery-tracking-service.md` TR-3): `GET /deliveries/{id}/eta`·`/stream`(SSE) 구현 — 고객 본인 배송 dropEta 만, 직원은 execution `Access` 범위, ETA 계산 꺼짐이면 ETA_PENDING. 고객앱은 이미 부른다. **main 에서 새 브랜치로**(예 `delivery/tr-3-eta-api`)
- [x] **PR #71 다시 맞추기**(2026-11-16 PC 1 — 2026-11-17 V11·0.13.0 으로 맞춰 병합·배포 09115c80): #68(PC 2 CS 접수)과 case V10·0.12.0·문의 코드 4곳이 겹침 → main 위로 rebase, `V11__rider_inquiry.sql`·case 0.13.0, 허용 목록 다시 생성. PR 댓글 참고. case-service 는 PC 2 검토도

- [x] 공통 절 전부(gh·포크·`./gradlew --stop`) — 2026-11-13: 저장소 형제(`pmart-backend`·`pmart-clients`, 시뮬레이터는 delivery 에 불필요), gh `piaa1004` 로그인·포크 `piaa1004/pmart-backend`·`fork` remote, 비밀번호 파일 4개 `deploy/lan/.secrets/`(루트 `.secrets/` 없음), Gradle 제한 파일 있음·`--stop`, 루트 CA 설치 확인. 주의: gh 는 설치돼 있지만 PATH 에 없는 터미널이 있음(`C:\Program Files\GitHub CLI\gh.exe`)
- [x] `docs/delivery/HANDOVER.md` 읽기 — 2026-11-13. 남은 결정 중 AI 엔진(ADR-0058)·배달 시간 08~24시·카카오 키는 사용자가 정함(kickoff §6). 남은 것: 라이더 앱 기반·CP-1·오배송 확인 기록 주체
- [x] **PR #8 변경 요청 반영**(PC 1 검토 2026-11-13 — `gh pr view 8 --comments` 또는 GitHub PR 화면): H1 포장 전 주문 중지 때 배차 멈추기, H2 픽업 뒤 진행 중 수(배송 완료를 같이 만들거나 임시로 ASSIGNED 만), H3 주소·좌표를 URL 쿼리에서 빼기(추적 기록), M1 라이더 단위 잠금, M4 포장 목록 모를 때 PICKED_UP 금지, L1 취소 결과 권한. 다음 묶음: M2 재배차 길, M3 카카오 호출 줄이기 — 2026-11-13 전부 반영(H3 `337a2110`, H1·M4·M1·L1 `741fc90f`, M2·M3 `04bf6a2b`, H2 = delivery-execution 0.2.0 배송 수행·전달 완료 → dispatch CLOSED). 재검토 대기
- [x] **PR #8 재검토 변경 요청 반영**(PC 1 2026-11-13 밤): ① 피킹 전 부분 중지(PARTIAL, `perPackage=[]`)를 전체 중지로 처리하는 것 고치기 ② 429 때 `later`(quota-backoff) 쓰기 ③ DeliveryCompleted.lines 포장마다 마지막 인계만 ④ 게이트웨이 사용자 종류 목록 다시 생성(optimizer `/health` 제외). 사용자 확인: F-2 "픽업 시작 전" ↔ "피킹 시작 전". 다음 묶음: 배송 실패 닫는 길, DispatchFailed 반복 의미, 경쟁 잠금, S3 트랜잭션 밖 — 2026-11-14 반영: ① 피킹 전 중지(perPackage=[])는 판단하지 않고 order `OrderCancelled` FULL 로 주문 전체 취소·차단(사용자 (가)) ② 429 때 quota-backoff ③ 완료 수량은 포장마다 마지막 인계만(출고 포장만, 비면 완료 거절) ④ 허용 목록 다시 생성(생성기에서 optimizer 계약 제외). F-2 = 피킹 시작 전(사용자 확인). 다음 묶음 중 "실패 닫는 길"은 execution 0.3.0 으로 생김
- [x] PR #8 병합 1fa5b32·LAN 배포 e867e22(2026-11-14, PC 1). 실패 확정은 PC 1 이 관리자만으로 좁힘(eefc1a9 — 그 센터 MFC_SUPERVISOR·OPERATOR_ADMIN·CS_AGENT)
- [x] PR #8 다음 묶음(2026-11-14 PC 5 반영 — 브랜치 `delivery/dd-1-next`, 버전 두 흐름은 events.md §7 머리말로 문서화 — PC 1 확인): 같은 라이더 재배정 뒤 완료 수행료 미생성, 도움요청 incidentId 겹침(deliveryId 붙이기), `ensure`/`onStop` 같은 주문 advisory lock, S3 업로드를 트랜잭션 밖으로, CREATED 배송 취소 때 DeliveryCancelledBeforePickup 내지 않기, 같은 aggregate 버전 두 흐름. LAN 시험에는 라이더 시험 토큰·운행·위치 필요
- [x] **RS-1 라이더 계정 제한**(2026-11-14 사용자 결정 — 브랜치 `delivery/rs-1-restriction`, `delivery/dd-1-next` 위): rider 0.3.0 운영자 제한 API(OPERATOR_ADMIN)·V3 이력·`RiderRestricted`, dispatch `riderEvents` 구독 → 픽업 전 배정 풀고 재배차·픽업분은 전달까지. 병합 때 pmart-config `delivery-dispatch-service.yml` 에 `riderEvents`, `rider-service.yml` 에 `caseEvents`·검토 기준 값(PC 1). RS-3 컴플레인 누적(같은 종류 7일 3건)도 같은 PR — case 발행(R-5)은 PC 1 영역
- [x] **PC 1 없이 할 수 있는 것**(2026-11-14 사용자 결정 — 브랜치 `delivery/rider-app-next`, `delivery/rs-1-restriction` 위): BR-23 회신(QR = MFC `PKG:<id>:<버전>`), BR-24 전달지(order O-5 + execution `drop-info`, 고객 전화번호 그대로), tracking 위치 끊김 3분·보존 30일. 병합 때 pmart-config `delivery-tracking-service.yml` 에 `deliveryEvents` 바인딩(PC 1). **order O-4 도로명주소 늘 null 버그**는 main 위 작은 PR 로 따로 — LAN 실배차 전에 배포 필요
- [x] **백오피스 고객센터·라이더 운영 직원 API**(2026-11-15 사용자 결정 — 요청서 `docs/architecture/service-requests/delivery-backoffice-staff-apis.md` BOD-1~6 — 2026-11-15 PC 5 구현, 브랜치 `delivery/backoffice-staff-apis` PR, 요청서 회신 절. 라이더 찾기는 ID·전화 완전 일치만(사용자 "이름은 동명이인"), 상세 원문은 ADR-0062 열람 요청): 새 역할 `RIDER_OPS` 권한(rider·execution·dispatch), CS_AGENT 배차 조회, 직원용 라이더 목록·검색·상세, 전달 실패·도움요청 대기 목록. **main(6aa24c06 이후)에서 새 브랜치로**(예 `delivery/backoffice-staff-apis`) — PR #15 브랜치를 이어 쓰지 않는다
- [x] ~~배송 쪽 직원 시험 계정은 없다~~ — 2026-11-15 PC 1 이 LAN 에 `test-cs-rider@pmart.com`(CS_AGENT·RIDER_OPS, 인사 등록·승인 흐름) 을 만듦. 비밀번호 파일은 PC 1 `deploy/lan/.secrets/keycloak-test-cs-rider@pmart.com-password` — 필요하면 PC 1 에 요청(복사), `staff_auth.py` `staff_token("test-cs-rider@pmart.com")` 로 토큰
- [x] **라이더 찾기 쪽 넘김 버그(2026-11-15 PC 1 검토 — #17 은 병합·LAN 배포 6dfcc4bb)**: `OperatorController.searchRidersForStaff` 가 `size + 1` 을 넘기는데 `StaffRiderService.search` 가 건너뛸 수를 그 값으로 `page * size` 계산 → `page × (size+1)`, 2쪽부터 한 명씩 빠짐. limit·offset 을 따로 받게 고치고 2쪽 시험 추가. **새 main 에서 새 브랜치로**(예 `delivery/rider-search-paging`) — #17·#18 브랜치를 이어 쓰지 않는다. PR #17 댓글 issuecomment-6894891956 — 2026-11-16 PC 5 고침(브랜치 `delivery/rider-search-paging`, StaffRiderIT 쪽 넘김 시험 — 옛 코드에서는 실패 확인)
- 카카오모빌리티 키(ADR-0058): LAN 설정 `delivery-tracking-service-lan.yml` 의 `pmart.tracking.kakao-mobility.rest-api-key`(`{cipher}`)·`base-url`(2026-11-13 PC 1 등록 — 주소 판정 키와 다른 앱 키). 길찾기 코드를 만들 때 이 이름으로 읽는다(다른 서비스에서 부르면 PC 1 에 옮겨 달라고). 같은 키 원본은 PC 5 `deploy/lan/.secrets/kakao-rest-api-key`, PC 1 은 `kakao-mobility-rest-api-key`
- 클라이언트 rider-app 영역도 PC 5(2026-11-13) — 라이더 앱 handoff `docs/handoff/clients/rider-app/`, 클라이언트 BR-23(포장 QR 원문)·BR-24(라이더 전달지 경로)는 배송·인계 쪽 열린 요청
- [x] **2026-11-15 결정 다섯(사용자 "추천대로")** — 정지 라이더 로그인 끊기(rider-auth 구현, 브랜치 `delivery/rider-session-cut`), 위치 끊김 = 지원센터 알림만(case R-6 요청), 검토 알림 나중, 오배송 확인 = 상담원·지원센터(case R-7 요청), CP-1 = 주문 유지 + 지연 안내(구현 대기). `docs/delivery/HANDOVER.md` §4
- [x] DD-2 오배송 회수 배차(2026-11-16 PC 5 — 브랜치 `delivery/dd-2-misdelivery-recovery`, 사용자 "추천대로" a~d). PC 1: pmart-config `delivery-dispatch-service.yml` 에 `caseEvents` 바인딩(platform.case.v1, 소비 그룹 시작 위치 latest), 마이그레이션 dispatch V8·execution V7, 배포 순서 tracking → execution → dispatch
- [x] CP-1 구현(`CapacityReduced` 구독 → 주문 유지 + 지연 가능 안내, 고객 선택) — 1부 #59·2부 S4 #61·#64·#65 병합·배포(2026-11-17 정리)
- [x] **오배송 재발송(2026-11-16 사용자 "새상품 재배차 서비스 넣어" → "전부 PR로")** — 영역별 PR 셋: `mfc/misdelivery-reshipment-work`(fulfillment 재발송 작업 지시 — PC 3 검토), `platform/misdelivery-reshipment-saga`(order RESHIPMENT 원장·orchestration 재발송 사가 — PC 1 검토), `delivery/misdelivery-reshipment`(dispatch 재발송 배송 표시·약속, 내부 배정 조회 orderId + #36 에 빠진 원래 고객 전화·사진 보기). 배포 순서 mfc-fulfillment·order·dispatch·execution → orchestration 마지막. 흐름 `docs/business/flows/misdelivery-recovery-reshipment.md`
- [x] 사진 보기·원래 고객 전화(2026-11-16 사용자 — execution, 같은 브랜치)
- [ ] 라이더 앱 회수 화면(handoff 2026-11-16)
- [ ] LAN 서비스가 많은 PC 다(남은 메모리 약 5GB, 2026-11-13) — 빌드·시험 중 LAN 서비스 상태가 나빠지면 PC 1 에 알린다

- [ ] **서비스 간 요청 — PC 5 몫(2026-11-16 정리)**:
  - [x] DD-MFC `DispatchFailed` 에 `mfcId` 싣기(2026-11-16 구현 — `DispatchDelayed` 도)(필드 추가 — 센터 관리자 배차 실패 사건, PC 2 요청, `delivery-dispatch-service.md`)
  - [x] DE-CASE `CaseOpened`·`CaseClosed` 구독(2026-11-16 구현 — Reopened 도, 사건 붙은 도움요청은 처리함 닫기 409) → 도움요청에 사건 번호·상태(`delivery-execution-service.md`)
  - [x] TR-1 `DeliveryEtaUpdated`(2026-11-16 구현 — 설정 있을 때만 켜짐)
  - [x] DE-2 도움요청 대화(#60)(2026-11-17 PC 5 구현 — 브랜치 `delivery/de-2-help-chat`) — 답장 권한을 접수 건 규칙에(CS 보기만·넘긴 건 관리자만), 새 메시지 값 `lastRiderMessageAt`, 관리자 가림, 처리 중 = 집을 때(PC 2 요청, `delivery-execution-service.md` DE-2)
  - [x] RS-2(rider-auth) — 표 참고(구현 2026-11-13, 표 "구현"). RS-3 컴플레인 검토 목록도 LAN 배포 확인(2026-11-17 PC 5 — 관리자 `GET /riders/restriction-reviews` 200)
  - [x] **DD-3 고객 반품 회수 배송**(2026-11-17 PC 5 — #104 구현·배포, 맞춤(pickupRequestId·회수 품목·전달 전 반품 불가) 브랜치 `delivery/dd-3-align`. 회수 실패는 DeliveryFailed 대신 `ReturnPickupFailed` — claim 구독함)(2026-11-17 PC 1 — CL-1, 사용자 "추천대로"): `ReturnPickupRequested`(order.claim.v1, purpose CLAIM_RETURN·pickupRequestId·attempt) 구독 → dispatch 회수 배송(원주문 배송지 → MFC)·execution 회수 수행(`ReturnPickedUp`·`ReturnDelivered` 에 claimId), 회수 실패는 `DeliveryFailed`(claimId 도 실어서). `DeliveryCreated` 에 claimId·pickupRequestId. 요청서 `delivery-dispatch-service.md` DD-3. claim 은 구현됨(미배포)

- [x] **DE-5 전달지 정확 좌표**(2026-11-18 PC 1 — 급함): drop-info·drop-location 이 주문 사본 좌표 없으면 dispatch `drop_lat·lng`, `coordinateSource` — 요청서 `delivery-execution-service.md` DE-5 — **2026-11-18 구현·배포(#183, execution 0.24.1)**
- [x] **RS-6 운행 시작·대기 시간**(2026-11-18 PC 1): 운행 구간 기록, onDutySince·waitingSince(관제 목록), 오늘 합계 — 대기 = 운행 중·진행 중 배송 0건(신규배차 꺼짐 제외), 요청서 `rider-service.md` RS-6 — **2026-11-18 구현·배포(#182, rider 0.11.0·tracking 0.9.0·execution 0.25.0)**

## 클라이언트(pmart-clients) — 클라이언트를 맡은 PC

백엔드에서 회신을 기다리는 것(자세한 내용은 각 handoff):
- [x] HTTPS 빌드 소스(vite.config 등) 커밋 — pmart-clients 13608a6(2026-11-13) — `docs/handoff/clients/archive/2026-11-13-all-reply-https-builds-published-http-closed.md`
- [x] 두 백오피스 브라우저 확인(사용자 2026-11-13 "HTTPS 전환했어"): `https://192.168.0.40`(백오피스)·`https://192.168.0.40:8444`(인사팀) 로그인·업무 화면 — 같은 문서
- [x] 예전 운영자 로그인 코드 삭제 뒤 `gen:api` — pmart-clients ca2ecf3(2026-11-13) — `archive/2026-11-13-clients-notice-auth-operator-login-removed.md`
- mfc_app 남은 일(정지 안내·실기기 로그인)은 **PC 3** 이 맡는다(클라이언트 알림 2026-11-13) — PC 3 절
- [x] 권한 기간 "기한 없음" 표시 — ca2ecf3(새 웹 빌드 올리기는 사용자 확인 뒤) — `archive/2026-11-13-clients-reply-hr-backoffice-deployed.md`
- 결과는 클라이언트 저장소 handoff 로 회신(백엔드는 읽기만).

## PC 4 — rag 영역(사용자 확정 2026-11-13, **2026-11-15 인수인계 — 시작해도 됨**)
- [ ] **CA-2 AI 상담 채팅 금칙어**(2026-11-23 PC 2 — `customer-assistant-service.md` CA-2): 욕설(막기 목록)은 받지 않고 안내(정지 없음), 위협·거친 말(정지 목록)은 `CustomerProfanityDetected` source AI_CHAT. 목록 키는 PC 1 과
- [ ] **AI-2·CA-1 상품 영양·알레르기 질문(2026-11-23 PC 1)**: search 0.11.0 결과의 `nutrition`·`allergens` 로 정해진 문장 — 목록에 없음은 "없다"가 아니라 "확인하지 못했어요". `docs/architecture/service-requests/ai-assistant-service.md` AI-2·`customer-assistant-service.md` CA-1
- [ ] **AI-1 업무 도우미 질문 원문 저장**(2026-11-19 PC 2 — 백오피스 웹 요청, 요청서 `docs/architecture/service-requests/ai-assistant-service.md` AI-1): **2026-11-19 사용자 결정(추천대로)**: `store-question` 켜기, 질문 원문 보존 = 답변과 같이 90일, 본인만 보기, 본인 지우기(원문만 NULL), 소급 없음. ADR-0036 결정 필요 3 고침
- [ ] **RAG 구조 개편 취소 알림(2026-11-19 사용자 "개편 일단 취소할거야")**: `docs/rag/planning/2026-11-18-es-hybrid-restructure-plan.md`·`…-es-restructure-decisions.md` 는 취소로 표시함(PC 1). RAG 전용 ES·1,024토큰 청킹·RRF 작업은 시작하지 않는다 — 지금 구현(pgvector) 그대로. 확인하면 이 줄을 지운다

- [ ] 공통 절 전부
- [ ] `docs/rag/HANDOVER.md` 읽기 — **§0(11-15 뒤 바뀐 것 — 재색인 필요, ADR-0062·0063·0064 를 넣을지)** → §6 "시작하면 먼저". 색인 범위는 `docs/rag/operator-index-scope.md`
- [x] ~~시작 전 사용자 결정: 개발 PC 에서 모델을 쓰는 방법~~ — 2026-11-15 사용자: PC 4 로컬로 먼저(local 프로필 + 필요할 때 로컬 ollama), 부족하면 PC 2 ollama 포트 열기를 그때 정함 → **2026-11-16 사용자 "지금 바로" — PC 2 ollama LAN 11434 열림**(rag-pgvector 는 닫힘, 대량 작업 전 알림). RAG LAN 위치는 PC 2(2026-11-15 PC 5 → PC 2)
- 주의: PC 4 에 게이트웨이·설정 서버·Eureka 가 있다 — 무거운 빌드·시험 중 LAN 이 느려지면 PC 1 에 알린다. 배포·LAN 재색인은 PC 1 — PR 에 "재색인 필요"를 적는다

## PC 2 — backoffice 영역(사용자 확정 2026-11-13, 로그인 마무리 뒤 시작)
- [ ] **R-16 고객 오배송 문의 → 채팅 선택 → MisdeliveryConfirmed**(2026-11-24 PC 1 — ADR-0086, 요청서 `case-service.md` R-16): NOT_RECEIVED + 전달 완료 배송이면 오배송 사건 자동, 고객 문의 채팅·선택 API·5시간 자동 환불, 통합 백오피스 처리 화면
- [x] **BF-4·R-12 센터 거르기**(2026-11-23 PC 1 — 사용자 "주문에 붙은 것만", 통합 백오피스 센터 고르기 #390): bff `views/claims`·`views/rider-complaints`·`views/pending-work`·`work-counts` `centerId`, case `rider-complaints/counts`·`obligations` `centerId`. 클레임 원천 CL-8 은 PC 1. — **bff 1차 구현**(2026-11-23 bff 0.24.0: claims·pending-work·work-counts), case R-12 구현(2026-11-23 case 0.33.0), bff 2차 rider-complaints(2026-11-23 bff 0.25.0) — **끝**
- [x] **R-11 직원 문의 목록 `riderId` 거르기**(2026-11-21 delivery — `case-service.md` R-11, 사용자 "응"): `GET /inquiries?riderId=` — 그 라이더의 라이더 문의만(RIDER_OPS·OPERATOR_ADMIN), 탈퇴 라이더 포함. 받는 화면 라이더 백오피스 라이더 상세 "문의"(PC 5). 급함 낮음 — **구현**(2026-11-21 case-service 0.32.0, 못 보는 직원 403)
- [x] **CS-MFC-Q 품질 사고 고객 영향 → 고객 사건**(2026-11-19 PC 3 — `case-service.md` CS-MFC-Q): mfc-quality `QualityIncidentImpactAssessed`(영향 LOT 이 나간 주문) 구독 → 고객센터 사건, caseId 를 돌려줄 사건 모양 정하기(PC 3 가 구독 붙임)

- **2026-11-16 인수인계 예정**(사용자 2026-11-15) — 그 전에 사용자가 브라우저·mfc_app 등 화면 확인을 마친다. 경계 확정(2026-11-15 사용자 "너의 추천대로"): backoffice-bff·case-service, 클라이언트 `web/backoffice`·`web/hr-backoffice`·`web/core`·`web/ui`(로그인·세션 코드는 PC 1 만). audit-service 는 PC 1 에 남는다(`pc-split.md` §1-1). **인수인계 문서 `docs/backoffice/HANDOVER.md`** — 시작하면 먼저 읽는다.
- 시작할 때: 공통 절 + 백엔드·클라이언트 두 저장소 포크(저장소마다 PR 따로).
- ~~`web/mfc-backoffice` 도 PC 2~~ — **2026-11-17 PC 3 으로 옮김**(사용자 "MFC 백오피스를 PC2에서 PC3로"). PC 2 에 남는 웹은 `web/backoffice`·`web/hr-backoffice`·`web/core`·`web/ui` — PC 3·5 가 웹 공통을 고칠 일은 PC 2 가 받는다.
- 11-16 오전 마지막 갱신: case 0.4.1(`InquiryAnswered` 발행·사건 refs requestId), 백오피스 라이더·배송 화면 빌드 LAN, 리뷰·알림(PC 1 영역 — 백오피스 화면 없음). `HANDOVER.md` 첫머리 참고.
- [x] ~~**MFC 센터 관리자 웹 — 본사 승인 번호·업무 내역·주문번호**~~ — 2026-11-18 PC 2: `web/mfc-backoffice` 는 2026-11-17 PC 3 으로 옮겨서 이 줄은 PC 3 몫(PC 3 절에 같은 줄이 필요하면 PC 1 이 옮김). 원문: (2026-11-17 PC 3 공지 `docs/handoff/clients/mfc-backoffice/2026-11-17-notice-mfc-approval-ref-and-new-fields.md`): 실사 승인·정정·폐기·격리 해제·운영 제한 해제가 기준 이상이면 422 APPROVAL_REQUIRED → details `approvalRequest`(JSON)로 승인 요청 → 승인 번호로 다시(정정은 같은 Idempotency-Key). 업무 내역(선택), orderNumber·handoverCode 표시. PR #116·#120·#123 병합·배포 뒤
- [ ] **HR-2 인사팀 앱 권한 요청 화면**(2026-11-17 PC 1 — 백엔드 hr-service 0.6.0·employee-auth-service 0.11.0 끝, LAN 배포 전): 역할 목록 `centerScoped` 대로 센터 칸 필수/숨김, 한 요청에 역할 한 번, 이미 가진 권한·기간 표시, 새 거절 400(`…[i].centers`·`…[i].role`)·409 `GRANT_ALREADY_ACTIVE`·503 처리, 기간 연장 없음(결정 대기) — handoff `docs/handoff/clients/hr-backoffice/2026-11-17-request-grant-request-validation.md`
- [ ] **인사팀 앱 새 거절 코드 `CENTER_ROLE_MIX`**(2026-11-18 PC 1 — hr-service 0.7.0, 센터마다 다른 MFC 역할 거절): 409 면 서버 `detail` 그대로 + `existingGrantId` 줄 강조, 같은 요청 행끼리는 400 `grants[i]` — handoff `docs/handoff/clients/hr-backoffice/2026-11-18-notice-center-role-mix.md`
- [ ] **인사팀 앱 권한 "연장" 버튼**(2026-11-18 PC 1 — HR-2 결정 ①, 백엔드 변경 없음): 끝나는 날이 있는 권한 줄에 "연장" → 같은 역할·센터, `validFrom` = 그 권한 `validUntil` 의 날짜로 새 요청, 회수 때 이어지는 줄 안내 — handoff `docs/handoff/clients/hr-backoffice/2026-11-18-request-grant-extension.md`
- [ ] **백오피스 고객센터 "주문 취소"**(2026-11-18 PC 1 — claim 0.4.0, 상품준비 전 주문 전체 취소, CS_AGENT·OPERATOR_ADMIN): 주문 상세 버튼(CONFIRMED 만), 사유·본인 확인 필수(반품과 같은 칸), PREPARING 뒤 안내 문구 — handoff `docs/handoff/clients/backoffice/2026-11-18-request-cs-order-cancel.md`
- [ ] **R-8 반품 회수 실패 → 상담원 재연락 사건 + 백오피스 반품 접수 화면**(2026-11-17 PC 1 — CL-1): case 가 `ReturnRecoveryFailed`(order.claim.v1) 구독 → 고객센터 사건(요청서 `case-service.md` R-8). 백오피스 고객센터 메뉴에 반품 접수(`POST /claims` type RETURN)·회수 조치(`POST /claims/{id}/recovery-actions`) — handoff `docs/handoff/clients/all/2026-11-17-request-return-recovery.md`. **백엔드 사건 구현 2026-11-17(case 0.22.0, PR — pmart-config 바인딩 `claimEvents` PC 1)**, 남은 것: 웹 화면(반품 접수·회수 조치·사건함 상담원 팀 종류)
- [x] **직원 웹 앱 바꾸기 메뉴(앱 런처 ⋮⋮⋮)**(2026-11-17 사용자 "지금 직원용 백오피스 4개", "같은 탭", "다 보여지는 방식" — 요청 `docs/handoff/clients/all/2026-11-17-request-app-launcher.md`): `web/ui` 에 `AppLauncher`(네 앱 모두에게 같게, 역할로 거르지 않음, 같은 탭 `<a href>`, 주소 = 지금 호스트 이름 + 포트 443·8444·8445·8446 / localhost 5173~5176) + `web/backoffice`·`web/hr-backoffice` 머리줄. 로그인·세션 코드는 건드리지 않음. 네 앱 빌드·시험 모두. PC 3·5 는 병합 뒤 한 줄씩 — **끝**(네 앱 모두 — 통합·인사팀은 웹 세션, MFC·라이더는 2026-11-20 pmart-clients #267)
- [x] **BF-1 화면 응답에 운영자 이름·사번**(2026-11-19 PC 1 — 원 요청 backoffice operator-display-names R2, 선택): 원천 hr-service 0.8.0 `GET /hr/employee-labels`. `docs/architecture/service-requests/backoffice-bff.md` BF-1 — **2026-11-19 구현(bff 0.17.0, #212)**

- [x] (2026-11-19 delivery) case R-9 배차 지연 → 고객센터 사건(`DispatchDelayed`) — **2026-11-19 구현·배포(case 0.27.0, #230), 기한 1시간 사용자 확정(#232)**

- [x] (2026-11-19 delivery) case R-10 — `HelpRequestReopened` 로 접수 건 다시 열기 + 결정 대기 전달 불가 접수 건 닫기 막기 — **끝**(case 0.28.0 #240, 닫기 막기는 2026-11-20 case 0.30.0 에서 뺌 — 닫기 = 실패 확정)

## 사용자가 할 일 (PC 가 아니라 사람)

- [ ] 다른 PC 에 비밀번호 파일 복사(필요한 PC·파일만 — `dev-on-other-pcs.md` §2)
- [ ] 결정: 개발 PC(PC 4)에서 ollama 쓰는 방법, backoffice 영역 경계, MFC↔인사 3개 (CSRF 는 2026-11-13 결정 — ADR-0059)
- [ ] (PC 3, 2026-11-18 delivery) PDA·센터 관리자 웹에 MFC 반납 인증번호 표시 — execution `GET /return-handovers?mfcId=`·`POST /return-handovers/{id}/code-reissues`(0.27.0, 회신 `docs/handoff/clients/rider-app/2026-11-18-reply-return-handover-code.md`)
