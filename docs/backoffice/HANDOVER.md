# 백오피스(backoffice) 인수인계 (2026-11-15 작성, **2026-11-16 갱신 — PC 2 로 넘김**, **2026-11-18 PC 2 갱신 — §2·§4·§5·§6, 맨 위 "지금 상태"**)

## 지금 상태 (2026-11-18 PC 2 — 이것부터)
- **백엔드에 열린 일 없음.** PC 2 몫 서비스 요청(R-1~R-8, BO-12)은 모두 구현·배포 또는 취소·거둠. 열린 PR 없음.
- **최근(11-17~18)**: R-8 반품 회수 실패 → 상담원 사건(case 0.22.0), 반품 회수 실패 메모 claim 0.3.0 필드(0.22.x), 명령 결과 조회(bff 0.12.0), **지표 CASE_SLA**(case 0.24.0 `/cases/metrics/sla` + bff 0.13.0 `views/metrics`), 반품 끝남 이력 `CLAIM_CLOSED`(ClaimClosed 구독), 사건 이력 이름 목록(계약 0.24.1), LAN 확인 각본 `deploy/lan/checks/backoffice_views_check.py`.
- **기다리는 것**: 결품률 지표 = PC 3 F-10(분모), 정시 배송률 = DD-4 누가 셀지 결정(사용자·PC 5, 급하지 않음), 반품 취소 회수비 판정 필드 = claim CL-2(PC 1) → 나오면 웹에 알림, 포장 인계 단계 표시 LAN 확인 = 진행 중 출고가 생길 때.
- **웹(같은 PC 다른 세션 "프론트 백오피스")**: PR 을 앞 PR 병합 순서대로 올리는 중 — 주문 취소(#105) → … → 처리 기한 지킴(CASE_SLA), 인사팀 앱(권한 요청·CENTER_ROLE_MIX·연장), 앱 런처(#92). LAN 웹은 PC 1 `lan.sh web-sync` 가 병합 뒤 자동으로 맞춘다(`docs/handoff/clients/all/2026-11-18-notice-lan-web-sync.md`).
- **사용자가 정할 것(막히지 않음 — 필요해질 때 묻는다)**: DD-4, 백오피스 영역 경계, MFC↔인사 3개, 비밀번호 파일 복사.

보낸 영역: platform(PC 1). 받는 영역: backoffice(PC 2). 경계는 `docs/operations/pc-split.md` §1-1(사용자 2026-11-15 "너의 추천대로").

- **넘기는 시점(2026-11-16)**: 사용자 결정으로 PC 1 이 넘기기 전에 백엔드를 먼저 만들었다 — 권한 관리(BO-01), 사건함(case R-4·R-6·R-7), 처리 대기(BO-12), 개인정보 열람 기록 조회, 세션 센터 이름.
- **화면: 지금까지 클라이언트에 보낸 요청(§3 표)은 지금 클라이언트 쪽이 끝까지 한다**(사용자 2026-11-16 "클라이언트쪽에 보낸 요청까지는 다하기로"). PC 2 는 그 뒤의 일부터 맡는다 — 받은 화면은 확인만(§4-1).
- 처음 할 일: §1 먼저 읽을 것 → `./gradlew :services:backoffice-bff:check :services:case-service:check` 가 PC 2 에서 통과하는지 → §4.
- **11-16 오전 PC 1 이 더 한 것(PC 2 에 닿는 것)**: case-service 0.4.1 — 상담원 문의 답변 때 `InquiryAnswered` 발행(고객 알림함, ADR-0064), 도움요청·전달 실패 사건 `refs` 에 `requestId`(클라이언트 질문). 클라이언트가 라이더·배송 직원 화면을 붙였고(f50025c) 그 빌드를 LAN 에 올림. 리뷰(review-service, ADR-0063)·고객 알림함(notification-service, ADR-0064)은 PC 1 영역 — 백오피스 화면은 아직 없다(§4-5).

## 0. 맡는 것·안 맡는 것

| | PC 2(backoffice) | PC 1(platform)에 남음 |
|---|---|---|
| 백엔드 | **backoffice-bff**, **case-service**(문의·컴플레인·사건함) | **audit-service**(개인정보 열람 원장 — ADR-0062), approval-service·hr-service·employee-auth-service(권한 API 포함), order·order-orchestration, 게이트웨이, 라이브러리, **배포 전부** |
| 클라이언트(pmart-clients) | `web/backoffice`, `web/hr-backoffice`(인사팀 화면), ~~`web/mfc-backoffice`~~(2026-11-16~17 PC 2 → **2026-11-17 PC 3 으로 옮김**, 사용자 "MFC 백오피스를 PC2에서 PC3로"), `web/core`·`web/ui`(웹 공통 — **로그인·세션·토큰 부분 제외**) | 로그인·세션·토큰 코드 전부(§1-3) |
| PC 3(mfc) | **`web/mfc-backoffice` 화면(2026-11-17 부터)**과 그 화면이 부르는 MFC 백엔드 API(MB-1~3 — `docs/architecture/service-requests/mfc-backoffice-staff-apis.md`). 화면에 필요한 MFC API 는 PC 3 에 요청 | |

- **로그인 코드는 PC 1 만**(`pc-split.md` §1-3): `web/core` 의 `employeeAuth.ts`·`session.ts`·`sessionPersistence.ts`·`tokens.ts`·`pmartFetch.ts` 갱신 부분, 웹 앱 `src/features/session`. 바꾼 PR 은 PC 1 이 돌려보낸다. 필요하면 handoff 로 PC 1 에 요청.
- 웹 공통(`web/core`·`web/ui`)은 네 웹 앱(backoffice·hr-backoffice·mfc-backoffice·rider-backoffice)이 같이 쓴다 — 바꿀 때 네 앱 빌드를 같이 본다. **2026-11-17 부터 mfc-backoffice 는 PC 3, rider-backoffice(새 앱)는 PC 5** 지만 웹 공통 주인은 PC 2 그대로 — PC 3·5 의 웹 공통 변경 요청을 PC 2 가 받는다.
- **mfc-backoffice**: LAN https://192.168.0.40:8445(빌드 `index-ChSinewe.js`, 올리기는 PC 1 `lan.sh publish-mfc-backoffice <dist>`). 첫 요청서 `docs/handoff/clients/mfc-backoffice/2026-11-15-request-mfc-backoffice.md`. 업무는 MFC 원문(`docs/business/MFC-business-logic.md`)·`docs/mfc/HANDOVER.md` — MFC 업무 규칙은 추측하지 말고 PC 3·사용자에게 묻는다.
- 권한·인사·주문·사가 API 가 더 필요하면 PC 1 에 요청(`docs/architecture/service-requests/`). audit-service 계약(`/internal/pii-access-logs`)에 새 `subjectType`·항목 코드가 필요할 때도.

## 1. 먼저 읽을 것 (순서대로)
1. `docs/operations/dev-on-other-pcs.md`(방법·PR 순서), `other-pcs-todo.md`(PC 2 절), `other-pcs-docs.md`(문서 지도·직원 토큰)
2. `docs/business/BackOffice-business-logic.md` — 업무 원문 BO-01~BO-28(확정 정책, 추측 금지)
3. `services/backoffice-bff/CLAUDE.md`, `services/case-service/CLAUDE.md`, `docs/architecture/service-map.md` 의 두 서비스 절
4. 받은 요청·결정: `docs/architecture/service-requests/backoffice-bff.md`(BO-12 — 결정·구현), `case-service.md`("사건함 공통 결정", R-3~R-7)
5. ADR: 0059(refresh 쿠키·CSRF), 0060(고객센터 취소 없음), 0061(로그아웃 전파), **0062(개인정보 원문 열람 = 사유 + audit 동기 기록, 기록 조회는 OPERATOR_ADMIN)**
6. 권한 흐름: `docs/business/flows/employee-account-provisioning.md` §1(BO-01 결정·구현), `docs/architecture/auth/keycloak-manage-users-and-grant-revocation.md`(권한 회수 구조)
7. 클라이언트에 보낸 요청: §3 표의 파일들

## 2. 서비스 상태 (2026-11-17 밤 갱신 — case 0.19.0·bff 0.9.1, **11-18 덧붙임 아래**)

- **2026-11-18 기준 버전**: **bff 0.13.0**(0.10.0 사건함 myLastSeenAt, 0.11.0 포장 인계 현황 `packing-handover`(PC 3 F-9, 단계 stage), 0.12.0 명령 결과 조회, 0.13.0 지표 CASE_SLA — LAN 배포 PC 4), **case 0.24.1**(0.20.0 직원별 읽음, 0.21.0 라이더 문의 대화, 0.22.0 R-8 RETURN_RECOVERY_FAILED(상담원 팀 CS, 1시간, 사람이 닫기), 0.23.0 배송 지연 신고 deliveryIssue DELAYED(PC 1), 0.24.0 `/cases/metrics/sla`, 0.24.1 이력 이름 목록(설명만)). 반품 끝남 이력 CLAIM_CLOSED 는 0.24.0 위 코드(계약 변경 없음) — LAN 배포는 PC 1 일지로 확인.
- 아직 501: bff `replenishment-status`·`trip-control`(원천 서비스 없음), `metrics` 의 CASE_SLA 밖 지표.


| 서비스 | 된 것 | 안 된 것 |
|---|---|---|
| backoffice-bff **0.9.1 배포**(8504, LAN PC 4 — 2026-11-17 #98·#100, PC 1 기록 03557dfb. 0.9.0 통합 조회 MFC 현장 칸(PC 3 F-3 #97), 0.9.1 처리 대기 MFC 목록 다음 쪽·truncated. 0.6.0 사건함 도움요청 대화 시각(HELP_REQUEST), 0.7.0 실시간 배송 도착 예정, 0.8.0 처리 대기 MFC 묶음 켬(PC 3 F-4 #95 — 멈춘 중지·거절된 인계, 재시도 운영자·사유 기록, lastRetry).) | 승인함(11-03), 처리 대기 `pending-work`(멈춘 사가 + 이행 확인 필요 + **MFC 중지 실패·인계 거절 0.4.0 — 0.8.0 부터 기본 켬(F-4)**, OPERATOR_ADMIN) + 명령 `saga-requery·saga-resolve·stop-retry·handover-retry`, 통합 조회 `unified-lookup`, 실시간 배송 현황 `live-delivery`(센터 관리자 자기 센터 0.3.1), **사건함 `case-inbox`**(연결 배송·주문 지금 상태 0.5.0, 오배송 회수 배송 0.5.1·재발송 배송 0.5.2) | `replenishment-status`·`trip-control`·`packing-handover`·`metrics`·명령 결과 조회 501. 통합 조회 MFC 칸(F-3 대기), latestEta 는 0.7.0 배포(tracking ETA). |
| case-service **0.19.0 배포**(8506, LAN PC 4 — 2026-11-17 #102, 14:10 확인. 0.12.0 CS 접수 → 0.13.0 라이더 문의(PC 5 #71) → 0.13.1 교대 인계 역할 → 0.14.0 문의 사진 → 0.15.0 만족도 → 0.16.0 배송 신고(PC 5 #82) → 0.17.0 행위 요청 기록 → 0.18.0 접수 건 현황 건수 `GET /cases/metrics` → 0.18.1 행위 요청 길이 제한 → 0.19.0 문의 닫기(본인·답변 역할 직원, 다시 열기 없음)) | 고객 문의, 라이더 컴플레인, 사건함 `/cases`(도움요청·전달 실패·위치 끊김·오배송 확인·사가/결제 이상·배차 실패 — **센터 관리자 자기 센터 배차 실패: DD-MFC 배포로 이제 실제로 보임**), 재개 0.6.0, 이관 0.7.0, 후속 처리(의무) 0.8.0, 오배송 회수 진행 0.8.1, 교대 인수인계 0.9.0, 메모 0.10.0, **오배송 귀책·고객 선택 0.11.0(PC 5 #44 — ADR-0065, 고객 잘못 3·4단계 #49~#53 도 배포)**, 도움요청 접수 `/help-requests` 사용 안 함 0.11.1, 오배송 재발송 연결 0.11.2, **CS 접수 0.12.0(BO-11 — 상담원 전화·채팅·메일 기록 `/cs-intakes`, 본인 확인 방법 필수·대리 표시, 새 건은 고객에게 안 보이는 문의 STAFF_INTAKE, V10)**. LAN 쓰기 시험: 재개·이관·후속 처리·교대·메모 통과(일지 2026-11-16), CS 접수는 읽기 확인만(2026-11-17), **0.13.0~0.17.0(11-17): 라이더 문의(지원센터가 답함), 교대 인계 역할(지원센터 팀 = 지원센터·관리자, 관리자 팀 = 관리자), 문의 사진(버킷 pmart-case, 본인·담당만), 만족도(답변 뒤 1~5점, 관리자만 모아 봄 — 상담원 평가에 안 씀), 고객 배송 신고(deliveryIssue), 행위 요청 기록(대신 실행 안 함)** | 사건 알림(ADR-0064 — 직원 알림 없음), 대리 정책(대리 취소·환불 막기)·등록 번호로 다시 걸기·앱 확인 요청·외부 본인인증 |

PC 1 이 만든 관련 백엔드(PC 1 영역, 화면이 쓴다):

| API | 서비스 | 용도 |
|---|---|---|
| `GET /hr/employees`·`POST /hr/employees/{id}/grant-requests` | hr 0.4.0 | 인사팀 권한 관리 — 직원 검색, 권한 추가·변경 요청(승인 거침). 소속 변경하면 이전 센터 권한이 다음 날 00:00 자동 종료 |
| `GET /employee-auth/employees/{id}`·`POST …/grant-revocations` | employee-auth 0.9.0 | 지금 권한(effectiveRoles·grants) 보기, 즉시 회수(관리자 권한은 OPERATOR_ADMIN 만) |
| `GET /employee-auth/sessions/current` | employee-auth 0.9.0 | 역할·센터 + **`centerDetails`(센터 이름)** |
| `GET /fulfillment-issues`·`POST …/acknowledgements` | order 0.6.0 | 이행 확인 필요 — 관리자는 모든 센터 |
| `GET /pii-access-logs`·`/{id}` | audit 0.3.0 | 개인정보 열람 기록 조회(OPERATOR_ADMIN) |
| `/internal/stuck-sagas`·`/internal/sagas/{id}/manual-actions` | order-orchestration 0.2.0 | 처리 대기 원천(bff 가 부름) |
| `/reviews`·`/products/{id}/reviews`·`/internal/reviews/{id}` | **review 0.2.0(새, PC 2 LAN 8108 — ADR-0063)** | 고객 리뷰. 운영자 숨김·신고는 사용자 결정으로 **없음**(정책 승인 전) — 백오피스 리뷰 화면을 만들지 않는다 |
| `/me/notifications` | **notification 0.2.0(ADR-0064)** | 고객 앱 알림함만. 직원·라이더 알림 없음 — 사건 알림(§4-4)을 만들 때 여기를 쓸지는 그때 |

- **11-16 오후 PC 2 가 더한 것(그 뒤 병합·배포됨)**: case 0.5.0 — 사가·결제 이상(RECONCILIATION·PAYMENT_UNKNOWN, 관리자만, 1시간)·배차 실패(DISPATCH_FAILED, 지원센터, 10분, V5), PR #21. 문의 관리자 보기(0.4.2) PR #20. 클라이언트 PR #11·#13. **백오피스 웹(backoffice·hr-backoffice·mfc-backoffice·web-common)은 같은 PC 의 다른 Claude 세션이 맡는다**(사용자 확정 — 이 문서를 읽는 백엔드 세션은 handoff 로 요청).

## 3. 클라이언트 상태와 보낸 요청

- **웹은 같은 PC 의 다른 Claude 세션("프론트 백오피스")이 맡는다**(사용자 확정). 백엔드 세션은 handoff(`docs/handoff/clients/backoffice/…`)와 세션 메시지로 요청하고, 화면 용어는 **"접수 건"(case)·"후속 처리"(의무)·완료 처리(닫기 — 2026-11-17 전엔 처리 끝내기)·맡기(배정)·다시 처리하기**(사용자 결정, pmart-clients PR #28 뒤 고침)로 쓴다.
- **pmart-clients main(2026-11-16 저녁)**: #27~#43 병합 — 다시 처리하기·넘기기·화면 용어·후속 처리·교대 인계·메모·회수/재발송 연결·오배송 귀책(고객 잘못 실제 주소 포함), MFC 백오피스 메뉴 11개. 목 모드 시험만, 실서버 쓰기(접수·주소 기록)는 사용자 승인 뒤.
- **LAN 빌드는 아직 오전 것**: backoffice `index-BGISi3dQ.js`(f50025c), hr-backoffice `index-Bkg8rNsZ.js` — **PC 1 이 `lan.sh publish-backoffice`(MFC 백오피스도)로 올려야** 오늘 화면이 브라우저에 보인다(사용자 2026-11-16 "아직 안 해도 괜찮아" — 사용자가 PC 1 에 전할 때).
- 남은 요청서(화면 쪽): 도움요청 대화는 PC 5 #60(execution 0.6.0)·DE-2 반영 뒤(권한·필드가 바뀜). **CS 접수 화면**(`2026-11-16-request-cs-intake.md`)은 case 0.12.0 배포를 2026-11-17 웹 세션에 알림 — 웹 순서는 #46 → 라이더와 대화 → CS 접수, 목 시험 뒤 실서버 쓰기는 사용자 승인.

## 4. 할 일 (추천 순서)
0. **2026-11-18**: 위 "지금 상태"의 기다리는 것이 풀리면 — F-10 → bff SHORTAGE_RATE(줄 단위·사유별), DD-4 결정·API → bff ON_TIME_DELIVERY, CL-2 → 웹 알림. 배포 뒤 `backoffice_views_check.py` 로 읽기 확인.
1. **LAN 웹 빌드가 올라오면** 시험 계정(§6)으로 오늘 화면을 눌러 보고 빠진 것만 고친다(실서버 쓰기는 사용자 승인 — 남는 기록을 먼저 알린다).
2. ~~PC 5 DE-2 → bff `case-inbox` RIDER_HELP 건에 `lastRiderMessageAt`~~ — 2026-11-17 bff 0.6.0 배포.
3. ~~PC 3 F-4 → bff 처리 대기 MFC 목록~~ — 2026-11-17 PC 3 #95(mfc-fulfillment 0.9.0) + bff 0.8.0 배포. ~~F-3 → 통합 조회 MFC 칸~~ — PC 3 #97(0.10.0) + bff 0.9.0 배포.
4. ~~PC 5 #71 검토~~ — 2026-11-17 끝(병합·배포 0.13.0). PC 5 가 case 를 고치는 PR(#71·#73·#82)은 PC 2 가 case 부분을 검토한다 — 마이그레이션 번호·계약 버전은 PC 1 이 병합 순서로 정하고, 겹치면 뒤 PR 이 다시 맞춘다.
5. 결정이 남은 것: 대리 정책(지금은 표시·경고만 — 2026-11-17 사용자 "유지"), bff `views/metrics` — CASE_SLA 는 2026-11-18 구현(0.13.0), 결품률은 PC 3 F-10 분모 대기, 정시 배송률은 order·delivery 누가 셀지 결정 전, 결제 불명은 만들지 않음, 상담원 실시간 채팅(안 만듦). 행위 요청(기록만 0.17.0)·지표 1단계(0.18.0)·latestEta(bff 0.7.0)는 끝.
6. 고객센터 문의 답변을 바꿀 때(`InquiryService.reply`) **`InquiryAnswered` 발행을 지킨다**(단, 상담원이 남긴 문의 STAFF_INTAKE 는 보내지 않는다) — 고객 알림함이 이것만 보고 "답변이 등록되었어요"를 쌓는다(notification 이 구독, PC 1). 리뷰·알림 백오피스 화면은 정책이 정해질 때(리뷰 숨김·신고는 사용자 결정 "이번엔 뺌").
- R-2(진행 중 주문 고객센터 취소)는 ADR-0060 으로 거둠 — 만들지 않는다. 도움요청 접수 `/help-requests` 는 R-4 로 execution 이 받음 — 만들지 않는다(0.11.1).
- PR 은 **쌓지 않는다**(PC 1 이 squash 병합 — 앞 PR 뒤에 main 기준으로 다시 올린다). 새 요청은 받는 서비스 파일 + 받는 PC 의 `docs/operations/other-pcs-todo.md` 한 줄, 공용 표(README)는 PC 1 만 — PR 본문 "공용 표 갱신 필요"에. case 요청 번호는 `CS-n`.

## 5. 정해진 것 (사용자 2026-11-15~16 — 다시 묻지 않는다)
- **지원센터 역할 = `RIDER_OPS` 가 겸한다**(따로 두지 않음).
- **사건함**: 다섯 유형(라이더 도움요청·전달 실패·위치 끊김·오배송 확인·사가/결제 이상), 유형별 팀이 공용 목록에서 집어 간다(배송 쪽 = RIDER_OPS, 사가/결제 = OPERATOR_ADMIN), **처리 기한은 표시만 — 도움요청·위치 끊김 10분, 전달 실패·오배송 30분(확정 "그대로 할게")**.
- **BO-12**: OPERATOR_ADMIN 만, "다시 확인"은 결제 확인 대기에만, 업무 거절로 멈춘 건은 "처리 완료(사유 필수)"만, 두 조치 모두 승인 없음, 환불은 기존 절차, 알림은 목록만.
- **BO-01**: 추가·변경 = 승인, 회수 = 즉시(사유 필수), 관리자 권한(OPERATOR_ADMIN·HR_ADMIN) 회수는 운영 관리자만, 종료일 없으면 근무 기간까지, 소속 변경 시 이전 센터 권한 다음 날 00:00 종료. 권한 관리 메뉴 = 인사팀 앱, 승인 = 백오피스 승인함.
- **ADR-0062**: 개인정보 원문은 사유 + audit 기록 뒤에만, 기록 2년, 기록 조회 OPERATOR_ADMIN 만.
- **ADR-0063(리뷰)**: 운영자 숨김·신고 없음(정책 승인 전). **ADR-0064(알림)**: 고객 앱 알림함만, 직원·라이더 알림 없음, 푸시는 나중.
- **고객 앱 라이더 신고 없음(2026-11-16 "추천대로 지금은 안 하는 걸로")**: 고객이 앱에서 라이더 문제를 직접 신고하는 기능은 만들지 않는다 — 고객 불만은 상담원이 문의·사건으로 기록(2026-11-15 결정), 허위 신고 위험. 다시 하려면 사용자 결정부터.
- **처리 대기 MFC(2026-11-16 "A안")**: 중지 실패·인계 거절은 PC 3 목록 API(F-4) 뒤 처리 대기에 — bff 0.4.0 은 준비·설정으로 꺼 둠, "다시 시도"는 사유 필수, 처리 완료 표시는 MFC 종류엔 없음.
- **사건 재개·이관(2026-11-16)**: 재개는 사람이 사유를 적고 손으로만(자동 재개 없음), 닫기와 같은 사람, 기간 제한 없음, 담당 비움·기한 새로 — 닫힌 뒤 같은 대상 새 사건엔 previousCaseId("1번 + 이전 사건 연결"). 이관은 배송 쪽 사건만 지원센터 ↔ 관리자 팀, 센터 이관·병합·사람 없는 팀 없음("A안").
- **사건 의무(2026-11-16 "고친 안")**: 담당 팀 있는 8종, 담당 = 지원센터·관리자 팀·고객센터(그 사건을 볼 수 있는 팀만), 등록 = 사건 팀, 해소 = 담당 팀(상담원은 고객센터 의무 — 첫 쓰기 권한), 시도 기록은 상태 그대로, 면제·실패는 관리자만(사유), 근거는 기록만, 기한 표시만, 자동 생성 없음, 열린 의무가 있으면 닫기 409.
- **교대 인수인계(2026-11-16, BO-27 최대한 그대로)**: 전임이 맡은 열린 건 + 팀 스냅샷, 전임 작성 → 후임이 건별 수락(뺀 건 = 미인수), 건마다 다음 조치 필수·확인 기한 선택, 작성 = 전임·관리자(전임 부재), 수락 전엔 전임 담당·전임 취소 가능·미인수는 관리자 목록(기한 값 없음), 업무(팀) 필수·센터 선택. 근무 확인·실물 인수·늦은 보고 연결·일일 마감은 범위 밖.

- **접수 건 메모(2026-11-16 "고친 안")**: 볼 수 있는 본사 직원(관리자·지원센터·상담원)이 덧붙이기만, 센터 관리자는 아직 안 됨, 처리 끝난 건에도(상태 그대로), 관리자 가림(사유 필수, 글은 지우고 기록은 남김), 500자, 이벤트 없음.
- **도움요청 대화(2026-11-16 "진행해" — PC 5 요청 DE-2)**: 대화는 도움요청 쪽(execution, PC 5 #60). 답장 = 지원센터·관리자만(상담원 보기만, 센터 관리자 없음, 관리자 팀으로 넘긴 건은 관리자만), 새 메시지 = 서버 `lastRiderMessageAt`·읽음은 화면, 관리자 가림(라이더에게도), 처리 중 = 상담원이 집을 때.
- **오배송 귀책(ADR-0065 — PC 5 작성, 사용자 결정)**: 라이더·시스템 = 재발송·환불(라이더는 배달료 미지급), 고객 = 다시 배송(200m 안 0원·넘으면 9,000원 결제 30분)·환불(상품값 − 9,000원). 실제 주소는 order 에 기록(입력만). 백오피스 화면 요청 `handoff/clients/backoffice/2026-11-16-request-misdelivery-fault-choice.md`.
- **고객센터 결정(2026-11-17 — 사용자 추천안 그대로)**: ① 문의 사진 = 리뷰 방식(전용 버킷 pmart-case, 먼저 올리고 붙임, 문의당 5장·장당 10MB, 본인·그 문의를 보는 직원만, 안 붙은 사진 하루 뒤 정리 — case 0.14.0). ② 만족도 = 답변 받은 문의에 고객이 한 번 1~5점 + 한 줄(200자), 상담원 평가·제재에 쓰지 않음, 관리자만 모아 봄, STAFF_INTAKE 제외(아직 안 만듦). ③ 상담원 실시간 채팅 = 지금은 안 만듦(문의·답변 + CS 접수 '채팅' 기록). ④ 대리 = 표시·경고만 유지(서버가 막지 않음). ⑤ 행위 요청 = 기록만 먼저(case 가 다른 서비스를 부르지 않음, 요청·결과 ID·상태만). ⑥ 지표 BO-28 = 우리 데이터 건수부터(접수 건 유형별 열린·기한 넘김, 미인수 교대, 열린 후속 처리, 문의 답변 대기·만족도 평균 — 분자/분모 같이, 목표·SLA 없음). 만드는 순서: 사진 → 만족도 → (행위 요청·지표).
- **문의 닫기(2026-11-17 사용자 "그 표대로 진행해, 다시 열기는 빼고")**: 고객·라이더 본인 또는 답변 역할 직원(상담원·지원센터 — 관리자는 보기만), 직원은 사유 필수(직원에게만 보임), 답변 전에도 닫힘, 닫을 때 만족도(선택, 답변 있을 때만), 자동 닫기·다시 열기 없음(새 문의로), 문의와 그 접수 건 행만 닫힘 — case 0.19.0.
- **결정(2026-11-17 밤 — 사용자 "가가가나")**: ① 직원별 읽음 = case 에 접수 건별 "마지막으로 본 시각"(0.20.0 — `POST /cases/{id}/views`, 목록·상세 `myLastSeenAt`), ② 사건 알림 = 웹 화면 안 배지(새 백엔드 없음 — 기존 `/cases?unassigned`·`overdue` 1분 폴링, 화면을 연 사람만), ③ 다른 서비스 지표(bff `views/metrics`) = 지금은 안 함(정의 승인·다른 PC 집계 API 필요), ④ 역할별 메뉴 확정 = 계속 미룸(PC 1 사정상 LAN 웹 빌드 반영이 지금 어려움).

- **반품 회수 실패(R-8, 2026-11-17 "상담원 팀 (추천)"·"1시간 (추천)"·"사람이 닫기 (추천)")**: 새 팀 CS = 상담원(CS_AGENT)의 첫 사건함 쓰기 권한, 넘기기·교대 인계 없음, 자동 닫기 없음 — 반품이 끝나면 이력 CLAIM_CLOSED 만(2026-11-18).
- **지표(2026-11-18 "일단 그렇게 하고" — 추천안 검토 뒤)**: BO-28(분모를 줄이지 않음, 분모 0 → N/A). CASE_SLA = 닫힌 건 기준(마지막 닫은 시각·그때 기한), 기한 없던 건은 판정 불가로 따로, 종류·팀별, 지금 기한 지난 건은 스냅샷으로. 결품률 = 줄 단위·사유별(수량 합산 안 함). 정시 배송률 = 누가 셀지 결정 뒤(DD-4). 결제 불명 = 지표로 만들지 않음(처리 대기·사건함). 운송·계획 밖 = 원천 없음.

## 6. 규칙·도구
- PR: 포크 `backoffice/<주제>` 브랜치 → PC 1 검토·병합·배포. main push 금지. 저장소마다 PR 따로(backend·clients).
- 일지: `docs/worklog/backoffice/YYYY-MM-DD.md`(첫 날 폴더를 만든다). 클라이언트 handoff: `docs/handoff/clients/backoffice/`(첫머리 `보낸 영역: backoffice`).
- 새 화면이 고객·라이더 개인정보 원문을 보여 주면 **ADR-0062 방식**(기본 가림 + 사유 + audit 동기 기록, 기록 실패면 원문 없음). 개인정보·사유·메모는 로그 금지.
- 직원 토큰: `deploy/lan/checks/staff_auth.py`(`staff_token(이름)`). 시험 계정 — `claude-dev`(OPERATOR_ADMIN), `test-cs-rider@pmart.com`(CS_AGENT·RIDER_OPS), `claude-approver`(HR_ADMIN 등 승인자), `claude-mfc-sup`(MFC_SUPERVISOR, MFC-SEED-01). 비밀번호 파일은 `deploy/lan/.secrets/`(PC 1 에서 복사, Git 아님).
- **LAN 확인 각본(2026-11-18)**: `python -X utf8 -I deploy/lan/checks/backoffice_views_check.py [--cs]` — GET 만, 관리자 15개(첫 실행 15/15). `--cs` 는 최신 비밀번호 파일이 있을 때만(옛 값으로 실패가 쌓이면 계정이 잠김).
- **같은 날 PR 여럿**: 그날 일지 줄은 한 PR 에만(`git merge-tree` 로 main·서로 충돌 없음 확인 — 사용자 2026-11-18). 계약 경로를 바꾸면 게이트웨이 허용 목록(`user-type-allowlist.txt`, `PMART_WRITE_ALLOWLIST=1 … UserTypeAllowlistFileTest`)도 같은 PR 에서 다시 만든다.
- LAN 배포 직후 1~2분은 게이트웨이가 503 일 수 있다(Eureka 목록 갱신 전) — 기다렸다 다시.
- Gradle 제한(workers 2·Xmx2g)은 PC 2 에 이미 적용 — PC 2 는 commerce·RAG 를 돌리는 LAN PC 라 무거운 시험 중 LAN 이 느리면 PC 1 에 알린다.
