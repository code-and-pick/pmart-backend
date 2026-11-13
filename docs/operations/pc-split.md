# PC 나눠 작업하기 — 영역·담당·기록 규칙 (2026-11-13)

여러 PC 가 같은 저장소를 동시에 고친다(사용자 결정 2026-11-12 PC 분담, 2026-11-13 "영역 나누기 해줘", 클라이언트는 "앱 단위로"). 같은 파일을 여러 PC 가 고치면 PR 을 합칠 때마다 충돌하고 누가 무엇을 했는지 섞이므로, **일지·handoff·소유를 영역 단위로 나눈다.** PC 담당은 바뀔 수 있어도 영역은 잘 안 바뀌기 때문에 PC 가 아니라 영역으로 나눈다.

## 1. 백엔드 영역

| 영역 | 지금 맡는 PC | 서비스·폴더 | 계약(`contracts/`) 주인 |
|---|---|---|---|
| **platform** | PC 1(192.168.0.32) — 배포 유일 지점 | auth-service·employee-auth-service·hr-service·approval-service·api-gateway·config·eureka·mail-provisioner, `libraries/`·`build-logic/`·`deploy/`, 그리고 아래 두 영역 밖의 모든 서비스(커머스·CDC·RAG 등 — 담당이 생기면 영역을 더한다) | 공통(`_common.yaml`·`_envelope`·`_defs`), 영역을 넘는 사건(`SubjectBlocked`·`ApprovalDecided`·`Employee*`·`GrantRequested` 등), platform 서비스 |
| **mfc**(인수인계 `docs/mfc/HANDOVER.md`) | PC 3(192.168.0.222) | mfc-inventory·mfc-fulfillment·mfc-capacity·mfc-location·mfc-quality·mfc-inbound-service, `docs/simulator/mfc/` | mfc 서비스 계약·mfc 안에서만 오가는 사건 |
| **delivery**(인수인계 `docs/delivery/HANDOVER.md`) | PC 5(192.168.0.101) | delivery-serviceability·delivery-capacity·delivery-dispatch·delivery-execution·delivery-tracking-service, rider-service, rider-auth-service | delivery 서비스 계약·delivery 안 사건 |

- 남의 영역 서비스·계약을 고쳐야 하면 직접 고치지 않고 `docs/architecture/service-requests/<받는 서비스>.md` 에 요청을 적는다(지금 규칙 그대로). 급하면 PR 을 열되 그 영역 담당이 리뷰한다.
- 영역을 넘는 사건·공통 계약은 platform 이 바꾼다(한 주인).
- 브랜치 이름 `<영역>/<주제>`(예 `mfc/count-variance`). **다른 PC 는 main 에 직접 넣지 않는다 — PR 만 올리고 PC 1 이 검토한 뒤 병합·배포한다**(사용자가 만든 구조, PC 마다 다른 GitHub 계정). PR 설명에 바꾼 것·실행한 시험·영향 영역·공용 표 갱신 필요를 적는다. PC 1 은 병합할 때 자기 일지 `## PR` 절에 한 줄(검토·배포 상태·후속 — `docs/worklog/README.md` 규칙 6). 따로 PR 기록 파일은 두지 않는다.

### 1-1. 예정 영역 (2026-11-13 — PC 는 사용자 확정: PC 4 = rag, PC 2 = backoffice. 영역은 아직 나누지 않음)

| 영역 | 언제 | PC(예정) | 범위(예정) | 정할 것 |
|---|---|---|---|---|
| **rag**(인수인계 문서 `docs/rag/HANDOVER.md`) | 지금 나눠도 됨(로그인과 겹치지 않음 — 직원 토큰 모양은 그대로) | **PC 4**(사용자 확정 2026-11-13). 주의: PC 4 에 게이트웨이·설정 서버·Eureka 가 있어 빌드·시험 부하가 LAN 전체에 번질 수 있다 — Gradle 제한(workers 2·Xmx2g) 유지, 무거운 시험 중 LAN 이 느리면 PC 1 에 알린다 | ai-assistant-service·customer-assistant-service, `docs/rag/`, `deploy/lan/rag/` | RAG 실행은 PC 5(ollama GPU·rag-pgvector, LAN 포트 없음) — 개발 PC 에 로컬 ollama 를 띄울지 PC 5 포트를 열지 |
| **backoffice** | **로그인 마무리 뒤**(아래 기준) — 남은 로그인 일이 `web/core`·백오피스·인사팀 화면과 겹친다 | **PC 2**(사용자 확정 2026-11-13). 백오피스 화면 서빙(nginx)은 계속 PC 4 — 배포는 PC 1 | 백엔드 `backoffice-bff`(+ case-service·audit-service 는 정할 것), 클라이언트 `web/backoffice`·`web/hr-backoffice`·`web/core`·`web/ui`(웹 공통 코드를 함께 — 한 주인). approval-service·hr-service·employee-auth-service 는 인증 중심이라 platform 에 남긴다(추천) | case·audit 포함 여부, hr-backoffice·웹 공통 포함 여부 |

**로그인 마무리 기준**(이것이 끝나면 backoffice 영역을 나눈다):
1. ~~두 백오피스 HTTPS 전환·HTTP 닫기~~ — **끝(2026-11-13)**: HTTPS 빌드 올림, 80·8081 은 HTTPS 로 301, 허용 목록에서 http 주소 제거. 클라이언트 HTTPS 빌드 소스 커밋(pmart-clients 13608a6), 브라우저 확인 끝(사용자 2026-11-13 "HTTPS 전환했어" — PC 1 에서 443·8444 200, 80·8081 → 301 확인).
2. ~~웹메일 로그인 방식~~ — **결정 2026-11-13: 합치지 않는다**(사용자 "안함"). 웹메일은 사서함 비밀번호, 사내 로그인은 Keycloak 비밀번호 — 지금 구조 그대로.
3. CSRF(웹 refresh token → HttpOnly 쿠키) — 할지·언제 할지 결정(지금 미룸).
4. mfc_app 실기기 확인 통과(클라이언트).

권한 변경·회수(BO-01)는 백오피스 업무 기능이라 backoffice 영역을 나눈 뒤 그 영역에서 해도 된다.

PC 2·PC 4 는 LAN 서비스를 돌리는 PC 다(2026-11-13 남은 메모리 PC 2 11GB·PC 4 10GB, 32GB 중). **2026-11-13 적용함**(사용자 요청): Windows 업데이트 정책(AUOptions 4·로그인 중 자동 재부팅 안 함·사용 시간 08~02)과 Gradle 사용자 단위 제한(`%USERPROFILE%\.gradle\gradle.properties` — `org.gradle.workers.max=2`, `-Xmx2g`, 프로젝트 설정보다 우선). PC 3·5 도 같은 날 같게 맞췄다(전에는 Windows 업데이트 정책만 있고 Gradle 제한 파일이 없었고, 사용 시간이 PC 3 09~02·PC 5 08~19 였다). 이제 PC 2~5 가 모두 같다. 이미 떠 있던 Gradle 데몬은 다음에 새로 뜰 때 제한이 적용된다(`./gradlew --stop`). 개발 시작 전 남은 것: 비밀번호 파일 복사·`git pull`(`dev-on-other-pcs.md`).

### 1-2. 영역 사이 알려진 연결

- **mfc ↔ platform(hr-service)**: mfc-capacity 작업자 명단(고용 형태·상태)을 인사와 따로 입력한다 — 퇴사·정지·계약 종료가 MFC 배정에 반영되지 않고 고용 형태를 두 곳에서 넣는다. mfc-capacity 가 `EmployeeRegistered·Suspended·Reinstated·Left`(이미 있는 사건, 고용 형태·소속 센터 포함)를 구독하면 풀린다 — mfc 영역 일. **결정 2026-11-13("추천대로")·구현 `d46671c`**: 자동 생성·정지/퇴사 자동 반영·고용 형태 정본 = 인사. 덧붙임(사용자): 센터 관리자는 작업자와 별개 — 자동 생성에서 빼는 일이 PC 3 에 남음(`other-pcs-todo.md`).
- **delivery**: 맞물리는 점 거의 없음(라이더 로그인 그대로, 배송 관리 API 를 직원으로 시험할 때만 토큰 받는 법이 바뀜).

## 2. 클라이언트 영역 (pmart-clients — 앱 단위)

| 영역 | 폴더 | PC(클라이언트 알림 2026-11-13, 사용자 "mfc-app 은 PC3, rider-app은 PC5야") |
|---|---|---|
| **customer-app** | `apps/customer_app` | PC 1(위주) |
| **mfc-app** | `apps/mfc_app` | **PC 3** — 백엔드 mfc 영역과 같은 PC |
| **rider-app** | `apps/rider_app` | **PC 5** — 백엔드 delivery 영역과 같은 PC |
| **backoffice** | `web/backoffice` | PC 2 가 시작하기 전까지 PC 1 |
| **hr-backoffice** | `web/hr-backoffice` | 같음(PC 2 로 함께 옮길지 미정) |
| **web-common** | `web/core`·`web/ui`·`tools/web-tools`(두 웹앱 공통 — 한 주인) | 같음 |

- 클라이언트 정본: pmart-clients `docs/operations/pc-split.md` §1, `docs/handoff/2026-11-13-backend-client-area-pcs-decided.md`.

클라이언트 저장소 규칙은 클라이언트가 정한다 — 백엔드는 `docs/handoff/clients/archive/2026-11-13-all-notice-area-split.md` 로 같은 방식을 제안했다.

## 3. 작업 일지 — 영역별 폴더

- **`docs/worklog/<영역>/YYYY-MM-DD.md`**(platform·mfc·delivery). 자기 영역 파일에만 쓴다. 2026-11-13 까지의 일지는 `docs/worklog/YYYY-MM-DD.md`(루트, 그대로 둔다).
- 다른 영역에 영향을 준 일은 자기 일지에 `영향: mfc` 처럼 적는다(남의 일지에 쓰지 않는다).
- 규칙은 그대로: 커밋 없이 일지만 쓰지 않고, 일지 없이 커밋하지 않는다(`docs/worklog/README.md`).

## 4. handoff — 받는 앱(영역) 폴더

- 백엔드 → 클라이언트: `docs/handoff/clients/<받는 앱 영역>/YYYY-MM-DD-<종류>-<주제>.md`(2026-11-13 사용자 결정 — 클라이언트 제안과 같은 폴더 구조). 받는 앱 영역 = §2 의 이름, 여럿이면 `all/`, 처리 끝나면 `archive/`. 종류: request·reply·notice. 첫머리에 **`보낸 영역: platform|mfc|delivery`**. 규칙 `docs/handoff/clients/README.md`.
  - 예: `mfc-app/2026-11-14-reply-worker-login.md`(보낸 영역 mfc), `backoffice/2026-11-14-notice-grant-approval.md`(보낸 영역 platform).
  - 각 앱 담당 PC 는 자기 앱 폴더와 `all/` 만 본다. 클라이언트 → 백엔드는 `pmart-clients/docs/handoff/<앱 영역>/`.
- 한 주제는 한 영역이 맡는다(같은 주제를 두 PC 가 따로 보내지 않는다). 인증·HR·배포·공통 계약은 platform.
- 2026-11-13 까지의 파일은 이름 그대로 폴더만 옮겼다(`…-clients-…`).
- 시뮬레이터 handoff(`docs/handoff/simulator/`)는 mfc 영역이 맡는다.

## 5. 여럿이 고치는 표 — 합칠 때 PC 1 이 몰아서

`docs/architecture/service-requests/README.md`, `docs/business/flows/pending-user-decisions.md`, `docs/architecture/events.md`·`service-map.md` 의 표처럼 여러 영역이 고치는 목록은, 다른 PC 는 **개별 파일(요청 파일·자기 일지)만** 고치고 표 갱신은 PR 설명에 적는다. PC 1 이 합치면서 표를 갱신한다(충돌을 한 곳으로 모은다). 자기 영역 줄 하나만 고치는 것은 괜찮다.

## 6. 다른 PC 가 알아야 할 사실은 저장소 문서에

Claude 메모는 PC 마다 따로라 서로 모른다. 다른 PC 도 알아야 할 사실(계정·토큰 받는 법·바뀐 규칙)은 메모가 아니라 저장소 문서에 둔다 — `docs/operations/dev-on-other-pcs.md`(개발 방법), `docs/operations/other-pcs-todo.md`(PC 별 할 일), `docs/operations/other-pcs-docs.md`(읽을 문서 지도), 이 문서(영역·기록 규칙). 루트 `CLAUDE.md` 가 두 문서를 가리킨다.
