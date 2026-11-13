# 다른 PC 가 읽을 문서 — 경로·파일 이름 (2026-11-13)

PC 1 이 아닌 PC(사람·Claude)가 **어떤 문서를 어떤 순서로** 읽는지 모은 지도. 모두 `pmart-backend` 저장소 기준 경로. 읽기 전에 `git pull origin main`.
할 일 체크리스트는 `other-pcs-todo.md`, 방법은 `dev-on-other-pcs.md` — 이 문서는 그 둘을 포함한 **읽을 목록**만.

## 1. 모든 PC — 먼저 이 순서로

| 순서 | 파일 | 내용 |
|---|---|---|
| 1 | `CLAUDE.md` | 저장소 규칙. "PC 나눠 작업하기" 절에 아래 문서 링크·PR 규칙 |
| 2 | `docs/operations/other-pcs-todo.md` | **PC 별 지금 할 일 체크리스트** |
| 3 | `docs/operations/dev-on-other-pcs.md` | 방법: 저장소 위치·gh 설치·포크 PR 순서, 직원 토큰 받는 법, 시험 계정·비밀번호 파일 |
| 4 | `docs/operations/pc-split.md` | 영역(platform·mfc·delivery, 예정 rag·backoffice)·담당 PC·계약 주인·일지·handoff 규칙 |
| 5 | `docs/worklog/README.md` | 일지 쓰는 법 — 자기 영역 폴더 `docs/worklog/<영역>/YYYY-MM-DD.md`(2026-11-14 부터) |

## 2. PC 별로 더

| PC(영역) | 파일 |
|---|---|
| **PC 3**(mfc) | `docs/mfc/HANDOVER.md`(인수인계). 일지 `docs/worklog/mfc/`. 시뮬레이터 문서(`docs/simulator/`)는 1차 MFC 작업에서 안 읽어도 됨(시뮬레이터 안 함 — 사용자 2026-11-13) |
| **PC 5**(delivery) | `docs/delivery/HANDOVER.md`(인수인계) → 그 안 1번 `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(작업 지시서). 일지 `docs/worklog/delivery/` |
| **PC 4**(rag, 시작 전) | `docs/rag/HANDOVER.md`(인수인계) |
| **PC 2**(backoffice, 로그인 마무리 뒤) | 아직 없음 — 영역을 나눌 때 `docs/backoffice/HANDOVER.md` 를 만든다 |

## 2-1. 지난 작업 일지 — 영역별로 볼 날짜

2026-11-13 까지의 일지는 `docs/worklog/YYYY-MM-DD.md`(루트, **모든 영역이 한 파일에 섞임**)다. 아래 날짜에서 `## ` 제목으로 자기 영역 항목을 찾는다. 빠진 날이 있을 수 있으니 `grep -il "<서비스 이름>" docs/worklog/2026-*.md` 로도 찾는다. 2026-11-14 부터는 `docs/worklog/<영역>/`.

| 영역 | 날짜(루트 일지) | 무엇 |
|---|---|---|
| mfc | 10-30, 11-07, **11-08**(MFC 대부분 구현 — 품질·실사·제한·재포장 등), 11-09(임시 평면도대로 칸 다시 등록·근무 기본값·OR-Tools 보완), 11-10(첫 실주문 → 출고 READY), 11-12(OR-Tools 시험 Docker, PC 3) | `docs/worklog/mfc/2026-11-13.md`(PC 3 첫 일지)도 |
| delivery | 10-30·10-31(권역 매핑·주소 판정·예약 슬롯), **11-10**(PC 5 로 이동·배송 DB 사고·rider-auth-service 구현), 11-11(rider-auth-service 첫 LAN 배치) | |
| rag | `docs/rag/worklog.md`(11-04~11-10 RAG 전용), 루트 11-04(1차), 11-11(PC 5 이동·재색인) | |
| platform·로그인 | 11-12·11-13(ADR-0056 직원 로그인·HR·HTTPS·PC 나누기) | backoffice 영역을 나눌 때 PC 2 가 볼 것 |

## 2-2. handoff — 영역별 관련 파일

클라이언트에 보낸 것 `docs/handoff/clients/<앱 영역>/`·`all/`(남은 것만 — 끝난 것은 `archive/`, 2026-11-13 앱 영역 폴더), 클라이언트가 보낸 것 `pmart-clients/docs/handoff/<앱 영역>/`·`all/` + 상태표 `pmart-clients/docs/backend-requests.md`(BR 번호, 읽기만). 아래 경로는 `docs/handoff/clients/` 기준.

| 영역 | 백엔드가 보낸 것(`docs/handoff/clients/`) | 클라이언트가 보낸 것·열린 질문 |
|---|---|---|
| mfc | `all/2026-11-07-clients-request-mfc-inbound-screens.md`, `all/2026-11-07-clients-request-mfc-picking-packing-screens.md`, `all/2026-11-07-clients-request-shelf-life-screens.md`, `mfc-app/2026-11-08-clients-notice-handover-check.md`, `mfc-app/2026-11-08-clients-notice-pda-response-plan.md`, `backoffice/2026-11-09-clients-notice-assignment-plan-094.md`, `backoffice/2026-11-09-clients-notice-plan-input.md`, `backoffice/2026-11-09-clients-notice-worker-speed-and-plan-estimate.md`, `mfc-app/2026-11-13-clients-request-mfc-app-employee-login.md`, `mfc-app/2026-11-13-clients-reply-mfc-app-login-and-https-build.md` | `2026-11-10-backend-dispatch-manual-only.md`, `2026-11-10-backend-pda-work-areas.md`. **회신 없음: BR-51**(배차 요청은 점원만 — 백엔드 D-5 READY 자동 요청과 충돌, 사용자 확인 중), **BR-52**(PDA 업무 선택 배정), **BR-53**(입고 수령 화면 값). 시뮬레이터 handoff(`docs/handoff/simulator/`)는 1차에서 안 봄 |
| delivery | `rider-app/2026-11-10-clients-notice-rider-google-login.md`, `customer-app/2026-10-31-clients-notice-delivery-and-order-draft.md`(배송 방식·슬롯 — §5 열린 것) | 라이더 앱 요청은 아직 없음 |
| rag | `backoffice/2026-11-04-clients-request-ai-assistant.md`(운영자 질문 화면 — 지금도 참조), `customer-app/2026-11-09-clients-request-customer-chat-assistant.md`(고객 앱 채팅 — 화면 아직 없음) | |
| backoffice(나중) | `backoffice/2026-11-09-clients-request-backoffice-role-menus.md`, `all/2026-11-09-clients-request-customer-inquiry-page.md`, `hr-backoffice/`·`backoffice/` 폴더 | BR-25(상품 이미지 표시) |

## 3. 필요할 때

| 파일·폴더 | 언제 |
|---|---|
| `docs/architecture/service-requests/README.md` | 다른 영역 서비스에 API·사건이 필요할 때 — 받는 서비스 파일에 요청을 적는다. 내 영역 서비스로 온 요청도 여기 |
| `docs/architecture/service-map.md` | 어느 서비스 책임인지 모호할 때 |
| `docs/architecture/events.md` | 사건 발행자 → 구독자 |
| `docs/handoff/README.md` | 클라이언트·시뮬레이터 저장소와 주고받을 때. 클라이언트에 보낸 것 `docs/handoff/clients/`(처리 끝난 것은 `archive/`), 클라이언트가 보낸 것은 `pmart-clients/docs/handoff/`·`pmart-clients/docs/backend-requests.md`(BR 상태표, 읽기만) |
| `docs/business/flows/pending-user-decisions.md` | 사용자 결정 대기 목록 |
| `docs/business/*-business-logic.md` | 업무 원문(확정 정책 — 설계가 바꾸지 않는다. 없는 규칙은 사용자에게 묻는다) |
| `docs/adr/0056-staff-login-employee-auth-service-and-hr-service.md`, `docs/architecture/auth/employee-auth-structure.md` | 직원 로그인 구조 — 토큰·권한이 왜 이렇게 됐는지 |
| `docs/adr/0057-lan-dev-https-private-ca.md` | LAN HTTPS·루트 인증서 |
| `deploy/lan/checks/staff_auth.py` | LAN 직원 토큰 받는 스크립트 |
| `services/<서비스>/CLAUDE.md` | 그 서비스 안 규칙·상태(고치기 전에) |

## 4. 다른 PC 에 시작할 때 보낼 말

> `git pull origin main` 한 뒤 `CLAUDE.md` → `docs/operations/other-pcs-todo.md` 순서로 읽고, 내 PC 절의 체크리스트대로 진행해 줘. 읽을 문서 지도는 `docs/operations/other-pcs-docs.md`.

문서가 새로 생기거나 경로가 바뀌면 PC 1 이 이 표를 고친다.
