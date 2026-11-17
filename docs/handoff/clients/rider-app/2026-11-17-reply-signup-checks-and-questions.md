# 회신 — §12 거주지·§14 번호 확인·§15 계좌 확인·§16 남은 물음·§18 배달료

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app` — 요청 `docs/handoff/rider-app/2026-11-16-backend-trip-info-and-signup.md` §12·§14·§15·§16·§18
- 계약: `rider-service.yaml` 0.7.0 — `gen:api` 다시
- 상태: 백엔드 PR 단계(rider-service). PC 1 병합·배포 뒤 날짜를 적는다. §13 은 따로(execution, 다음 회신)

## §12 거주지 — 서버가 확인(사용자 결정 "서버가 주소 변환으로 확인")
- 가입·`PATCH /riders/me` 의 `residence.roadAddress` 를 서버가 주소 검색(주문 배송지와 같은 정확 일치 변환)으로 찾는다. 못 찾으면 **422 `INVALID_ADDRESS`**, 주소 검색 장애면 503(다시 시도).
- 앱이 지금처럼 우편번호 서비스 결과의 도로명주소를 보내면 된다(필드 추가 없음). `detail` 은 확인하지 않는다.

## §14 휴대폰 번호 중복 확인
- `POST /riders/phone-checks` `{phone}`(숫자 01X…) → `{available}`. 형식이 틀리면 400. 번호는 저장·로그하지 않는다. 가입 때 다시 검사(409 DUPLICATE_RIDER).

## §15 정산 계좌 중복 확인(사용자 "중복 불가")
- `POST /riders/account-checks` `{bankCode, accountNumber}`(숫자 10~14자리) → `{available}`. 본인이 이미 등록한 계좌는 true. 은행 코드가 목록 밖이면 400.
- 가입·프로필 변경에서 다른 라이더와 같은 은행·계좌면 **409 `DUPLICATE_ACCOUNT`**.
- 두 확인 경로 모두 **면허 확인(`license-checks`)처럼 `Idempotency-Key`·`X-Source-System` 헤더가 필요**하다(공통 변경 요청 규칙 — 없으면 400).

## §16 남은 물음
1. **bankCode** — 사용자 결정 **금융결제원 은행 코드 3자리**(예: KB국민 `004`, 카카오뱅크 `090`). 목록 `GET /riders/bank-codes` → `items[{code, name}]`. 앱은 이름을 보이고 코드를 보낸다. 이름("국민은행")을 보내면 400(계약 pattern `^[0-9]{3}$`).
2. **약관 `consents[].version`** — 서버가 정하지 않는다. 앱이 보여 준 약관의 버전(예: 약관 문서의 시행일 `2026-11-17`)을 보내 달라. **version 이 없는 동의는 지금 서버가 기록하지 않는다**(가입은 되지만 동의 기록이 남지 않음) — 약관 버전이 정해지기 전까지는 앱 고정값(예 `2026-11`)이라도 보내는 것을 권한다.
3. (해결됨)
4. **수락 뒤 예정 시각** — 이미 있다: `GET /deliveries/{id}/eta`(delivery-tracking 0.6.0 — 라이더 토큰은 403이라 아직 못 쓴다). → 라이더도 자기 배송 ETA 를 보게 열어 달라는 뜻이면 다시 요청해 달라(지금은 고객·직원용). 그 전까지는 회신대로 제안 값으로.

## §18 배달료 기준
- 사용자 결정 **"앱 표 그대로"** — 서버 요율표 조회 API·동의 종류(FEE_SCHEDULE)는 만들지 않는다. 앱의 원문 §7.1 표·"실제 금액은 배차 요청마다 표시" 안내 그대로.

## §13 전달 불가 ↔ 지원센터 대화(사용자 결정 "실패 등록이 대화도 열기" — execution 0.14.0, 브랜치 `delivery/failure-help-chat`)
- `POST …/failures` 가 **같은 ID 의 도움요청(대화)을 함께 연다** — 응답 `resultId` = 도움요청 ID(= 보낸 Idempotency-Key). 대화는 `GET/POST …/help-requests/{resultId}/messages`. **앱은 도움요청을 따로 보내지 않는다**(보내면 운영자 쪽에 두 건). 도움요청 category 는 `DELIVERY_FAILURE`(도움요청 만들기로는 400).
- **전달 재개 요청**: `POST /deliveries/{id}/failures/resume-requests`(Idempotency-Key·X-Source-System, 본문 `{note?}` 300자) — 실패 등록 중(FAILURE_REPORTED)만, 아니면 409. 대화에 "[전달 재개 요청] …" 메시지가 남고 운영자 대기 목록에 표시된다. 다시 눌러도 그대로. 되돌리는 것은 지원센터 반려 — 결과는 `GET /deliveries/{id}` 상태(PICKED_UP·ARRIVED_AT_CUSTOMER 로 돌아감).
- 배송 조회 `failure.helpRequestId`·`failure.resumeRequestedAt`. 이 배포 전에 등록한 실패에는 대화가 없다.
