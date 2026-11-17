# rider-service 에 대한 요청 (MFC·배차 쪽에서)

rider-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다.

## RS-1. 라이더 단계 사건 발행 (D-4, 2026-11-08 결정 "추천대로")

- **요청한 곳**: delivery-dispatch(배정 풀기의 원천), mfc-fulfillment(간접).
- **필요한 것**: `RiderStateChanged`/`RiderRestricted` — 단계: 신규배차 제한 / 일시 중지 / 계약 종료. dispatch 가 일시 중지·계약 종료 라이더의 배정을 풀고(`DeliveryAssignmentChanged`) 재배정한다(DD-1 4번).
- **설계상 그대로 둘 것**: MFC 는 라이더 단계를 직접 보지 않는다 — `DeliveryAssignmentChanged` 로만 막는다.
- 라이더 앱의 QR → 인계 허가 → 확정 흐름은 rider 앱·delivery-execution(DE-1) 쪽.
- 근거: `docs/business/flows/package-handover-contract.md`, `package-handover-decisions.md` D-4.
- **구현(2026-11-14, delivery PC 5 — rider-service 0.3.0, V3)**: 운영자 `POST /riders/{riderId}/restrictions`(OPERATOR_ADMIN — 승인 화면 BO-20 전 임시, 사용자 2026-11-14)·`GET …`(이력) → `RiderRestricted`(풀기는 restriction NORMAL). 제한이면 신규배차 꺼짐·적격에서 빠짐, 운행 상태는 그대로. delivery-dispatch 가 구독해 **모든 단계에서 픽업 전 배정 풀고 재배차**(`DeliveryAssignmentChanged` reason `RIDER_RESTRICTED`)·남은 제안 LATE, **픽업한 배송은 끝까지 전달**(사용자 2026-11-14 — D-4 변경). `until` 지나면 제한 없음으로 본다(사건 없음). `RiderStateChanged` 는 따로 내지 않는다(`RiderRestricted` 가 신규배차 꺼짐을 뜻함).

## RS-3. 컴플레인 누적 → 제한 검토 대상 등록 (2026-11-14 사용자)

- **결정(사용자 2026-11-14)**: 컴플레인이 일정 기준 이상 누적되면 **시스템이 검토 대상으로 등록**하고, 백오피스 운영자가 확인 뒤 계정을 제한한다(RS-1 API). 자동 제재는 하지 않는다(원문 "후기로만 자동 제재하지 않음").
- **기준(사용자 2026-11-14)**: "비슷한 종류의 컴플레인이 일주일 이내에 3건 이상", 종류는 "신고, 오배송, 파손 전부".
- **구현(2026-11-14, rider 0.3.0, V4)**: case `RiderComplaintRecorded`(새 계약, platform.case.v1 — 발행은 case R-5 요청) 구독 `caseEvents-in-0` → 종류(REPORT·MISDELIVERY·DAMAGE)마다 **7일 안 서로 다른 배송 3건 이상**이면 검토 대상 OPEN(`ComplaintReviewRules`). 해석(설계): ① "비슷한 종류" = 세 종류 각각, ② 한 배송에 여러 번 온 컴플레인은 1건(같은 일을 여러 번 신고한 것과 여러 번 일어난 것을 가름), ③ 열린 검토가 있으면 같은 종류는 거기에 묶고, 닫힌 검토(RESTRICTED·DISMISSED)에 묶인 건은 다시 세지 않는다 — 운영자 확인 뒤에는 새로 3건이 쌓여야 다시 대상. 값은 설정 `pmart.rider.complaint-review.window`(P7D)·`threshold`(3).
- 운영자: `GET /riders/restriction-reviews?state=OPEN`(OPERATOR_ADMIN), `POST /riders/restriction-reviews/{reviewId}/decisions`(RESTRICTED — 먼저 RS-1 로 제한을 걸었어야 409 아님 / DISMISSED). 검토 대상이 열릴 때 알림(사건)은 아직 없다 — 백오피스가 목록을 본다(사용자 2026-11-15 "추천대로" — 우선 목록만, 알림은 나중).

## RS-2. 라이더 가입을 rider-auth-service 토큰으로 받기 (2026-11-10, ADR-0054 G·ADR-0055)

- **요청한 곳**: rider-auth-service(라이더 로그인·토큰은 거기서, 가입 업무는 여기서).
- **필요한 것**:
  - `POST /riders/signups` 를 라이더 토큰(`iss` = rider-auth-service, `typ=RIDER`)으로 받고, **riderId = 토큰 `sub`(`RD-…`)** 로 라이더 프로필을 만든다(본문에 riderId 를 받지 않는다). 같은 주체로 다시 가입하면 기존 가입 결과.
  - 입력·처리는 ADR-0054 결정 G: Google 로그인 → **전화번호·배달 지역·배달 수단·약관** 입력 → **즉시 운행 승인**(업무 원문 §1.1). 전화번호는 본인인증 API 가 없어 **중복 확인만**.
  - `GET /riders/me` 가 미가입이면 404 — 라이더 앱은 로그인 뒤 이것으로 가입 화면 여부를 정한다(rider-auth 응답 `newAccount` 는 참고).
- **지금(임시 방식)**: rider-service 는 501. LAN 시험은 auth-service 서명 시험 라이더 토큰(`RD-claude-*`).
- **구현됨(2026-11-13, 상태 정리 2026-11-16 PC 5)**: `POST /riders/signups`(라이더 토큰 주체 = riderId, 전화번호 중복만 409 DUPLICATE_RIDER, 즉시 OFF_DUTY·NORMAL)·`GET /riders/signups/{requestId}`·`GET /riders/me`(미가입 404). 계약의 면허·주민번호·PHONE_NOT_VERIFIED 는 2026-11-13 뺐다. LAN 에서 rider-auth 서명 시험 토큰으로 가입 201(2026-11-16 rider_e2e·misdelivery_e2e). 남은 것은 '나중'(정지 → rider-auth 세션 끊기)뿐.
- **계약과 어긋난 곳(구현 때 고칠 것)**: 지금 `rider-service.yaml` 가입 설명은 "전화 본인확인 후, 면허·주민번호 중복 차단", `422 PHONE_NOT_VERIFIED(auth 본인확인 미완료)` — 결정 G(본인확인 없음, 전화번호 중복 확인만)와 다르다. **사용자 결정(2026-11-10): 가입 때 면허·주민번호를 받지 않고, 중복 확인은 전화번호만 한다.** 구현 때 계약에서 `PHONE_NOT_VERIFIED` 와 면허·주민번호 중복 차단 문구를 빼고, 전화번호 중복은 `409` 로 둔다.
- **나중**: 정지(`RiderRestricted`)를 rider-auth-service 가 받아 세션을 끊는 일(ADR-0054 ⑤ 차단 목록).
- 근거: `docs/adr/0054-auth-split-keycloak-staff-rider-auth-service.md` 결정 G, `docs/adr/0055-google-login-id-token-only-customer-rider.md`, `contracts/openapi/rider-auth-service.yaml`.

## 확인 요청 (2026-11-16, 보낸 영역 backoffice) — LAN 라이더 찾기 0명
- **본 것**: LAN 백오피스 "라이더 찾기"(`GET /api/v1/riders?size=20&page=0&sort=JOINED_DESC`, BOD-3)가 조건 없이 200 이지만 `items` 0개 — OPERATOR_ADMIN(`claude-dev`)·RIDER_OPS(`test-cs-rider`) 둘 다.
- **궁금한 것**: LAN rider-service 에 가입한 라이더 프로필이 정말 없는가(시험 라이더 토큰 `RD-claude-*` 만 쓰고 가입은 안 했는가), 아니면 목록 조회가 비는 다른 이유가 있는가. 없다면 백오피스 확인용 시험 라이더 한두 명이 있으면 좋겠다(라이더 상세·원문 열람·제한 화면을 실서버로 볼 수 없다).
- 급하지 않음 — 화면은 목 모드 시험으로는 확인됨.
- **회신(2026-11-16, PC 5)**: 그때는 정말 가입한 라이더가 없었다(라이더 앱이 아직 없어 가입 경로가 없었음). 목록 조회 문제는 아니다. 2026-11-16 LAN 라이더 E2E 가 시험 라이더 둘을 가입시켰다 — `RD-claude-97fb21e37325`(배송 1건 완료·수행료 3,200원 확인 대기), `RD-claude-08b91d6afb92`(가입만). 둘 다 이름 "시험라이더", 오토바이, MFC-SEED-01, 지금 OFF_DUTY. `GET /riders` 가 OPERATOR_ADMIN(`claude-dev`)으로 둘 다 돌려주는 것을 확인했다. 시험 라이더는 `deploy/lan/checks/rider_e2e.py <deliveryId>` 로 더 만들 수 있다(LAN 라이더 서명키 필요 — PC 1·PC 5 `.secrets`).

## RS-4. 라이더 탈퇴 (2026-11-17, 라이더 앱 요청 §17 — PC 5 구현, 브랜치 `delivery/rider-withdrawal`)
- 앱: `POST /riders/me/withdrawals` 가 LAN 에서 501(계약만 있었음). 사용자 결정(2026-11-17, 원문 §8.3 미정 항목): **확정 전 수행료만 막기**(확정분은 탈퇴 뒤 지급), **제한 검토 중·열린 도움요청만 막기**(걸린 제한은 막지 않음), **즉시 가림·재가입 허용**. 진행 중 배송은 계약대로 막음.
- 구현: rider 0.6.0(V7)·execution 0.12.0(내부 막는 것 조회)·rider-auth(RiderWithdrawn → 세션 끊기·Google 연결 지움). 사건 `RiderWithdrawn`(새).
- 참고: 산정 불가(조정 대기) 수행료를 바꾸는 지원센터 API 가 아직 없어, 산정 불가가 하나라도 있는 라이더는 조정 기능이 생길 때까지 탈퇴가 막힌다.

## RS-5. 가입 전 확인·거주지 주소·정산 계좌 (2026-11-17, 라이더 앱 요청 §12·§14·§15·§16-1 — PC 5 구현, 브랜치 `delivery/rider-signup-checks`)
- 사용자 결정(2026-11-17): §12 "서버가 주소 변환으로 확인", §15 계좌 "중복 불가"(앱 요청 그대로), §16-1 은행 = "금융결제원 코드 3자리"(공개 표 — 비용 없음, 실명·1원 확인은 §9 나중에).
- rider 0.7.0: `POST /riders/phone-checks`·`/riders/account-checks`·`GET /riders/bank-codes`, 가입·프로필 변경에 주소 422 INVALID_ADDRESS·계좌 409 DUPLICATE_ACCOUNT·은행 코드 400.
- §13(전달 불가 ↔ 대화)는 execution 다음 PR, §18 배달료 기준은 사용자 "앱 표 그대로"(API 없음).
