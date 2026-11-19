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
