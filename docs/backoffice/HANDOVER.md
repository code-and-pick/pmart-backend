# 백오피스(backoffice) 인수인계 (2026-11-15 작성, **2026-11-16 갱신 — PC 2 로 넘김**, 11-16 오전 마지막 갱신)

보낸 영역: platform(PC 1). 받는 영역: backoffice(PC 2). 경계는 `docs/operations/pc-split.md` §1-1(사용자 2026-11-15 "너의 추천대로").

- **넘기는 시점(2026-11-16)**: 사용자 결정으로 PC 1 이 넘기기 전에 백엔드를 먼저 만들었다 — 권한 관리(BO-01), 사건함(case R-4·R-6·R-7), 처리 대기(BO-12), 개인정보 열람 기록 조회, 세션 센터 이름.
- **화면: 지금까지 클라이언트에 보낸 요청(§3 표)은 지금 클라이언트 쪽이 끝까지 한다**(사용자 2026-11-16 "클라이언트쪽에 보낸 요청까지는 다하기로"). PC 2 는 그 뒤의 일부터 맡는다 — 받은 화면은 확인만(§4-1).
- 처음 할 일: §1 먼저 읽을 것 → `./gradlew :services:backoffice-bff:check :services:case-service:check` 가 PC 2 에서 통과하는지 → §4.
- **11-16 오전 PC 1 이 더 한 것(PC 2 에 닿는 것)**: case-service 0.4.1 — 상담원 문의 답변 때 `InquiryAnswered` 발행(고객 알림함, ADR-0064), 도움요청·전달 실패 사건 `refs` 에 `requestId`(클라이언트 질문). 클라이언트가 라이더·배송 직원 화면을 붙였고(f50025c) 그 빌드를 LAN 에 올림. 리뷰(review-service, ADR-0063)·고객 알림함(notification-service, ADR-0064)은 PC 1 영역 — 백오피스 화면은 아직 없다(§4-5).

## 0. 맡는 것·안 맡는 것

| | PC 2(backoffice) | PC 1(platform)에 남음 |
|---|---|---|
| 백엔드 | **backoffice-bff**, **case-service**(문의·컴플레인·사건함) | **audit-service**(개인정보 열람 원장 — ADR-0062), approval-service·hr-service·employee-auth-service(권한 API 포함), order·order-orchestration, 게이트웨이, 라이브러리, **배포 전부** |
| 클라이언트(pmart-clients) | `web/backoffice`, `web/hr-backoffice`(인사팀 화면), **`web/mfc-backoffice`(MFC 센터 관리자 웹 — 사용자 2026-11-16 "PC2에서 할거고")**, `web/core`·`web/ui`(웹 공통 — **로그인·세션·토큰 부분 제외**) | 로그인·세션·토큰 코드 전부(§1-3) |
| PC 3(mfc)에 남음 | `web/mfc-backoffice` 가 부르는 MFC 백엔드 API(MB-1~3 — `docs/architecture/service-requests/mfc-backoffice-staff-apis.md`). 화면에 필요한 MFC API 는 PC 3 에 요청 | |

- **로그인 코드는 PC 1 만**(`pc-split.md` §1-3): `web/core` 의 `employeeAuth.ts`·`session.ts`·`sessionPersistence.ts`·`tokens.ts`·`pmartFetch.ts` 갱신 부분, 웹 앱 `src/features/session`. 바꾼 PR 은 PC 1 이 돌려보낸다. 필요하면 handoff 로 PC 1 에 요청.
- 웹 공통(`web/core`·`web/ui`)은 세 웹 앱(backoffice·hr-backoffice·mfc-backoffice)이 같이 쓴다 — 바꿀 때 세 앱 빌드를 같이 본다. 세 앱 모두 PC 2 라 한 곳에서 맞춘다.
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

## 2. 서비스 상태 (2026-11-17 갱신)

| 서비스 | 된 것 | 안 된 것 |
|---|---|---|
| backoffice-bff **0.5.2 배포**(8504, LAN PC 4 — #48, PC 1 기록 67740cf3) | 승인함(11-03), 처리 대기 `pending-work`(멈춘 사가 + 이행 확인 필요 + **MFC 중지 실패·인계 거절 0.4.0 — 목록은 F-4 뒤 설정 `pmart.bff.mfc-pending-lists` 로 켬**, OPERATOR_ADMIN) + 명령 `saga-requery·saga-resolve·stop-retry·handover-retry`, 통합 조회 `unified-lookup`, 실시간 배송 현황 `live-delivery`(센터 관리자 자기 센터 0.3.1), **사건함 `case-inbox`**(연결 배송·주문 지금 상태 0.5.0, 오배송 회수 배송 0.5.1·재발송 배송 0.5.2) | `replenishment-status`·`trip-control`·`packing-handover`·`metrics`·명령 결과 조회 501. 통합 조회 MFC 칸(F-3 대기), latestEta(tracking ETA 조회 API 501 — TR-1 은 이벤트만, 설정 있을 때). 도움요청 새 메시지 표시(DE-2 의 `lastRiderMessageAt` 뒤) |
| case-service **0.12.0 배포**(8506, LAN PC 4 — 2026-11-16 자정 #68, PC 1 배포 39c0d17f) | 고객 문의, 라이더 컴플레인, 사건함 `/cases`(도움요청·전달 실패·위치 끊김·오배송 확인·사가/결제 이상·배차 실패 — **센터 관리자 자기 센터 배차 실패: DD-MFC 배포로 이제 실제로 보임**), 재개 0.6.0, 이관 0.7.0, 후속 처리(의무) 0.8.0, 오배송 회수 진행 0.8.1, 교대 인수인계 0.9.0, 메모 0.10.0, **오배송 귀책·고객 선택 0.11.0(PC 5 #44 — ADR-0065, 고객 잘못 3·4단계 #49~#53 도 배포)**, 도움요청 접수 `/help-requests` 사용 안 함 0.11.1, 오배송 재발송 연결 0.11.2, **CS 접수 0.12.0(BO-11 — 상담원 전화·채팅·메일 기록 `/cs-intakes`, 본인 확인 방법 필수·대리 표시, 새 건은 고객에게 안 보이는 문의 STAFF_INTAKE, V10)**. LAN 쓰기 시험: 재개·이관·후속 처리·교대·메모 통과(일지 2026-11-16), CS 접수는 읽기 확인만(2026-11-17) | 행위 요청(501), 사건 알림(ADR-0064 — 직원 알림 없음), 대리 정책(대리 취소·환불 막기)·등록 번호로 다시 걸기·앱 확인 요청·외부 본인인증 |

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

- **11-16 오후 PC 2 가 더한 것(PR 대기, LAN 미배포)**: case 0.5.0 — 사가·결제 이상(RECONCILIATION·PAYMENT_UNKNOWN, 관리자만, 1시간)·배차 실패(DISPATCH_FAILED, 지원센터, 10분, V5), PR #21. 문의 관리자 보기(0.4.2) PR #20. 클라이언트 PR #11·#13. **백오피스 웹(backoffice·hr-backoffice·mfc-backoffice·web-common)은 같은 PC 의 다른 Claude 세션이 맡는다**(사용자 확정 — 이 문서를 읽는 백엔드 세션은 handoff 로 요청).

## 3. 클라이언트 상태와 보낸 요청

- **웹은 같은 PC 의 다른 Claude 세션("프론트 백오피스")이 맡는다**(사용자 확정). 백엔드 세션은 handoff(`docs/handoff/clients/backoffice/…`)와 세션 메시지로 요청하고, 화면 용어는 **"접수 건"(case)·"후속 처리"(의무)·처리 끝내기·다시 처리하기**(사용자 결정, pmart-clients PR #28)로 쓴다.
- **pmart-clients main(2026-11-16 저녁)**: #27~#43 병합 — 다시 처리하기·넘기기·화면 용어·후속 처리·교대 인계·메모·회수/재발송 연결·오배송 귀책(고객 잘못 실제 주소 포함), MFC 백오피스 메뉴 11개. 목 모드 시험만, 실서버 쓰기(접수·주소 기록)는 사용자 승인 뒤.
- **LAN 빌드는 아직 오전 것**: backoffice `index-BGISi3dQ.js`(f50025c), hr-backoffice `index-Bkg8rNsZ.js` — **PC 1 이 `lan.sh publish-backoffice`(MFC 백오피스도)로 올려야** 오늘 화면이 브라우저에 보인다(사용자 2026-11-16 "아직 안 해도 괜찮아" — 사용자가 PC 1 에 전할 때).
- 남은 요청서(화면 쪽): 도움요청 대화는 PC 5 #60(execution 0.6.0)·DE-2 반영 뒤(권한·필드가 바뀜). **CS 접수 화면**(`2026-11-16-request-cs-intake.md`)은 case 0.12.0 배포를 2026-11-17 웹 세션에 알림 — 웹 순서는 #46 → 라이더와 대화 → CS 접수, 목 시험 뒤 실서버 쓰기는 사용자 승인.

## 4. 할 일 (추천 순서)
1. **LAN 웹 빌드가 올라오면** 시험 계정(§6)으로 오늘 화면을 눌러 보고 빠진 것만 고친다(실서버 쓰기는 사용자 승인 — 남는 기록을 먼저 알린다).
2. **PC 5 DE-2(도움요청 대화)** 가 오면 bff `case-inbox` 의 RIDER_HELP 건에 `lastRiderMessageAt` 붙이기.
3. **PC 3 F-4**(멈춘 중지 요청·거절된 인계 목록)가 오면 bff 설정 `pmart.bff.mfc-pending-lists` 켜고 경로·필드 맞추기. **F-3**(주문 번호로 현장 상태)이 오면 통합 조회에 MFC 칸. F-4 가 더 급하다(고객 취소·환불이 조용히 멈춤).
4. **PC 5 PR #71(라이더 문의 — `/inquiries` 를 라이더에게)** 이 case V11·0.13.0 으로 다시 올라오면 case-service 를 검토한다(#68 CS 접수와 `InquiriesController`·`InquiryService`·`InquiryStore`·계약이 겹침 — STAFF_INTAKE 숨김·답변 알림 없음·channel/callerRelation 이 남는지). 웹 세션에 `/inquiries` 응답 변화를 알린다.
5. 결정이 필요한 것: 대리 정책(대리 접수 뒤 취소·환불을 막을지 — CS 접수 0.12.0 은 표시만), 행위 요청(처리 대기·후속 처리와 겹침), 지표(BO-28). 실시간 배송 latestEta 는 tracking ETA 조회 API(501) 뒤.
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

## 6. 규칙·도구
- PR: 포크 `backoffice/<주제>` 브랜치 → PC 1 검토·병합·배포. main push 금지. 저장소마다 PR 따로(backend·clients).
- 일지: `docs/worklog/backoffice/YYYY-MM-DD.md`(첫 날 폴더를 만든다). 클라이언트 handoff: `docs/handoff/clients/backoffice/`(첫머리 `보낸 영역: backoffice`).
- 새 화면이 고객·라이더 개인정보 원문을 보여 주면 **ADR-0062 방식**(기본 가림 + 사유 + audit 동기 기록, 기록 실패면 원문 없음). 개인정보·사유·메모는 로그 금지.
- 직원 토큰: `deploy/lan/checks/staff_auth.py`(`staff_token(이름)`). 시험 계정 — `claude-dev`(OPERATOR_ADMIN), `test-cs-rider@pmart.com`(CS_AGENT·RIDER_OPS), `claude-approver`(HR_ADMIN 등 승인자), `claude-mfc-sup`(MFC_SUPERVISOR, MFC-SEED-01). 비밀번호 파일은 `deploy/lan/.secrets/`(PC 1 에서 복사, Git 아님).
- LAN 배포 직후 1~2분은 게이트웨이가 503 일 수 있다(Eureka 목록 갱신 전) — 기다렸다 다시.
- Gradle 제한(workers 2·Xmx2g)은 PC 2 에 이미 적용 — PC 2 는 commerce·RAG 를 돌리는 LAN PC 라 무거운 시험 중 LAN 이 느리면 PC 1 에 알린다.
