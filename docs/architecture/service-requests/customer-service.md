# customer-service 에 대한 요청 (auth 쪽에서)

## CU-1. 약관 동의 기능 (2026-11-09 기록)

- **요청한 곳**: auth-service — 동의 기능이 생기면 `require-consent=true` 로 켠다(`deploy/lan/.config-repo/auth-service.yml`).
- **지금**: false(사용자 결정 2026-10-30 — 동의 건너뜀). customer `ConsentsController` 501.
- **사용자 결정 필요**: 켜는 시점·약관 항목.
- **결정(2026-11-17 사용자 "추천대로")**: 지금 만들어 켠다. 필수 3(이용약관·개인정보 수집·이용·만 14세 이상) + 선택 1(마케팅 수신). 위치기반서비스는 현재 위치 기능 때.
- **구현(2026-11-17, LAN 배포 전)**: customer `ConsentService`(계약 0.3.0, V6 `customer_consent`·`_history`, `ConsentType` = TERMS_OF_SERVICE·PRIVACY_POLICY·AGE_OVER_14·MARKETING, 현행 버전 설정 `pmart.customer.consents.versions.*`) — `/me/consents`·`/me/consent-changes`(마케팅 켜고 끄기, 필수 끄기 422, 옛 버전 422)·내부 `/internal/accounts/{id}/consents`·`consent-changes`, `CustomerConsentChanged` 발행, 계정 상태 `purchasable` 에 필수 동의 반영(스위치 `consents.purchase-gate`). auth(계약 0.2.0, V5) — `require-consent=true` 면 신규 가입·약관 버전이 바뀐 기존 회원은 `AUTH_CONSENT`(`requiredConsents`·`optionalConsents` = `TYPE:version`), 완료는 `completions` 본문 `providerToken`+`consents`(BR-20 해소 — 토큰 없는 단계라 Google ID 토큰으로 본인 확인, 동의 뒤에만 프로필 사건). 고객 앱 안내 `docs/handoff/clients/customer-app/2026-11-17-notice-consents.md`. pmart-config(`customer-service.yml` 버전·`purchase-gate: false`, `auth-service-{lan,local}.yml` 주소)는 아직 적용 전. **서버는 준비, LAN 스위치는 고객 앱 반영 때까지 꺼 둔다**(`require-consent: false`·`purchase-gate: false`) — 켤 때는 둘을 함께 true.

## CU-2. 배송지 저장 때 좌표가 없으면 서버가 주소로 채우기 (2026-11-18, 보낸 영역 delivery — PC 5)
- **증상(LAN 실주문 74089765)**: 고객이 지도 핀 없이 주소만 저장 → 주문 사본(O-5 drop-info)에 lat·lng 가 null → 라이더 앱은 제안의 대략 좌표(1km 격자)로 핀을 찍어 실제 위치와 어긋났다. 사용자 "좌표로 핀을 찍게 해야지".
- **delivery 쪽 임시 대체(PC 5, execution 0.25.1)**: drop-info·직원 drop-location 이 핀이 없으면 배차가 도로명주소로 지오코딩한 좌표를 준다(`coordinateSource` ADDRESS_GEOCODE). 배차 좌표가 준비되기 전에는 여전히 null.
- **요청(근본)**: 고객 배송지 저장·수정 때 `geo` 가 없으면 서버가 delivery-serviceability `POST /internal/address-geocodes`(정확 일치) 결과로 채워 저장 → 주문 확정 사본(O-5)에도 좌표가 들어가게. 지오코딩 실패면 지금처럼 비워 둠(저장은 막지 않음 — 정책은 PC 1 이 정함).
- **받는 쪽**: customer-service(platform PC 1). 사본은 order 가 확정 때 복사하므로 order 는 바꿀 것 없음.

## CU-3. 고객번호 6자리 — 화면 표기 "가린 이름 · 고객번호" (2026-11-19, 보낸 영역 backoffice — PC 2, 받는 쪽 platform PC 1)

- **왜**: ADR-0070 덧붙임(2026-11-19, PR #219 — 사용자 결정 "가린 이름 · 고객번호 6자리"): 백오피스 목록·표에 `CU-…` 시스템 ID 를 내지 않는다. 고객은 **가린 이름 · 숫자 6자리**(`김*수 · 482913`, 라이더 번호와 같은 방식).
- **필요한 것**:
  1. `customerNumber` — 숫자 6자리, 무작위, 겹치지 않음(유일 제약), 바뀌지 않음. 가입 때 매기고 **기존 고객도 채운다**(마이그레이션).
  2. 직원 조회 응답(고객 상세·목록·주문 직원 조회에 고객이 붙는 곳)에 `customerNumber` 와 가린 이름(`nameMasked` — 첫·끝 글자, 이미 쓰는 가림 규칙).
  3. 여러 고객 ID 로 한 번에 — 예 `GET /customers?customerIds=a,b,c`(50개, 직원 토큰) → `{customerId, customerNumber, nameMasked}`. backoffice-bff 가 통합 조회·CS 접수·클레임 화면에 붙인다(라이더 `GET /riders?riderIds=` 와 같은 모양).
  4. 찾기: `customerNumber` 완전 일치(직원) — 화면 찾기 칸이 번호로 찾는다(ADR-0070 "찾기는 화면 표기로").
- **개인정보**: 번호·가린 이름만. 탈퇴하면 이름은 지우는 쪽 규칙 그대로(번호는 이력 표시용으로 남겨도 됨 — PC 1 판단).
- **받는 쪽(backoffice)**: bff 가 일괄 조회로 붙인다(case·claim 에 사본 두지 않음).
- **상태(2026-11-20, PC 1 — 사용자 "PC 2가 정한 순서로")**: 구현 customer **0.11.0**·V14. `customerNumber`(트리거로 가입 때, 기존 고객 채움, 탈퇴해도 남김 — 이력 표시용), `/me` 에도. 일괄 = `GET /customers?customerIds=`(50개, 요청 순서, 없는 ID 빠짐) → `StaffCustomerSummary`(customerId·customerNumber·nameMasked·phoneMasked·status). 가린 이름은 이름이 없으면 별명을 가림. 권한은 CU-4 와 같다(CS_AGENT·OPERATOR_ADMIN — MFC·라이더 운영 화면에도 고객 표기가 필요하면 역할을 넓히는 요청을 다시). LAN 배포(2026-11-20 905bffe7).

## CU-4. 직원용 고객 조회 — 백오피스 "고객" 메뉴 (2026-11-19, 보낸 영역 backoffice — PC 2, 받는 쪽 platform PC 1)

- **사용자 결정(2026-11-19 "응 그대로 진행해" — PC 2 추천안)**: 고객센터 묶음에 **조회만 하는** "고객" 메뉴. 고치기(연락처·주소 대리 변경)·운영 제한·쿠폰은 업무 원문에 규칙이 생긴 뒤(3단계). 지금 customer-service 에는 직원용 조회가 하나도 없다(전부 `/me/…`·`/internal/…`).
- **업무 원문 테두리**(BackOffice 개인정보 — "목적별로 제한", "담당 주문 상담 — 주문 필요한 고객/연락 정보, 실제 담당/문의 자격·열람 사유", "주소록 수정은 Commerce 의 현재 저장 정보만"): 전체 목록 훑기·이름 검색 없음, 원문은 사유 + 열람 기록 뒤에만.
- **필요한 것(1단계)** — 직원 토큰, CS_AGENT·OPERATOR_ADMIN:
  1. **찾기** `GET /customers?customerNumber=|phone=`(완전 일치만 — 전화는 숫자 9~11자리, 하이픈 무시. 라이더 찾기 `GET /riders` 와 같은 규칙). 이름 검색·목록 훑기는 두지 않는다. 찾는 값은 로그 금지.
  2. **상세** `GET /customers/{customerId}` — 가린 값만: `customerNumber`(CU-3)·가린 이름·가린 전화, `socialProvider`(GOOGLE·KAKAO·NAVER·APPLE — 화면 배지), `phoneVerified`(OCTOMO 0.9.0), 가입 시각, 계정 상태(정상·탈퇴 진행·탈퇴 — 탈퇴는 이름 등이 지워진 값 그대로).
  3. **원문 열람** `POST /customers/{customerId}/pii-reveals`(사유 2~200자, 항목 고르기 — 이름·전화) → audit-service 열람 기록이 남은 뒤에만 원문(rider·order 와 같은 ADR-0062 방식, 기록 실패면 503).
  4. **여러 명 한 번에** — CU-3 의 `customerIds` 일괄(번호·가린 이름)을 상세 머리에도 쓴다.
- **필요한 것(2단계)**: 동의 현황 보기(항목별 마지막 동의 — `/me/consents` 와 같은 모양), 저장된 배송지 목록(가린 값, 기본 배송지 표시).
- **받는 쪽**: backoffice-bff `views/customer`(PC 2 — `backoffice-bff.md` BF-2)가 고객 + 최근 주문·클레임·문의·접수 건을 한 번에 묶는다. 화면은 프론트 백오피스.
- 개인정보: 응답은 가린 값만, 원문·찾는 전화번호는 로그 금지.
- **상태(2026-11-20, PC 1)**: 1단계 구현 customer **0.11.0**. 찾기 `GET /customers?customerNumber=|phone=|customerIds=`(셋 중 하나만 — 둘 이상·없음 400, 전화는 01로 시작 10~11자리·하이픈 무시, 프로필 휴대폰 번호 기준, 같은 번호 여러 계정이면 가입 순 20개까지), 상세 `GET /customers/{id}`(customerNumber·nameMasked·phoneMasked·emailMasked·status·socialProvider·phoneVerified·joinedAt — 가입 방식 값은 문자열, 없을 수 있음), 원문 `POST /customers/{id}/pii-reveals`(사유 2~200자, fields NAME·PHONE, audit subjectType CUSTOMER·caseRef `CUSTOMER:<id>`, 기록 실패 503 원문 없음). 게이트웨이 라우트 `/api/v1/customers/**`(pmart-config). 2단계(동의 현황·배송지 목록)는 아직. LAN 배포(2026-11-20 905bffe7).

## CU-5. 직원 고객 목록·검색 — 이름 검색, 가입일·상태 거르기 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **왜**: 통합 백오피스 "고객 관리" 목록(회원번호·이름·연락처·가입일·상태·최근 주문일). 지금은 고객번호·전화 완전 일치 찾기만 있어 목록을 못 만든다.
- **필요한 것**: `GET /customers` 목록(쪽 넘김, 가린 값만) — 거르기 이름(부분 일치?)·전화·회원번호·가입일 기간·상태, 가입 최근 순.
- **정할 것(PC 1)**: 이름 검색을 어떻게 할지(개인정보 원문 검색 — ADR-0062 와 맞는지, 완전 일치만 허용할지). 최근 구매일 거르기는 order 값이라 O-12 와 같이 보기.
- 받는 쪽: backoffice-bff `views/customer-search` 확장(PC 2).

## CU-6. 고객 최근 활동일 (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **필요한 것**: 고객 상세(`GET /customers/{id}`)와 목록에 `lastActiveAt`(마지막 로그인 또는 앱 사용 — 정의는 PC 1 이 정해 주세요). 가입 경로(socialProvider)·가입일·이메일(가림)은 이미 있음.

## CU-7. 계정 이용제한 — 사유 "욕설 사용" (2026-11-23, 보낸 영역 backoffice — PC 2, 사용자 "고객관리" 목록 — "그 세션에 바로 보내")
- **사용자 결정(2026-11-23)**: 계정 상태 관리 — 정상·이용제한·정지·탈퇴, 제한 사유·해제. 사유 예: **욕설 사용**.
- **지금**: `AccountStatus` = ACTIVE·WITHDRAW_PENDING·WITHDRAW_COMPLETED 뿐 — 제한·정지 없음.
- **필요한 것**: 직원이 제한·정지를 걸고 푸는 명령(사유 필수, 누가·언제 기록 — audit), 상태 이력, 고객 상세·목록에 상태.
- **아직 정해지지 않은 것(사용자 확인 필요 — PC 2 가 사용자에게 물을 예정)**: 제한·정지가 각각 무엇을 막는지(주문·문의·리뷰·로그인), 거는 역할(CS_AGENT·OPERATOR_ADMIN), 승인 필요 여부, 고객 알림, 기간(자동 해제).
- **결정(2026-11-23 사용자)**:
  1. **이용제한 = 문의·리뷰 작성만 막기, 정지 = 주문·로그인까지 막기**.
  2. **거는 길**: 욕설을 **자동으로 인식해서** 건다(문의 = case R-14, 리뷰 = review — PC 1). 자동이 어려운 경우를 위해 **상담원(CS_AGENT)·관리자(OPERATOR_ADMIN) 둘 다 사유를 적고** 걸 수 있다.
  3. **기간 7일, 자동 해제**.
  4. **고객 알림**: "7일 계정 정지" 를 고객에게 알린다(사용자 "고객에게 7일 계정 정지라고 알려야지") — notification. 정지는 로그인을 막으니 앱 밖 경로(문자·푸시 등)는 PC 1 이 정해 주세요.
  - PC 2 해석(다르면 사용자에게 확인): 욕설 자동 인식의 결과는 알림 문구대로 **7일 정지**. 직원이 손으로 걸 때는 제한·정지 중 고른다(둘 다 7일).
  - 받는 쪽 이벤트: case 가 낼 `CustomerProfanityDetected`(R-14) 를 customer 가 구독해 정지를 거는 모양을 제안 — 모양은 PC 1 과 맞춘다.
  5. **오인식 해제(2026-11-23 사용자 "응")**: 욕설이 아닌데 걸린 정지는 상담원·관리자가 사유를 적고 직접 푼다. 금칙어 목록은 pmart-config(사용자 승인 뒤 PC 1 반영).
