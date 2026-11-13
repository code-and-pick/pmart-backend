# [pmart-backend → pmart-clients] 안내: 프로필 변경 — 닉네임 (customer-app) (2026-11-03)

- 성격: **기존 계약의 구현 안내.** 앱이 이미 부르는 `POST /me/profile-changes` 가 501 이던 것을 닉네임부터 연다.
- 계약: `contracts/openapi/customer-service.yaml` tag `profile` — `POST /me/profile-changes`, `GET /me/profile-changes/{requestId}`. 본문 형식은 그대로이고 설명만 보탰다.
- 사용자 결정(2026-11-03): **닉네임만 먼저.** 연락처(phone)는 본인 확인 방식을 정한 뒤에 연다.

## 1. 닉네임 바꾸기
`POST /api/v1/me/profile-changes` (고객 토큰)
- 헤더: `Idempotency-Key`(= requestId, 필수) · `X-Source-System: customer-app`(필수) · `X-Expected-Version` = `GET /me` 의 `version`(권장)
- 본문: `{"nickname": "장보기왕"}`
- 응답 200: `{"status":"APPLIED","requestId":"<키>","version":<새 버전>,"occurredAt":"…"}` — **즉시 반영**이다. 화면은 `GET /me` 를 다시 읽거나 보낸 값으로 바꾸면 된다.
- 닉네임 규칙: 앞뒤 공백은 서버가 뗀다. 1~30자(이모지 1개 = 1자). 줄바꿈·탭 같은 제어 문자는 안 된다. 같은 닉네임이면 버전이 오르지 않는다.

## 2. 오류
| 상태 | code | 언제 | 앱 처리 |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | 빈 닉네임·30자 초과·제어 문자, 바꿀 값 없음, **phone 을 보냄** | `details[].field`·`reason` 을 입력 칸 아래에 |
| 409 | `VERSION_CONFLICT` | `X-Expected-Version` 이 현재와 다름 | `GET /me` 다시 읽고 다시 시도 |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 키로 다른 닉네임 | 새 키로 |
| 409 | `INVALID_STATE` | 탈퇴 신청 이후 | 변경 불가 안내 |

- 응답을 못 받았으면(시간 초과 등) **같은 키·같은 본문으로 다시 보내면** 처음 결과가 온다. 그 사이 버전이 올랐어도 409 가 아니다.
- 결과만 다시 볼 때: `GET /api/v1/me/profile-changes/{requestId}` → 같은 응답. 다른 계정의 키·없는 키는 404 `PROFILE_CHANGE_NOT_FOUND`.

## 3. 연락처
- **지금은 phone 을 보내지 않는다**(400). 앱의 연락처 수정 칸은 숨기거나 "준비 중"으로 둔다.
- 결제한 주문의 배송 연락처는 주문에 따로 저장된 사본이라, 나중에 연락처 변경이 열려도 확정 주문은 바뀌지 않는다(BR-37).

## 4. LAN
- customer-service 배포 후 `http://192.168.0.40:8080/api/v1/me/profile-changes` (게이트웨이 `/api/v1/me/**` → customer-service, 기존 라우트).
