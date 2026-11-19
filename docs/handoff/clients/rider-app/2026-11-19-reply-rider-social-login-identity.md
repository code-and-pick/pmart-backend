# 회신 — 라이더 카카오·네이버 로그인 + 휴대폰 번호 확인(OCTOMO)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-19
- 대상: pmart-clients `apps/rider_app` — 요청 `docs/handoff/rider-app/2026-11-19-backend-rider-social-login-identity.md`(R1~R5)
- 상태: **구현 — PR 올림(PC 1 병합·배포 전)**. 사용자 결정 2026-11-19: 탈퇴 번호 **30일** 재가입 막기(R4), 계정 하나 = **로그인 수단 하나**(R5).

## R1 카카오·네이버 (rider-auth 0.2.0)
- `POST /rider-auth/social-logins {provider: GOOGLE|KAKAO|NAVER, providerToken: <ID 토큰>}` — 고객 auth 0.4.0(ADR-0071)과 같은 흐름: **ID 토큰만**(access token·인가 코드 교환·secret 없음).
  - KAKAO: 카카오 SDK 로그인 + OpenID Connect 켬 → `idToken`(aud = 라이더 앱 카카오 앱 키).
  - NAVER: OIDC 인가 코드 + PKCE(scope `openid`, client secret 없이) → `id_token`(aud = 네이버 Client ID).
- 앱 키가 서버에 설정되지 않은 제공자는 **422 CONFIG_REQUIRED** — 그동안 버튼을 숨기세요. 앱 키·패키지(`com.pmart.rider_app`)·키 해시 등록은 사용자(카카오·네이버 개발자 콘솔) → PC 1 이 pmart-config `pmart.rider-auth.kakao.client-ids`·`naver.client-ids`.
- 새 계정이면 `newAccount: true`(구글과 같음) → 가입 화면.

## R2 휴대폰 번호 확인 (rider-service 0.17.0)
- `POST /riders/me/phone-verifications {phone}` → `{verificationId, sendTo(1666-3538), text(6자리), expiresAt, phoneMasked, maxAttempts}` — 가입 전(로그인 뒤) 전화번호 단계에서.
- 라이더가 `sendTo` 로 `text` 를 문자로 보낸 뒤 "보냈어요" → `POST /riders/me/phone-verifications/{id}/confirmations {phone}`(**같은 번호를 다시** — 서버는 원문을 두지 않음) → state VERIFIED / PENDING(아직 안 옴, remainingAttempts) / FAILED(TOO_MANY_ATTEMPTS·PHONE_CHANGED) / EXPIRED. **버튼을 누를 때만** 부르세요(조회마다 월 한도).
- 가입 `POST /riders/signups` 에 `phoneVerificationId`(확인 뒤 1시간 안). 서버 설정 `required` 가 켜지면 없을 때 422 PHONE_NOT_VERIFIED — **앱이 확인 화면을 내보낸 뒤 PC 1 이 켠다**(지금은 선택).
- 그 밖: 010 아님 400 PHONE_NOT_SUPPORTED, 하루 10회 넘음 429, OCTOMO 장애 503, 설정 없음 422.

## R3 한 번호 한 계정
- 확인 시작·가입 때 그 번호로 가입한 **다른 라이더 계정**이 있으면 **409 IDENTITY_ALREADY_REGISTERED**, `details`:
  - `provider` = GOOGLE·KAKAO·NAVER, `emailMasked` = 예 `a***@k***`(2026-11-19 이전 계정은 없을 수 있음), `joinedAt`.
  - 앱: "카카오로 가입한 계정이 있어요(가입일·가린 이메일). 그 계정으로 로그인해 주세요."

## R4 탈퇴 번호
- 탈퇴한 번호는 **30일** 다시 가입 못 함 → **409 RIDER_REJOIN_BLOCKED**, `details.until`(다시 가입할 수 있는 때).

## R5 계정 하나 = 로그인 수단 하나
- 수단을 더 잇는 기능은 없다. 다른 수단으로 들어오면 새 주체 → 번호 확인에서 R3 안내.
