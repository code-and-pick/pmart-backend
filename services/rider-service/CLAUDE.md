# rider-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `rider-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/rider-service.yaml` → `openApiGenerate` 가 `com.pmart.rider.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.rider.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:rider-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:rider-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8403.

## 상태
2026-11-13 DD-1 첫 묶음(계약 0.2.0, V2): 가입·본인 프로필·운행 상태·배차 설정·내부 적격 라이더/라이더 조회 구현. 탈퇴는 2026-11-17 구현(아래).
- 주체 = 라이더 토큰 sub(RD-…, rider-auth-service). 가입은 전화번호 중복(면허·주민번호 안 받음 — 사용자 결정 2026-11-10 RS-2), 번호 확인은 2026-11-19 OCTOMO(아래 — 설정 required 로 켬), 즉시 운행 승인(원문 §1.2). 첫 가입 배차 모드 AI_RECOMMENDED(원문 §2.2).
- 운행 상태 `domain/OperatingRules`: OFF_DUTY 면 신규배차 꺼짐, SUSPENDED·TERMINATED 운행 시작 403, DISPATCH_RESTRICTED 는 신규배차 ON 403, 기준 MFC 는 배달지역 안(비우면 첫 MFC). 변경마다 `RiderStateChanged`(delivery.rider.v1, mfcId 포함).
- 버전 하나(행 version)가 프로필·운행·설정의 X-Expected-Version 기준.
- 적격 라이더 = 운행 중·신규배차 ON·NORMAL·배달지역에 mfcId. 반경은 거르지 않는다(dispatch 가 tracking 위치로 직선 반경 — 사용자 2026-11-13). 용량·주류·연속 거절은 비움.
- **계정 제한(RS-1, 2026-11-14 — 계약 0.3.0, V3 `rider_restriction_history`)**: `POST /riders/{riderId}/restrictions`(restriction·reason 필수, until·evidenceRefs·approvalRef 선택, NORMAL = 풀기)·`GET …`(지금 제한 + 지금 행 `version`(0.4.0 BOD-6 — X-Expected-Version 용) + 이력 최근 100). **OPERATOR_ADMIN·RIDER_OPS**(`RiderService.requireRestrictionAdmin` — RIDER_OPS 는 0.4.0, 2026-11-15 BOD-1). 승인 화면(BO-20) 전 임시 경로(사용자 2026-11-14 "승인 화면 구현 전에는 운영자 API"). 행 버전 조건 갱신 + 이력 + `RiderRestricted`(delivery.rider.v1, payload previous 포함, actor OPERATOR) 한 트랜잭션. 제한이면 `new_dispatch_enabled=false`, **운행 상태는 그대로**(픽업한 배송은 끝까지 전달 — 위치를 계속 보내게). `until` 이 지나면 `Rider.asOf` 가 NORMAL 로 본다(응답·운행 판정·적격 SQL — 기한 끝에 사건은 없음). `RiderStateChanged` 는 따로 내지 않는다.
- dispatch 가 `RiderRestricted` 를 받아 모든 단계에서 픽업 전 배정을 풀고 재배차(사용자 2026-11-14 — D-4 변경).
- **컴플레인 누적 → 제한 검토 대상(RS-3, 2026-11-14 — V4 `rider_complaint`·`rider_restriction_review`)**: 구독 `caseEvents-in-0` ← platform.case.v1 `RiderComplaintRecorded`(case 발행은 구현 전 — case R-5). `ComplaintService` → `domain/ComplaintReviewRules`: 종류(REPORT 신고·MISDELIVERY 오배송·DAMAGE 파손)마다 7일 안 **서로 다른 배송** 3건 이상이면 검토 OPEN(사용자 "비슷한 종류 일주일 이내 3건 이상", "신고·오배송·파손 전부" — 배송 단위로 세는 것은 설계 해석). 라이더별 advisory lock, 같은 complaintId 한 번. 열린 검토가 있으면 같은 종류는 거기에 묶고, 닫힌 검토의 건은 다시 세지 않는다. **자동 제재 없음** — `GET /riders/restriction-reviews`·`POST …/{reviewId}/decisions`(OPERATOR_ADMIN·RIDER_OPS, RESTRICTED 는 먼저 RS-1 로 제한해야). 값 `pmart.rider.complaint-review.window`(P7D)·`threshold`(3).
- **직원 라이더 찾기·상세·원문 열람(0.4.0, 2026-11-15 — 백오피스 라이더 운영·고객센터, 요청서 `delivery-backoffice-staff-apis.md` BOD-3·6)**: `StaffRiderService`, 권한 OPERATOR_ADMIN·RIDER_OPS·CS_AGENT. `GET /riders` 는 **라이더 ID·전화 완전 일치만**(이름 검색 없음 — 사용자 "이름은 동명이인이 있을 수도"), 제한은 지금 유효한 값으로 거름, V5 색인. 목록·상세 모두 이름(첫·끝 글자)·전화 가림, 계좌·거주지 없음. 원문은 `POST /riders/{riderId}/pii-reveals`(사유 2~200자) → `infrastructure/AuditClient` 가 audit-service `/internal/pii-access-logs`(subjectType RIDER) 에 동기 기록한 뒤에만(ADR-0062 — 기록 실패면 503, 원문 저장 안 함). 주소 `pmart.rider.audit-base-url`(기본 `http://audit-service:8503`). 사유·원문·검색 전화번호는 로그 금지.
- 아직 없음: 진행 중 배송 수(execution 사본 — 그래서 운행 중이면 수단 변경을 막는다), 검토 대상이 열릴 때 알림 사건(사용자 2026-11-15 — 나중, 우선 목록만), BO-20 승인 연동(`ApprovalDecided`), 마지막 통신 시각은 운행 상태 바꿀 때만.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.
- **배달 수단·운전면허(2026-11-16 — 라이더 앱 요청 §6·§7, V6)**: 수단 E_BIKE(전기자전거)·KICKBOARD(전동킥보드) 추가(수행료는 자전거와 같음 — dispatch `FeeTable` 이 도보만 따로). 가입 `licenseNumber`(12자리, 하이픈 선택) — MOTORCYCLE·CAR·KICKBOARD 필수(422 LICENSE_REQUIRED), 같은 번호 409 DUPLICATE_LICENSE, `POST /riders/license-checks`(가입 전 중복 확인). **원문 저장 안 함**: `license_hash` = HMAC-SHA256(`pmart.rider.license-hash-key` — pmart-config {cipher}, 없으면 면허를 받는 요청만 422 CONFIG_REQUIRED), `license_masked`(11-**-******-12) → 프로필 `licenseNumberMasked`. 가입 뒤 수단 변경(PATCH `/riders/me`)도 오토바이·자동차·킥보드로 바꾸면서 등록된 면허가 없으면 `licenseNumber` 필수(422 LICENSE_REQUIRED, 다른 라이더와 같으면 409 — 사용자 2026-11-16 "받는다").
- **탈퇴(2026-11-17 — 계약 0.6.0, V7, 라이더 앱 요청 §17·원문 §8.3)**: 사용자 결정 — 막는 것 = 진행 중 배송·**확정 전** 수행료(확정분은 탈퇴 뒤 지급일에 그대로)·산정 불가 조정 대기·열린 도움요청(delivery-execution `GET /internal/riders/{id}/withdrawal-blockers` — `infrastructure/ExecutionClient`, `pmart.rider.execution-base-url` 기본 `http://delivery-execution-service:8405`, 못 부르면 503)·**제한 검토 중**(RS-3 OPEN — 이 서비스). 이미 걸린 계정 제한은 막지 않는다. `POST /riders/me/withdrawals`(`WithdrawalService`): 막으면 `rider_withdrawal` REJECTED·blockers + 409 INVALID_STATE(details field `blockers`), 같은 키로 다시 내면 다시 검사. 없으면 바로 COMPLETED(202 APPLIED, nextAction LOGOUT): **이름·전화·계좌·거주지·면허를 지움**(사용자 "즉시 가림·재가입 허용" — phone NULL 허용), 운행 끔, `withdrawn_at` → `RiderWithdrawn`(delivery.rider.v1, 개인정보 없음). 탈퇴한 행은 이력 주체로 남고 조회·직원 찾기·적격에서 빠진다(`withdrawn_at IS NULL`). 같은 주체로 다시 가입은 409 RIDER_WITHDRAWN(rider-auth 가 Google 연결을 지워 다시 로그인하면 새 주체 → 같은 전화·면허로 가입 가능). `GET /riders/me/withdrawals/{requestId}`(blockers enum `WithdrawalBlocker`).
- **가입 전 확인·주소·계좌(2026-11-17 — 계약 0.7.0, V8 색인, 라이더 앱 요청 §12·§14·§15·§16-1)**: 사용자 결정 — 거주지는 **서버가 주소 변환으로 확인**(`infrastructure/AddressClient` → serviceability `POST /internal/address-geocodes` 정확 일치, 결과 없으면 422 INVALID_ADDRESS, 장애 503 — 외부 호출이라 멱등 트랜잭션 밖에서 먼저. 주소는 본문으로만·로그 없음, `pmart.rider.serviceability-base-url` 기본 `http://delivery-serviceability-service:8401`), **정산 계좌 중복 불가**(같은 은행 코드·계좌 다른 라이더 → 409 DUPLICATE_ACCOUNT, 가입·프로필 변경 — 유일 제약 대신 색인 + 검사), **은행 = 금융결제원 코드 3자리**(`domain/BankCodes`, 목록 밖 400, `GET /riders/bank-codes`). 가입 전 `POST /riders/phone-checks`·`/riders/account-checks`(→ `{available}`, 본인 계좌는 true, 값 저장·로그 없음 — 면허 확인처럼 변경 요청 공통 헤더 Idempotency-Key·X-Source-System 필요).

- **운행 중 라이더(2026-11-17, 0.8.0 — ADR-0068 라이더 관제)**: `GET /internal/riders/on-duty?mfcId=` — 운행 중·탈퇴 안 함·배달지역에 mfcId, 신규배차·제한 무관, 200명, 가린 이름만(`RiderViews.onDuty`). tracking 이 부른다.
- **대시보드·가입 승인(2026-11-17, 0.9.0 — 사용자 "승인 화면은 만들고 일단은 바로 승인, 정책은 나중에", "대시보드에 필요한 것만")**: `GET /riders/stats?mfcId=`(`StaffRiderService.stats` — 전체·운행 중·신규배차 켬·제한 단계별(기한 지난 제한은 NORMAL)·오늘 가입, RIDER_OPS·OPERATOR_ADMIN·CS_AGENT). 직원 목록·상세 `signupStatus`(지금 늘 APPROVED)·상세 `signupDecidedBy` AUTO — 승인 대기 정책이 정해지면 상태를 저장한다.
- **정산계좌·동의(2026-11-18, 0.10.0 — 라이더 백오피스 요청 #2·#3)**: 직원 상세에 `bankAccountRegistered`(등록 여부만)·`bankName`(`BankCodes`)·`consents`(항목별 마지막 동의 — `rider_consent` 는 (라이더, 항목) 한 줄이라 과거 이력은 없음, 사용자 "최신만"으로 충분).
- **접속·대기(2026-11-18, 0.11.0·V9 — 라이더 백오피스 관제, 사용자 "대기 = 운행 중 + 신규배차 켬 + 배송 0건", "접속 시간 따로")**: `rider.on_duty_since`·`available_since` + `rider_duty_segment`(운행 여부·신규배차가 바뀔 때마다 구간을 닫고 운행 중이면 새로, `RiderStore.recordDuty` — `setOperating`·탈퇴). `/internal/riders/on-duty` 에 `onDutySince`·`availableSince`, 새 `GET /internal/riders/{id}/duty-segments?from&to`(execution 이 부름). V9 전부터 운행 중이던 라이더는 `state_changed_at` 부터로 근사. `OperatingState.changedAt` 은 신규배차 토글에도 바뀐다.
- **화면 라이더 번호(2026-11-18, 0.12.0·V10 — ADR-0070)**: `rider.rider_number` 6자리(INSERT 트리거 무작위·겹치지 않음, 기존 라이더는 마이그레이션이 채움) — 직원 목록·상세·본인 프로필·운행 중 목록 `riderNumber`, 직원 찾기 `riderNumber`·`riderIds`(쉼표 50개).
- **공지 받는 라이더(2026-11-18, 0.13.0)**: `GET /internal/riders/recipients?mfcId&after&size`(탈퇴 제외, 배달지역 센터, riderId 순 1000개씩) — notification 라이더 공지.

- **직원 조치 이력(2026-11-18, 0.14.0 — 사용자 "이미 기록된 조치를 모아 보기", 관리자만)**: `GET /rider-staff-actions`(`StaffActions` — 계정 제한 걸기·풀기, 제한 검토 결정) — OPERATOR_ADMIN, 기간 기본 7일·최대 31일.
- **소명·이의 신청(2026-11-18, 0.15.0·V11 — 사용자 "앱에서 신청 → 백오피스 결정", 원문 §9.6)**: `application/AppealService`·`api/AppealsController` — 라이더 `POST/GET /riders/me/appeals`(대상 RESTRICTION=지금 제한, EARNING=deliveryId, 대상마다 한 번), 직원 `GET /rider-appeals`·`POST /rider-appeals/{id}/decisions`(RIDER_OPS·OPERATOR_ADMIN, UPHELD·ACCEPTED, 설명 필수). 제한 인정이면 바로 정상으로(changeRestriction), 수행료 인정은 adjustmentRequired. 사건 RiderAppealDecided(설명 없음), 조치 이력 APPEAL_DECIDED.
- **소명 내부 조회(2026-11-19, 0.16.0)**: `GET /internal/rider-appeals/{appealId}` — execution 보상금 확인.
- **휴대폰 번호 확인·재가입 막기(2026-11-19, 0.17.0·V12 — 사용자 "옥토모", 라이더 앱 요청 R2~R4)**: `PhoneVerificationService`·`api/PhoneVerificationsController` — `POST /riders/me/phone-verifications {phone}`·`…/{id}/confirmations {phone}`·`GET …/{id}`(고객과 같은 MO 방식, `infrastructure/OctomoClient` 고객 것 그대로, 번호 원문 없이 해시). 시작·가입 때 그 번호의 다른 라이더 → 409 IDENTITY_ALREADY_REGISTERED(details provider·emailMasked — rider-auth 내부 `RiderAuthClient`, `pmart.rider.rider-auth-base-url` 기본 `http://rider-auth-service:8510` — joinedAt), 탈퇴 번호 `rejoin-block`(기본 30일 — 사용자 결정) 안 → 409 RIDER_REJOIN_BLOCKED(until, `rider_rejoin_block` 해시, 탈퇴 때 기록). 가입 `phoneVerificationId` — `pmart.rider.phone-verification.required` true 면 필수(422 PHONE_NOT_VERIFIED, 확인 뒤 1시간 안), 주면 `phone_verified` true. OCTOMO 설정이 없으면 시작·확인 422.
- **화면 표기 묶음(2026-11-20, 0.18.0 — RS-MFC, ADR-0070)**: `GET /riders/display-names?riderIds=`(`StaffRiderService.displayNames`) — 직원 3역할 + MFC_SUPERVISOR·MFC_WORKER, 라이더 번호·가린 이름만. 센터 직원에게 다른 라이더 조회는 열지 않는다.
- **탈퇴 뒤 부정 이용 방지 보관(2026-11-20, V13 — 사용자 "이름이나 전화번호를 5분 보관", 라이더 개인정보 동의서 제3조)**: 탈퇴 때 rider 행은 그대로 바로 비우고, 이름·전화만 `rider_withdrawal_hold` 에 `rejoin-block` 기간(LAN 5분·기본 30일)까지 따로 둔다(`PhoneVerificationService.holdWithdrawn` — 재가입 막기 해시와 같은 기한). `WithdrawalHoldPurger`(@Scheduled `pmart.rider.withdrawal.purge-tick` 기본 PT1M)가 기한 지난 보관과 `rider_rejoin_block` 을 지운다(로그는 건수만). 기간을 바꾸면 앱 동의서 문구도 같이.
- **직원 탈퇴 목록(2026-11-20, 0.19.0 — 사용자 "라이더 쪽에만 넣어", 라이더 백오피스만)**: `GET /riders/withdrawals?state&riderNumber&page&size`(`StaffRiderService.withdrawals` → `RiderStore.staffWithdrawals`) — `rider_withdrawal` 최근 신청 순, 거절은 blockers, 완료는 completedAt, 라이더 번호만(개인정보 없음). OPERATOR_ADMIN·RIDER_OPS·CS_AGENT. 통합 백오피스(bff)에는 붙이지 않는다.
- **탈퇴 라이더 기록 보기(2026-11-21, 0.20.0 — 사용자 "탈퇴한 라이더의 정보는 따로 모아볼 수 있게 필터")**: 직원 `GET /riders?withdrawn=true`(탈퇴한 라이더만, 탈퇴 최근 순 — `RiderStore.Search.Scope.WITHDRAWN`)·`GET /riders/{id}?includeWithdrawn=true`(`findIncludingWithdrawn`) — 목록·상세에 `withdrawnAt`, 이름 null·전화 빈 문자열(탈퇴 때 지움). 기본(파라미터 없음)은 예전 그대로 탈퇴 제외·상세 404. 계정 제한·원문 열람은 계속 탈퇴 라이더를 찾지 못한다(`find`·`lock`). `display-names` 는 탈퇴 라이더도 번호 + `withdrawn: true`(Scope.ALL). 수행료(execution)·상담(case)은 riderId 로 그대로 남아 있어 화면이 riderId 로 묶어 본다.
- **짧은 라이더 번호(2026-11-22, 0.21.0·V14 — ADR-0085, 사용자 "R4785 좀 이렇게 간단하게")**: `rider_number` = `R` + 4자리(무작위, 50번 못 찾으면 5자리 — `rider_next_number()`), 기존 6자리는 V14 가 모두 새 번호로 바꿈. 찾기 `riderNumber` 는 `R4785`·`r4785`·`4785` 같음(`OperatorController.riderNumberQuery`).
