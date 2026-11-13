# 다른 PC 할 일 — PC 별 체크리스트 (2026-11-13 기준)

PC 1 이 아닌 PC(사람·Claude)가 **지금 해야 할 일**만 모은 목록. 방법은 `dev-on-other-pcs.md`, 영역·규칙은 `pc-split.md`, 읽을 문서 지도는 `other-pcs-docs.md`.
끝낸 줄은 그 PC 가 PR 로 `[x]` 로 바꾸고 날짜를 적는다(자기 PC 절만 고친다 — 다른 절은 PC 1 이 고친다). 새 할 일이 생기면 PC 1 이 여기에 더한다.

## 모든 다른 PC 공통 (개발 시작 전)

- [ ] 저장소 셋을 같은 부모 폴더의 형제로 받기 — `pmart-backend`·`pmart-clients`·`pmart-mfc-simulation`(드라이브 무관, `dev-on-other-pcs.md` §0)
- [ ] gh 설치·로그인(두 줄 따로: `winget install --id GitHub.cli -e` → 새 터미널에서 `gh auth login`) → 포크 만들고 `fork` remote 추가
- [ ] 비밀번호 파일은 필요한 것만 **`deploy/lan/.secrets/`** 에(사용자가 복사). 저장소 루트 `.secrets/` 에 두지 않는다. `keycloak-admin-password` 는 받지 않는다
- [ ] `./gradlew --stop` 한 번 — Gradle 사용자 단위 제한(workers 2·Xmx2g, 2026-11-13 PC 2~5 적용)이 새 데몬부터 적용된다
- [ ] 루트 CA: PC 2~5 Windows 에는 설치돼 있음. PDA·폰 실기기에서 직원 로그인하려면 기기에도 설치

## PC 3 — mfc 영역 (GitHub YOO196)

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
- [ ] 클라이언트 BR-52(PDA 업무 선택 배정)·BR-53(입고 수령 화면 값) 회신 — BR-52 R1·R3·R4 는 PR #9, **BR-53 R8~R11 회신**(PC 3, 2026-11-13, 브랜치 `mfc/inbound-labels` — `docs/handoff/clients/mfc-app/2026-11-13-reply-br53-inbound-receiving.md`). 남은 것 BR-52 R2·R5~R7·R12~R20

## PC 5 — delivery 영역

- [x] 공통 절 전부(gh·포크·`./gradlew --stop`) — 2026-11-13: 저장소 형제(`pmart-backend`·`pmart-clients`, 시뮬레이터는 delivery 에 불필요), gh `piaa1004` 로그인·포크 `piaa1004/pmart-backend`·`fork` remote, 비밀번호 파일 4개 `deploy/lan/.secrets/`(루트 `.secrets/` 없음), Gradle 제한 파일 있음·`--stop`, 루트 CA 설치 확인. 주의: gh 는 설치돼 있지만 PATH 에 없는 터미널이 있음(`C:\Program Files\GitHub CLI\gh.exe`)
- [x] `docs/delivery/HANDOVER.md` 읽기 — 2026-11-13. 남은 결정 중 AI 엔진(ADR-0058)·배달 시간 08~24시·카카오 키는 사용자가 정함(kickoff §6). 남은 것: 라이더 앱 기반·CP-1·오배송 확인 기록 주체
- [x] **PR #8 변경 요청 반영**(PC 1 검토 2026-11-13 — `gh pr view 8 --comments` 또는 GitHub PR 화면): H1 포장 전 주문 중지 때 배차 멈추기, H2 픽업 뒤 진행 중 수(배송 완료를 같이 만들거나 임시로 ASSIGNED 만), H3 주소·좌표를 URL 쿼리에서 빼기(추적 기록), M1 라이더 단위 잠금, M4 포장 목록 모를 때 PICKED_UP 금지, L1 취소 결과 권한. 다음 묶음: M2 재배차 길, M3 카카오 호출 줄이기 — 2026-11-13 전부 반영(H3 `337a2110`, H1·M4·M1·L1 `741fc90f`, M2·M3 `04bf6a2b`, H2 = delivery-execution 0.2.0 배송 수행·전달 완료 → dispatch CLOSED). 재검토 대기
- [ ] **PR #8 재검토 변경 요청 반영**(PC 1 2026-11-13 밤): ① 피킹 전 부분 중지(PARTIAL, `perPackage=[]`)를 전체 중지로 처리하는 것 고치기 ② 429 때 `later`(quota-backoff) 쓰기 ③ DeliveryCompleted.lines 포장마다 마지막 인계만 ④ 게이트웨이 사용자 종류 목록 다시 생성(optimizer `/health` 제외). 사용자 확인: F-2 "픽업 시작 전" ↔ "피킹 시작 전". 다음 묶음: 배송 실패 닫는 길, DispatchFailed 반복 의미, 경쟁 잠금, S3 트랜잭션 밖
- [ ] 배송 쪽 직원 시험 계정은 없다 — 배송 관리 API 를 직원 토큰으로 시험해야 하면 PC 1 에 요청(인사팀 등록으로 만든다)
- 카카오모빌리티 키(ADR-0058): LAN 설정 `delivery-tracking-service-lan.yml` 의 `pmart.tracking.kakao-mobility.rest-api-key`(`{cipher}`)·`base-url`(2026-11-13 PC 1 등록 — 주소 판정 키와 다른 앱 키). 길찾기 코드를 만들 때 이 이름으로 읽는다(다른 서비스에서 부르면 PC 1 에 옮겨 달라고). 같은 키 원본은 PC 5 `deploy/lan/.secrets/kakao-rest-api-key`, PC 1 은 `kakao-mobility-rest-api-key`
- 클라이언트 rider-app 영역도 PC 5(2026-11-13) — 라이더 앱 handoff `docs/handoff/clients/rider-app/`, 클라이언트 BR-23(포장 QR 원문)·BR-24(라이더 전달지 경로)는 배송·인계 쪽 열린 요청
- [ ] LAN 서비스가 많은 PC 다(남은 메모리 약 5GB, 2026-11-13) — 빌드·시험 중 LAN 서비스 상태가 나빠지면 PC 1 에 알린다

## 클라이언트(pmart-clients) — 클라이언트를 맡은 PC

백엔드에서 회신을 기다리는 것(자세한 내용은 각 handoff):
- [x] HTTPS 빌드 소스(vite.config 등) 커밋 — pmart-clients 13608a6(2026-11-13) — `docs/handoff/clients/archive/2026-11-13-all-reply-https-builds-published-http-closed.md`
- [x] 두 백오피스 브라우저 확인(사용자 2026-11-13 "HTTPS 전환했어"): `https://192.168.0.40`(백오피스)·`https://192.168.0.40:8444`(인사팀) 로그인·업무 화면 — 같은 문서
- [x] 예전 운영자 로그인 코드 삭제 뒤 `gen:api` — pmart-clients ca2ecf3(2026-11-13) — `archive/2026-11-13-clients-notice-auth-operator-login-removed.md`
- mfc_app 남은 일(정지 안내·실기기 로그인)은 **PC 3** 이 맡는다(클라이언트 알림 2026-11-13) — PC 3 절
- [x] 권한 기간 "기한 없음" 표시 — ca2ecf3(새 웹 빌드 올리기는 사용자 확인 뒤) — `archive/2026-11-13-clients-reply-hr-backoffice-deployed.md`
- 결과는 클라이언트 저장소 handoff 로 회신(백엔드는 읽기만).

## PC 4 — rag 영역(사용자 확정 2026-11-13, 영역 시작은 아직)

- [ ] 공통 절 전부
- [ ] `docs/rag/HANDOVER.md` 읽기
- [ ] 시작 전 사용자 결정: 개발 PC 에서 모델을 쓰는 방법(PC 4 에 로컬 ollama 또는 PC 5 ollama 포트 열기 — `pc-split.md` §1-1)
- 주의: PC 4 에 게이트웨이·설정 서버·Eureka 가 있다 — 무거운 빌드·시험 중 LAN 이 느려지면 PC 1 에 알린다

## PC 2 — backoffice 영역(사용자 확정 2026-11-13, 로그인 마무리 뒤 시작)

- 지금 할 일 없음 — 로그인 마무리 기준(`pc-split.md` §1-1: CSRF 쿠키 방식 LAN 켜기(ADR-0059 — 백엔드 구현 끝)·mfc_app 실기기) 뒤 시작. 그 전에 사용자 결정: 경계(case·audit 서비스, hr-backoffice·웹 공통 포함 여부).
- 시작할 때: 공통 절 + 백엔드·클라이언트 두 저장소 포크(저장소마다 PR 따로).

## 사용자가 할 일 (PC 가 아니라 사람)

- [ ] 다른 PC 에 비밀번호 파일 복사(필요한 PC·파일만 — `dev-on-other-pcs.md` §2)
- [ ] 결정: 개발 PC(PC 4)에서 ollama 쓰는 방법, backoffice 영역 경계, MFC↔인사 3개 (CSRF 는 2026-11-13 결정 — ADR-0059)
