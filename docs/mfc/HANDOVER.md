# MFC 인수인계 (2026-11-13)

mfc 영역(`docs/operations/pc-split.md` §1 — PC 3)을 받는 사람·Claude 가 **처음 읽는 문서**. 지금까지 PC 1 에서 한 MFC 작업의 상태·결정·남은 일을 한곳에 모았다(그동안은 PC 1 Claude 메모에만 있던 것이 많다). 세부는 링크를 따라간다. 이 문서가 다른 문서와 다르면 **업무 원문·ADR·계약이 정본**이다.

## 0. 시작 전에

1. `docs/operations/pc-split.md`(영역·일지·handoff 규칙), `docs/operations/dev-on-other-pcs.md`(직원 토큰·시험 계정·비밀번호 파일)를 읽는다.
2. 작업 일지는 `docs/worklog/mfc/YYYY-MM-DD.md`, 브랜치 `mfc/<주제>`. **main 에 직접 넣지 않는다 — PR 만, PC 1 이 검토 뒤 병합·배포.** 배포·`.secrets` 원본·Jib push 는 PC 1.
3. 저장소 위치는 어디든 된다(드라이브 무관). 다만 **`pmart-backend`·`pmart-clients`·`pmart-mfc-simulation` 을 같은 부모 폴더의 형제로** 둔다 — 시뮬레이터·클라이언트 문서·도구가 `../pmart-…` 를 가정한다.
4. **OR-Tools 시험**: PC 3 은 Windows 애플리케이션 제어 정책이 OR-Tools 네이티브 DLL 을 막는다 → `tools/ortools-test-docker.sh` 로 Docker 안에서 돌린다(2026-11-12 PC 3 37/37 확인. SSH 로 할 때는 `DOCKER_CONFIG=~/.docker-pmart` + Docker Desktop `resources/bin` 경로, PC 앞에서는 그냥 됨). 그 밖의 시험은 그대로 `./gradlew :services:<mfc 서비스>:check`.
5. **시뮬레이터는 1차 범위에서 하지 않는다**(사용자 2026-11-13 "1차적으로 지금당장 mfc 작업할때 시뮬레이션은 안할 예정"). 백엔드 서비스·계약·시험(`check`·`mfc_e2e.py`)으로 확인한다. `pmart-mfc-simulation` 저장소는 지금은 받지 않아도 된다. 시뮬레이터에 맞춘 것(아래 §3 시험 데이터 평면도, 작업 시간 처리 값)은 그대로 쓰되 시뮬레이터 쪽 요청·handoff(`docs/handoff/simulator/`)는 새로 만들지 않는다 — 다시 할 때 사용자가 정한다.

## 1. 서비스 6개 (모두 LAN PC 3 에서 돈다)

| 서비스 | 맡는 것 | 규칙 문서 |
|---|---|---|
| mfc-inventory-service | 재고 원장(로케이션·SKU·LOT·품질·작업 단위), 예약(FEFO·LOT 단위), 입고 검수·적치, 피킹·포장 이동, 결품 보고·재확보, 실사·조정, 품질 전기, 스냅샷 | `services/mfc-inventory-service/CLAUDE.md` |
| mfc-fulfillment-service | 작업 지시(작업·포장·출고), PICK·PACK 실행, 밀봉 → 출고 준비(READY), 배차 요청, 인계(펜스·QR), 전체 중지·복귀, 재포장 | `services/mfc-fulfillment-service/CLAUDE.md` |
| mfc-capacity-service | 작업자·업무·자격·근무·일일크루, 배정(수동·SYSTEM "다음 작업 받기"·OR-Tools 배정 계획 CP-SAT), 피킹 동선(OR-Tools Routing), 처리량·운영 제한, 휴식·작업 내려놓기·지연/무활동 표시 | `services/mfc-capacity-service/CLAUDE.md` |
| mfc-location-service | 로케이션 마스터(층·구역·온도대·좌표·크기, 계층 코드), 거리 계산 | `services/mfc-location-service/CLAUDE.md` |
| mfc-quality-service | 격리·해제 승인·폐기, 반품 검수 | `services/mfc-quality-service/CLAUDE.md` |
| mfc-inbound-service | 입고 예정(INITIAL_STOCK 승인 = 초기 재고), 현장 입고·검수·적치 작업, 공급 요청 | `services/mfc-inbound-service/CLAUDE.md` |

- 업무 원문 `docs/business/MFC-business-logic.md`(IV-01~13·WK-01~14), 책임 경계 `docs/architecture/service-map.md` §5, 상태 `contracts/states/mfc-states.md`, 계약 `contracts/openapi/mfc-*.yaml`.
- 주요 ADR: 0040(재고 모델 v2) · 0041(상품별 소비기한 보장) · 0042(LOT 대체·홀드 TTL·로케이션 코드) · 0043(피킹·포장) · 0044(결품 홀드·재확보) · 0045·0049(결품 = 항상 부분 취소·환불) · 0046(피킹 뒤 취소·복귀) · 0047(포장 인계 펜스) · 0048(품질·실사·운영 제한) · 0050(OR-Tools 배정·동선, JGraphT 안 씀) · 0053(OR-Tools 서비스는 JVM — Native 금지).

## 2. 지금 상태 (LAN, 2026-11-10 기준 + 이후)

- 6개 서비스 업무 원문대로 구현·배포(2026-11-07~09 슬라이스 6a·6b·6c, IV-08·09, 밤샘 작업 — 반품 재포장·실사·품질 격리/폐기·운영 제한·공급 요청 등).
- **LAN 끝까지 시험**: `deploy/lan/checks/mfc_e2e.py`(66/66, 11-09) — 예약 → 확정 → 작업 접수 → PICK 배정·동선(ROUTING)·피킹 → PACK → 배차 요청 → 밀봉 READY → 인계 → 전체 중지 → 복귀(RETURN) → 재고 원복 → 이동 지시·다음 작업 받기(MOVE) → 배정 계획·입력. 2026-11-13 부터 직원 토큰은 `staff_auth.py`(시험 계정 `claude-mfc-sup`·`claude-mfc-wrk`, 센터 `MFC-SEED-01`).
- **첫 실주문(11-10)**: 고객 앱 실주문 → 결제 → 작업 → 피킹·포장 → 밀봉 → **출고 준비(READY)에서 멈춤** — 배송(delivery-dispatch)이 `DispatchRequested` 를 아직 받지 않는다(delivery 영역 DD-1).
- 추적: 6개 서비스 HTTP·Kafka 가 한 traceId 로 이어짐(Zipkin 192.168.0.40:9411, 약 2일 보관). 서비스 **안의** 구간(span)은 없다 — §5.
- 시험 데이터: `MFC-SEED-01` 를 시뮬레이터 임시 평면도대로 다시 등록(11-09, `deploy/lan/seed/mfc_layout.py` — 선반 칸 6,762·팔레트 32, 재고 8,418). 시드 원본 `deploy/lan/seed/Seed.java`(상품·가격·권역·초기 재고).

## 3. 결정 — 어디에 적혀 있나

| 무엇 | 위치 |
|---|---|
| **결정 대기 목록(한곳)** | `docs/business/flows/pending-user-decisions.md` — 결정되면 여기에 옮겨 적는다 |
| 내가 정한 기본값(사용자 검토 대기) | `docs/business/flows/mfc-defaults-status.md`, 일지 2026-11-08 "사용자 확인 없이 정한 것"(#1~81) |
| 포장 인계 결정 D-1~D-9 | `docs/business/flows/package-handover-decisions.md`, 계약 `package-handover-contract.md` |
| OR-Tools 쓰는 곳·진행 | `docs/architecture/or-tools-usage.md` |
| 백오피스 역할별 메뉴(임시 기본값) | `docs/architecture/backoffice-role-menus.md` |

사용자가 정한 주요 규칙(요약 — 정본은 위 문서):
- 결품 = 항상 부분 취소·환불(남은 주문 전체가 결품이면 전체 취소). 다른 LOT 대체 허용(같은 SKU·소비기한 적격). 홀드 TTL 10분(설정).
- 작업자: 센터 관리자가 등록, **로그인 정보와 업무는 분리**(업무·자격은 이력 남는 별도 기록). 모든 현장 작업자(정규·알바·일일)는 시스템이 하나씩 배정("다음 작업 받기"), 관리자는 덮어쓰기만. 정규 = 관리자가 업무, 일일 = 그날 유효 자격 전부(수요 기반 나누기는 운영에서 필요해질 때). MFC 에는 온도대 자격 없음(CDC 만).
- 처리량·입고: 포장대 4·75%, PICK 7분·PACK 4분, 시간당 45, 입고 하루 720·도착당 360·도크 1(07~10시). 배정 목표 순서: 미배정 > 지연 > 이동[평면도 뒤] > 불균형, 5초, 자동 확정 없음.
- 인계: D-1 C(같은 라이더 재스캔은 확인 없이, 그 밖은 점원 확인), 펜스 TTL 기본 3분, MFC 는 라이더 도착을 보지 않음(QR 바깥 랙), 포장 중 배차 요청 + READY 자동.
- 시뮬레이터는 실제 현장 대신 보여 주는 것(1차 MFC 작업에서는 쓰지 않음 — §0-5) — **맞지 않으면 시뮬레이터가 바뀐다**(백엔드 계약·업무 문서가 정본). 결정 기록 `docs/simulator/mfc/mfc-simulator-integration.md`(SIM-1~11), 평면도 `docs/simulator/mfc/layout/`, 범위 `docs/simulator/scope.md`. 시뮬레이터 handoff 는 `docs/handoff/simulator/`(mfc 영역).

## 4. 다른 영역·서비스와 걸린 것

| 무엇 | 상태 | 어디 |
|---|---|---|
| 배송이 출고 준비를 받아 배차(DD-1), 픽업 = 포장별 인계 확정(DE-1), 라이더 상태(RS-1), 용량 감소(CP-1) | 배송 쪽 대기 — **delivery 영역(PC 5)** | `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`, `delivery-*.md` |
| 운영 제한 사건 → fulfillment 거절(F-1) | 대기(mfc 안) | `service-requests/mfc-fulfillment-service.md` |
| MFC 승인 번호 실제 검증(AP-1) — 지금 `RECORD_ONLY`(LAN 만) | 운영 전 필수, Q-11 | `service-requests/approval-service.md` |
| 반품 클레임·회수비·검수 뒤 환불(CL-1), 결품 고객 안내(N-1), 인계 → 주문 상태(O-1) | 대기 | `service-requests/claim-service.md`·`notification-service.md`·`order-service.md` |
| **작업자 명단 ↔ 인사(hr-service, 2026-11-13 새로)** | **결정·구현 2026-11-13**(사용자 "추천대로" + "센터 관리자는 작업자와 별개"): 그 센터 `MFC_WORKER` 권한(`GrantApplied`)이 붙을 때 작업자 생성, 퇴사·정지 자동 반영, 고용 형태 정본 인사, 권한 기간 밖이면 배정 안 됨(센터 이동). mfc-capacity V14·V15. LAN 은 PC 1 이 설정 저장소에 구독 바인딩 2개를 넣고 배포해야 켜짐. 권한 조기 회수 사건 요청 EA-1 | `services/mfc-capacity-service/CLAUDE.md`, `service-requests/employee-auth-service.md` |
| PDA 앱(mfc_app) 직원 로그인 | 클라이언트 구현 끝(a766d36), 실기기 확인 대기 | 클라이언트 handoff `2026-11-13-clients-request-mfc-app-employee-login.md` |

## 5. 남은 일 (mfc 영역)

- 서비스 안 구간(span) 열기 — OR-Tools(`CpSatPlanner`·`OrToolsRouter`, 상태·제한 시간·작업 수 태그), 재고 할당·예약·전기, 작업 중지·인계·작업 실행. 먼저 actuator 구간 빼기(Zipkin 2일 보관·용량). 사용자 "해야 할 목록"(11-10), 시작 지시 대기.
- E2E 에 아직 없는 것: 결품 사가 환불, 피킹 뒤 부분 중지(미구현), 라이더 인계 확정(배차 없음), 반품 재포장·실사·품질 격리·운영 제한·공급 요청, 입고 → 검수 → 적치 단계.
- 일일크루 그날 업무를 수요로 나누기(지금 = 유효 자격 전부, 사용자 "일단 유지").
- OR-Tools 배정 계획에 이동 포함은 됨, 작업 시간 추정의 처리 값은 시뮬레이터 값(임시).
- 첫 실주문 초안 `DRF-5099bf37…` 의 "차단 1" 원인 미기록.
- eureka-server 로그 "Batch update failure HTTP 401"(하루 약 9만 줄, 등록은 정상) — 조사 안 함.
- 결정 대기(`pending-user-decisions.md`): 남은 인계 결정(D-7 등), Q-08 가중치, 위험 추가 승인(Q-11) 기준 등.

## 6. 시험·도구

| 무엇 | 경로 |
|---|---|
| 서비스 시험 | `./gradlew :services:mfc-<…>-service:check` (OR-Tools 는 `tools/ortools-test-docker.sh` — PC 3) |
| LAN 끝까지 | `python -I -X utf8 deploy/lan/checks/mfc_e2e.py`(직원 토큰 `deploy/lan/checks/staff_auth.py`, 비밀번호 파일 `.secrets/keycloak-claude-mfc-{sup,wrk}-password`·`keycloak-claude-dev-password`) |
| 로컬 직원 토큰 | `POST http://localhost:8511/api/v1/internal/dev-tokens`(local 프로필 employee-auth-service) — `dev-on-other-pcs.md` |
| 평면도 재등록 | `deploy/lan/seed/mfc_layout.py`(센터 관리자 토큰) |
| 작업자 ID | 운영자 ID(`OP-…`) = 토큰 `sub`. `claude-mfc-wrk` = `OP-d696814f-4cd7-4263-86a6-032b591e5b42`, `claude-mfc-sup` = `OP-88399b05-87b7-48e2-aa33-229a3ab36f93` |
| 새 작업자 | 인사팀 백오피스(`https://192.168.0.40:8444`)로 등록 → 소속 센터 `MFC_WORKER` 자동 승인 → 소속 센터 `MFC_WORKER` 자동 승인(`GrantApplied`) → mfc-capacity 작업자 자동 생성(2026-11-13 — §4. 센터 관리자는 `MFC_WORKER` 권한이 없으면 작업자가 아님) → 센터 관리자가 자격·업무 지정(정규직·알바) |
